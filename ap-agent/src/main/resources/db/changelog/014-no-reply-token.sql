--liquibase formatted sql

--changeset jcarman:014-no-reply-token
-- Nessy answers a waiting call by its agent and idempotency key: no reply token to keep. Who made
-- a proposal (the rules or an agent) is now its own column.
alter table pending_decision drop column reply_token;
alter table pending_decision add column proposer text not null default 'agent';
