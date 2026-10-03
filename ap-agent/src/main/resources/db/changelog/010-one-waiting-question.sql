--liquibase formatted sql

--changeset jcarman:010-one-waiting-question
-- One question waits on a case at a time, even when two are asked at once.
create unique index question_one_waiting on question (exception_id) where answered_at is null;
