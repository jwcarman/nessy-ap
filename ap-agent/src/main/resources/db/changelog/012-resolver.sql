--liquibase formatted sql

--changeset jcarman:012-resolver
-- Who works a case. The rules claim each case the ERP raises, and keep it until they cannot
-- settle it; then its agent has it for good.
alter table ap_case add column handled_by text not null default 'agent';
-- Facts the rules learned from outside the ERP: a vendor's reply, a decider's decline.
create table case_slot (
    exception_id uuid        not null references ap_case (exception_id),
    name         text        not null,
    value        text        not null,
    source       text        not null,
    recorded_at  timestamptz not null,
    primary key (exception_id, name)
);
