--liquibase formatted sql

--changeset jcarman:001-erp-schema
create table vendor (
    id            uuid primary key,
    name          text        not null,
    payment_terms text        not null,
    contact_name  text        not null,
    contact_phone text        not null,
    contact_email text        not null,
    created_at    timestamptz not null
);

create table vendor_bank_account (
    id                uuid primary key,
    vendor_id         uuid        not null references vendor (id),
    account_number    text        not null,
    routing_number    text        not null,
    status            text        not null,
    proposed_at       timestamptz not null,
    proposed_by_email text
);
create index vendor_bank_account_vendor on vendor_bank_account (vendor_id);

create table purchase_order (
    id         uuid primary key,
    po_number  text        not null unique,
    vendor_id  uuid        not null references vendor (id),
    buyer      text        not null,
    created_at timestamptz not null
);

create table po_line (
    id         uuid primary key,
    po_id      uuid           not null references purchase_order (id),
    line_no    int            not null,
    item       text           not null,
    quantity   numeric(14, 3) not null,
    unit_price numeric(14, 2) not null,
    unique (po_id, line_no)
);

create table goods_receipt (
    id          uuid primary key,
    po_id       uuid        not null references purchase_order (id),
    received_at timestamptz not null
);
create index goods_receipt_po on goods_receipt (po_id);

create table receipt_line (
    id         uuid primary key,
    receipt_id uuid           not null references goods_receipt (id),
    po_line_no int            not null,
    quantity   numeric(14, 3) not null
);

create table invoice (
    id              uuid primary key,
    vendor_id       uuid           not null references vendor (id),
    invoice_number  text           not null,
    po_number       text,
    invoice_date    date           not null,
    tax             numeric(14, 2) not null,
    freight         numeric(14, 2) not null,
    total           numeric(14, 2) not null,
    approved_amount numeric(14, 2),
    status          text           not null,
    version         bigint         not null,
    received_at     timestamptz    not null
);
create index invoice_vendor on invoice (vendor_id);

create table invoice_line (
    id          uuid primary key,
    invoice_id  uuid           not null references invoice (id),
    line_no     int            not null,
    po_line_no  int,
    description text           not null,
    quantity    numeric(14, 3) not null,
    unit_price  numeric(14, 2) not null,
    unique (invoice_id, line_no)
);

create table match_exception (
    id              uuid primary key,
    invoice_id      uuid           not null references invoice (id),
    reason_code     text           not null,
    summary         text           not null,
    amount_at_issue numeric(14, 2) not null,
    status          text           not null,
    raised_at       timestamptz    not null,
    resolved_at     timestamptz
);
create index match_exception_invoice on match_exception (invoice_id);

create table erp_audit (
    id            uuid primary key,
    at            timestamptz not null,
    entity_type   text        not null,
    entity_id     uuid        not null,
    action        text        not null,
    acting_client text        not null,
    acting_user   text,
    detail        text        not null
);
create index erp_audit_entity on erp_audit (entity_id);

create table outbox (
    id           uuid primary key,
    event_type   text        not null,
    payload      jsonb       not null,
    created_at   timestamptz not null,
    published_at timestamptz
);
create index outbox_unpublished on outbox (created_at) where published_at is null;

create table idempotency_record (
    idempotency_key text primary key,
    request_hash    text        not null,
    response_status int,
    response_body   text,
    created_at      timestamptz not null
);
