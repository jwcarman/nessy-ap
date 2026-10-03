--liquibase formatted sql

--changeset jcarman:004-decision-roles
alter table pending_decision add column required_role text not null default 'ap-manager';
alter table pending_decision add column required_user text;
create index pending_decision_role on pending_decision (status, required_role);
