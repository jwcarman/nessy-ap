--liquibase formatted sql

--changeset jcarman:013-governance
-- What produced each proposal: proposer, models, desk build, and the versions of the playbook,
-- the decision tables and the routing policy.
create table decision_provenance (
    decision_id uuid not null primary key references pending_decision (id),
    provenance  text not null
);
-- Switches people use to oversee the agents, such as pausing them; and every change to one.
create table oversight_switch (
    name       text        not null primary key,
    on_now     boolean     not null,
    changed_by text        not null,
    changed_at timestamptz not null
);
create table oversight_change (
    id         bigserial   primary key,
    name       text        not null,
    on_now     boolean     not null,
    changed_by text        not null,
    changed_at timestamptz not null
);
-- Inputs for an agent that were held instead of told: while the agents are paused, or once a
-- case's agent has spent its budget.
create table held_input (
    id          uuid        not null primary key,
    agent_id    uuid        not null,
    exception_id uuid       not null,
    reason      text        not null,
    input       text        not null,
    held_at     timestamptz not null,
    released_at timestamptz
);
create index held_input_waiting on held_input (reason, held_at) where released_at is null;
