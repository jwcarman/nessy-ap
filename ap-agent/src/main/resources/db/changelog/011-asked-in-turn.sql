--liquibase formatted sql

--changeset jcarman:011-asked-in-turn
-- The agent turn that asked: an agent waits for an answer before it proposes.
alter table question add column asked_in_turn bigint;
alter table outbound_mail add column asked_in_turn bigint;
