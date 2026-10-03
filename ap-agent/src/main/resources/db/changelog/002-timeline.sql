--liquibase formatted sql

--changeset jcarman:002-timeline
create table case_event (
    id           bigserial primary key,
    exception_id uuid        not null references ap_case (exception_id),
    at           timestamptz not null,
    kind         text        not null,
    text         text        not null
);
create index case_event_exception on case_event (exception_id, id);
