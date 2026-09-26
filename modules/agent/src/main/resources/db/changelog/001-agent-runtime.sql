CREATE SCHEMA IF NOT EXISTS agent;
CREATE TABLE agent.agent_account (
    character_id VARCHAR(64) PRIMARY KEY,
    member_id UUID NOT NULL UNIQUE,
    backend_url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE agent.conversation (
    character_id VARCHAR(64) NOT NULL REFERENCES agent.agent_account(character_id),
    conversation_id UUID NOT NULL,
    summary BYTEA,
    summary_through BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(character_id, conversation_id)
);
CREATE TABLE agent.chat_message (
    character_id VARCHAR(64) NOT NULL,
    conversation_id UUID NOT NULL,
    message_id UUID NOT NULL,
    position BIGINT NOT NULL CHECK(position > 0),
    direction VARCHAR(3) NOT NULL CHECK(direction IN ('IN','OUT')),
    content BYTEA NOT NULL,
    wire_payload BYTEA,
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
    reply BYTEA,
    outbound_frame BYTEA,
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
