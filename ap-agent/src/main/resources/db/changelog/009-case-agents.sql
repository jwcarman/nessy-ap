--liquibase formatted sql

--changeset jcarman:009-case-agents
-- Every agent that worked a case: its own agent, and each reader that read a reply to it.
create table case_agent (
    exception_id uuid not null references ap_case (exception_id),
    agent_type   text not null,
    agent_id     uuid not null,
    primary key (exception_id, agent_type, agent_id)
);
