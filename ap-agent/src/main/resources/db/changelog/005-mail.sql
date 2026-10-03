--liquibase formatted sql

--changeset jcarman:005-mail
create table outbound_mail (
    id           uuid primary key,
    exception_id uuid        not null references ap_case (exception_id),
    kind         text        not null check (kind in ('buyer', 'vendor')),
    recipient    text        not null,
    subject      text        not null,
    body         text        not null,
    message_id   text        not null unique,
    sent_at      timestamptz not null
);
create index outbound_mail_case on outbound_mail (exception_id, kind);

create table inbound_mail (
    message_id   text primary key,
    exception_id uuid references ap_case (exception_id),
    received_at  timestamptz not null
);

create table unmatched_mail (
    id          uuid primary key,
    message_id  text        not null,
    sender      text        not null,
    subject     text        not null,
    body        text        not null,
    received_at timestamptz not null
);
