--liquibase formatted sql

--changeset jcarman:006-camel-idempotent
-- Camel's JDBC idempotent repository table, with the names its SQL uses (unquoted, so Postgres
-- folds them to lower case). Message ids are kept up to 1000 characters; the route hashes longer.
create table camel_messageprocessed (
    processorname varchar(255)  not null,
    messageid     varchar(1000) not null,
    createdat     timestamp     not null,
    primary key (processorname, messageid)
);

-- The desk's inbox route remembers handled Message-IDs in Camel's table instead.
drop table inbound_mail;
