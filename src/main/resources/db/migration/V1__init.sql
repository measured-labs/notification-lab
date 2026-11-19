-- ADR-000 §5 — PK와 FK만 둔다. event_id 유니크 · 상태 CHECK · 조회용 인덱스는 일부러 없다(베이스라인)

CREATE TABLE campaign (
    id             BIGSERIAL     PRIMARY KEY,
    name           VARCHAR(100)  NOT NULL,
    channel        VARCHAR(10)   NOT NULL,   -- PUSH · SMS · EMAIL
    user_id_start  BIGINT        NOT NULL,
    target_count   INT           NOT NULL,
    title          VARCHAR(100)  NOT NULL,
    body           VARCHAR(1000) NOT NULL,
    status         VARCHAR(20)   NOT NULL,   -- RUNNING · COMPLETED
    created_at     TIMESTAMPTZ   NOT NULL,
    completed_at   TIMESTAMPTZ
);

CREATE TABLE notification (
    id                   BIGSERIAL     PRIMARY KEY,
    kind                 VARCHAR(20)   NOT NULL,   -- TRANSACTIONAL · CAMPAIGN
    event_id             VARCHAR(64),              -- TRANSACTIONAL만
    event_type           VARCHAR(50),              -- TRANSACTIONAL만
    campaign_id          BIGINT        REFERENCES campaign (id),
    user_id              BIGINT        NOT NULL,
    channel              VARCHAR(10)   NOT NULL,
    title                VARCHAR(100)  NOT NULL,
    body                 VARCHAR(1000) NOT NULL,
    status               VARCHAR(20)   NOT NULL,   -- REQUESTED · SENT · FAILED
    attempts             INT           NOT NULL,
    provider_message_id  VARCHAR(64),
    last_error           VARCHAR(200),
    requested_at         TIMESTAMPTZ   NOT NULL,
    sent_at              TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ   NOT NULL
);
