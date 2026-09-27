CREATE SCHEMA IF NOT EXISTS agent;
CREATE TABLE agent.character_profile (
    id VARCHAR(64) PRIMARY KEY CHECK(id ~ '^[a-z][a-z0-9-]{2,63}$'),
    name TEXT NOT NULL CHECK(btrim(name) <> ''),
    gender VARCHAR(6) NOT NULL CHECK(gender IN ('female','male')),
    age INT NOT NULL CHECK(age BETWEEN 20 AND CASE WHEN gender='female' THEN 35 ELSE 30 END),
    country TEXT NOT NULL CHECK(btrim(country) <> ''),
    city TEXT NOT NULL CHECK(btrim(city) <> ''),
    native_language TEXT NOT NULL CHECK(btrim(native_language) <> ''),
    timezone TEXT NOT NULL CHECK(btrim(timezone) <> ''),
    occupation TEXT NOT NULL CHECK(btrim(occupation) <> ''),
    interests TEXT[] NOT NULL CHECK(cardinality(interests) >= 3 AND array_position(interests, NULL) IS NULL),
    persona TEXT NOT NULL CHECK(btrim(persona) <> '')
);
CREATE TABLE agent.agent_account (
    character_id VARCHAR(64) PRIMARY KEY REFERENCES agent.character_profile(id),
    username VARCHAR(30) NOT NULL UNIQUE CHECK(username ~ '^[a-z0-9_.-]{3,30}$'),
    password TEXT NOT NULL CHECK(length(password) >= 16 AND octet_length(password) <= 72 AND btrim(password) <> ''),
    enabled BOOLEAN NOT NULL DEFAULT false,
    member_id UUID UNIQUE,
    backend_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK((member_id IS NULL) = (backend_url IS NULL))
);
CREATE TABLE agent.conversation (
    character_id VARCHAR(64) NOT NULL REFERENCES agent.agent_account(character_id),
    conversation_id UUID NOT NULL,
    summary TEXT,
    summary_through BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(character_id, conversation_id)
);
CREATE TABLE agent.chat_message (
    character_id VARCHAR(64) NOT NULL,
    conversation_id UUID NOT NULL,
    message_id UUID NOT NULL,
    position BIGINT NOT NULL CHECK(position > 0),
    direction VARCHAR(3) NOT NULL CHECK(direction IN ('IN','OUT')),
    content TEXT NOT NULL,
    wire_payload TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(character_id, message_id),
    UNIQUE(character_id, conversation_id, position, direction),
    FOREIGN KEY(character_id, conversation_id) REFERENCES agent.conversation(character_id, conversation_id)
);
CREATE TABLE agent.reply_job (
    id UUID PRIMARY KEY,
    character_id VARCHAR(64) NOT NULL,
    conversation_id UUID NOT NULL,
    incoming_message_id UUID NOT NULL,
    position BIGINT NOT NULL,
    state VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','PROCESSING','DONE','DEAD')),
    attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    reply TEXT,
    outbound_frame TEXT,
    last_error VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    UNIQUE(character_id, incoming_message_id),
    FOREIGN KEY(character_id, conversation_id) REFERENCES agent.conversation(character_id, conversation_id)
);
CREATE INDEX reply_job_ready_idx ON agent.reply_job(available_at, created_at) WHERE state IN ('PENDING','PROCESSING');
CREATE INDEX reply_job_conversation_idx ON agent.reply_job(character_id,conversation_id,position);
CREATE TABLE agent.peer_key (
    character_id VARCHAR(64) NOT NULL REFERENCES agent.agent_account(character_id),
    conversation_id UUID NOT NULL,
    key_id UUID NOT NULL,
    encryption_public_key VARCHAR(64) NOT NULL,
    signing_public_key VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    PRIMARY KEY(character_id,conversation_id,key_id)
);
