# Instantly Agents

Java 26 / Spring Boot 4.1 service for Instantly's fictional adult profiles. Each
configured backend account has one stable Markdown character. The first release
receives encrypted text messages, saves conversation memory in its own PostgreSQL,
generates character-consistent replies through OpenAI Responses, and sends them
through the existing Instantly WebSocket API.

The catalog contains **30 women aged 20–35 and 20 men aged 20–30**, from Türkiye and
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
  adapter/out/                     JDBC, Markdown, OpenAI, HTTP/WebSocket, crypto
  src/main/resources/characters/   50 individually versioned character files
  src/main/resources/prompts/      shared, iterative social policy
  src/main/resources/db/           module-owned schema migration
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

By default `AGENTS_ENABLED=false`: migrations, catalog validation and the local
health endpoint run, with **no account logins or model calls**.
Health: `http://127.0.0.1:8081/actuator/health`.

To activate selected characters:

1. Deploy the backend agent-login support and set its `AGENT_LOGIN_ENABLED=true`.
   Provision AI accounts using the backend's trusted `scripts/create-ai-profile.py`
   process. The agent service does not create accounts or promote human profiles.
2. Copy `accounts.example.json` to `secrets/accounts.json`, set permission `0600`,
   and fill in real provisioned credentials. Each entry maps a unique character ID
   to a unique account. Include only accounts you intend to activate; the catalog
   does not automatically create or activate all 50 accounts.
3. Generate `AGENT_IDENTITY_KEY` once with `openssl rand -base64 32`, and keep it in
   a secret manager or external environment. It protects only private identity files
   in `keys/`; PostgreSQL conversation data does not require an encryption key.
   Back up the key and identity files together. Replacing the key is not a key-rotation
   procedure.
4. Export `OPENAI_API_KEY`, `OPENAI_MODEL`, `AGENT_IDENTITY_KEY`, `INSTANTLY_API_URL`
   and the DB settings from `.env.example`. Spring does not automatically load
   `.env`. Model selection is explicit: use a Responses-compatible model available
   in your OpenAI API project. A ChatGPT subscription is separate from API access.
5. Set `AGENTS_ENABLED=true` and run the jar. Start with a test account and test
   conversations to evaluate character behavior before activating the full roster.

Remote backend URLs require HTTPS; HTTP is allowed only on loopback for development.
The server binds loopback by default. The Docker image uses an unprivileged UID
10001; mount its credential file and key directory with compatible ownership and
permissions. Compose starts only the separate local PostgreSQL instance on port 5433.

## Message durability and privacy

- Authentication uses `/api/v1/auth/agents/login`; refresh-token rotation is
  serialized per account. Tokens remain in memory. Profile `isAi` is checked at login.
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
It covers roster constraints, hexagonal dependency rules, plaintext database storage,
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
