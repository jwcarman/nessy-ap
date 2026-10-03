--liquibase formatted sql

--changeset jcarman:001-cases
create table ap_case (
    exception_id   uuid primary key,
    agent_id       uuid           not null unique,
    invoice_id     uuid           not null,
    invoice_number text           not null,
    vendor_id      uuid           not null,
    po_number      text,
    reason_code    text           not null,
    amount         numeric(14, 2) not null,
    status         text           not null,
    opened_at      timestamptz    not null,
    updated_at     timestamptz    not null
);
create index ap_case_po on ap_case (po_number);

create table inbound_event (
    event_id    uuid primary key,
    event_type  text        not null,
    received_at timestamptz not null
);
