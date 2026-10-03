--liquibase formatted sql

--changeset jcarman:007-quarantine
-- Untrusted mail text lives only in the quarantine (Occlude). These tables keep its handle.
alter table case_event add column mail_handle text;

alter table unmatched_mail drop column sender;
alter table unmatched_mail drop column subject;
alter table unmatched_mail drop column body;
alter table unmatched_mail add column mail_handle text not null;

-- The case's integrity label: the join of what its agent has read.
alter table ap_case add column influenced_by_unendorsed boolean not null default false;
alter table ap_case add column instructions_seen boolean not null default false;
