--liquibase formatted sql

--changeset jcarman:008-questions
create table question (
    id           uuid primary key,
    exception_id uuid        not null references ap_case (exception_id),
    asked_of     text        not null,
    text         text        not null,
    choices      text[]      not null,
    asked_at     timestamptz not null,
    answered_by  text,
    choice       text,
    comment      text,
    answered_at  timestamptz
);
create index question_waiting on question (asked_of) where answered_at is null;
create index question_case on question (exception_id);
