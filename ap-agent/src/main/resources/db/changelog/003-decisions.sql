--liquibase formatted sql

--changeset jcarman:003-decisions
create table pending_decision (
    id               uuid primary key,
    agent_id         uuid           not null,
    call_key         text           not null,
    reply_token      text           not null,
    exception_id     uuid           not null references ap_case (exception_id),
    invoice_id       uuid           not null,
    action           text           not null,
    amount           numeric(14, 2),
    rationale        text           not null,
    evidence         text           not null,
    deadline         timestamptz    not null,
    status           text           not null,
    decided_by       text,
    approved         boolean,
    decision_comment text,
    decided_at       timestamptz,
    expected_version bigint,
    erp_result       text,
    created_at       timestamptz    not null,
    unique (agent_id, call_key)
);
create index pending_decision_status on pending_decision (status, decided_at);
create index pending_decision_case on pending_decision (exception_id, created_at);
