# Instantly Agents

Java 26 / Spring Boot 4.1 service for Instantly's fictional adult profiles. Each
configured backend account has one character stored in PostgreSQL. The first release
receives encrypted text messages, saves conversation memory in its own PostgreSQL,
generates character-consistent replies through OpenAI Responses, and sends them
through the existing Instantly WebSocket API.

The initial SQL seed contains **30 women aged 20–35 and 20 men aged 20–30**, from Türkiye and
Europe. All characters can respond in the other person's language. The shared
social policy allows mild, receptive adult flirting without sexual content.

## Architecture

The layout follows `instantly-be`:

```text
bootstrap/                         executable application, wiring, lifecycle, health
modules/agent/
  domain/                          framework-free character and conversation types
  application/port/in/             receive-message and process-reply contracts
  application/port/out/            storage, model, catalog and transport contracts
  application/usecase/             conversation and reply orchestration
  adapter/in/internal/             background workers
  adapter/out/                     JDBC, OpenAI, HTTP/WebSocket, crypto
  src/main/resources/prompts/      shared, iterative social policy
  src/main/resources/db/changelog/ module-owned schema migration
  src/main/resources/db/seed/      manually applied initial character/account DML
```

There is one agent domain module and no empty BFF/shared kernel. PostgreSQL is the
source of truth; Redis is not required in this version. Liquibase runs the master
changelog in `bootstrap` and creates the module's `agent` schema. Add new change
sets for future schema changes; never edit a migration after deployment.

The service never reads or writes the backend database. Backend responsibilities
remain authentication, profiles, conversation membership, anonymity and delivery.
`isAi` stays a profile property; no AI flags are added to chat frames.

## Run locally

Prerequisites: JDK 26, Docker, and an Instantly backend with the agent login change
([backend PR #74](https://github.com/ofaladag/instantly-be/pull/74)). Maven 3.9.16
is pinned by the wrapper.

```sh
docker compose up -d postgres
./mvnw verify
java -jar bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar
```

By default `AGENTS_ENABLED=false`: migrations and the local
health endpoint run, with **no account logins or model calls**.
An empty catalog is valid in this mode so the schema can be created before seeding.
Health: `http://127.0.0.1:8081/actuator/health`.

## Initial characters and accounts

After Liquibase creates the schema, run the standalone DML against the **agents
database**. The application does not automatically run it:

```sh
psql -h localhost -p 5433 -U agents -d instantly_agents -v ON_ERROR_STOP=1 \
  -f modules/agent/src/main/resources/db/seed/initial-agents.sql
```

`agent.character_profile` holds the name, gender, age, location, native language,
timezone, occupation, interests and complete persona for each character.
`agent.agent_account` holds its unique username, plaintext password and `enabled`
flag. The script generates usernames such as `ai_aylin_izmir` and a distinct random
36-character password per new account using PostgreSQL's built-in `gen_random_uuid()`;
it needs no extension. Credentials are generated when the SQL runs, not embedded
in source files. All 50 accounts start disabled. Re-running the DML inserts missing
records without replacing existing personas, passwords, activation states or bindings.

Read the generated credentials to provision matching backend AI accounts:

```sql
SELECT character_id, username, password
FROM agent.agent_account ORDER BY character_id;
```

To activate selected characters after seeding:

1. Deploy the backend agent-login support and set its `AGENT_LOGIN_ENABLED=true`.
   Provision AI accounts using the backend's trusted `scripts/create-ai-profile.py`
   process with the exact usernames/passwords from the query above. The agent service
   does not create backend accounts or promote human profiles.
2. Set `enabled=true` on the corresponding `agent.agent_account` rows only after
   those accounts exist in the backend, for example:
   `UPDATE agent.agent_account SET enabled=true WHERE character_id='aylin-izmir';`
3. Generate `AGENT_IDENTITY_KEY` once with `openssl rand -base64 32`, and keep it in
   a secret manager or external environment. It protects only private identity files
   in `keys/`; PostgreSQL conversation data does not require an encryption key.
   Back up the key and identity files together. Replacing the key is not a key-rotation
   procedure.
4. Export `OPENAI_API_KEY`, `OPENAI_MODEL`, `AGENT_IDENTITY_KEY`, `INSTANTLY_API_URL`
   and the DB settings from `.env.example`. Spring does not automatically load
   `.env`. Model selection is explicit: use a Responses-compatible model available
   in your OpenAI API project. A ChatGPT subscription is separate from API access.
5. Set `AGENTS_ENABLED=true` and restart the service. At least one account must be
   enabled in the database. Start with a test account and test conversations to
   evaluate character behavior before activating the full roster.

Character edits in PostgreSQL apply to the next newly generated reply. Account
credentials and activation flags are loaded at startup, so restart after changing
them. On the first successful login, the service fills `member_id` and `backend_url`
and refuses later logins that would bind the same character to a different identity.
Only the shared social policy remains a code resource; there are no character
Markdown files or JSON credential files to synchronize.

Remote backend URLs require HTTPS; HTTP is allowed only on loopback for development.
The server binds loopback by default. The Docker image uses an unprivileged UID
10001; mount its key directory with compatible ownership and
permissions. Compose starts only the separate local PostgreSQL instance on port 5433.

## Message durability and privacy

- Authentication uses `/api/v1/auth/agents/login`; refresh-token rotation is
  serialized per account. Tokens remain in memory. Profile `isAi` is checked at login.
- Character definitions and agent usernames/passwords are stored directly in PostgreSQL,
  with no application encryption or password hashing in this service. Credentials
  are excluded from application logs and object string representations.
- A database advisory lock enforces **one active runtime per agents database**.
  Configure only one deployment/database for a set of backend accounts. Do not log
  these accounts into a mobile app or another worker: backend delivery ACKs remove
  its ciphertext for the entire account.
- Keys persist outside PostgreSQL in encrypted owner-only files. X25519 +
  HKDF-SHA256 + ChaCha20-Poly1305 matches the iOS v1 text envelope; Ed25519 signs
  key-registration proofs. Existing mismatched/lost identity keys stop processing;
  the service never silently resets an account's crypto identity.
- Input text, original wire payload and a pending reply job commit in one transaction
  **before** `delivery.ack`. Redelivered message IDs cannot create duplicate jobs.
- Conversation memory is scoped by character and conversation. Message text,
  summaries, prepared replies and serialized wire frames are stored as plain `TEXT`
  in PostgreSQL, without an additional application encryption layer. Wire frames
  still contain the end-to-end `encryptedEnvelope` needed for delivery and exact
  retries. Database access controls, TLS, disk and backup encryption are infrastructure
  responsibilities. The service supplies decrypted message text to OpenAI for inference.
- Workers lease jobs using `FOR UPDATE SKIP LOCKED`. Jobs in a conversation run in
  order; stale workers cannot overwrite a newer lease. Expired leases are recovered.
- The generated reply and exact outgoing frame are persisted before transmission.
  Uncertain sends retry the same ciphertext and `clientMessageId`. Only a definite
  `key_changed` rejection permits resealing with a fresh ID. `idempotency_conflict`
  is terminal and requires investigation.
- `message.accepted` records backend command acceptance; it does **not** prove the
  peer received the message, including when the backend silently applies a block.
- Context keeps recent turns and a rolling summary. Long input is bounded before
  inference. Responses use `store:false`; this does not itself disable OpenAI abuse
  monitoring retention. See the [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data).

## Operations and current boundaries

The first release supports incoming **text replies**. Proactive DMs, instant posts,
media, typing simulation, per-user opt-outs beyond backend blocks, automated
retention/deletion workflows and multi-instance account sharding are later work.
Do not use autonomous agent-to-agent conversations: the service currently replies
to incoming messages without identifying anonymous peers as AI.

Unsupported media/type/key versions stop that account and leave the delivery
unacknowledged; otherwise ACK would delete content this version cannot fully save.
Backend retention still applies. Add media support or resolve the account's inbox
before restarting; repeatedly restarting does not fix an unsupported message.

After eight attempts (configurable), a job enters `DEAD`. Health becomes `DOWN`
when configured accounts are not all ready or dead jobs exist. Detailed health
payloads are disabled by default. Logs include character IDs and stable error codes,
never credentials, message bodies or prompts. Inspect job status using metadata:

```sql
SELECT id, character_id, conversation_id, state, attempts, last_error, created_at
FROM agent.reply_job WHERE state <> 'DONE' ORDER BY created_at;
```

Investigate the cause before a deliberate operator retry. Preserve a prepared frame
after an uncertain send. Do not clear `outbound_frame` or create a new client ID to
work around an idempotency error. Back up PostgreSQL and identity keys before maintenance.
Treat changes to character IDs/account bindings as a migration, not configuration edits.

## Verification

`./mvnw verify` requires Docker and fails if PostgreSQL integration tests cannot run.
It covers initial DML execution and safe re-runs, roster constraints, database-loaded
credentials and personas, hexagonal dependency rules, plaintext database storage,
encrypted identity files, CryptoKit interoperability, key persistence, duplicate
input, transaction rollback, conversation isolation, concurrent leasing, lease recovery,
Responses request format, and a local HTTP/WebSocket backend with a deliberately lost send acceptance.
The tests use a fake model/backend and do not contact OpenAI or production users.

The independent Apple fixture can be regenerated on macOS:

```sh
xcrun swift modules/agent/src/test/resources/ios-envelope-vector.swift \
  > modules/agent/src/test/resources/ios-envelope-vector.json
```

Live backend compatibility and model conversational quality still require a staging
run with provisioned accounts and API credentials. API implementation reference:
[official OpenAI Java library](https://developers.openai.com/api/docs/libraries),
[text generation](https://developers.openai.com/api/docs/guides/text).
