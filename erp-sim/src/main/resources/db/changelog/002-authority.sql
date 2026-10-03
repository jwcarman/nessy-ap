--liquibase formatted sql

--changeset jcarman:002-authority
-- Who may decide what, and up to how much. The ERP owns this: Keycloak only says who someone is.
-- max_amount null means no limit; own_po_only means only on purchase orders the person placed.
create table authority_grant (
    username    text           not null,
    action      text           not null,
    max_amount  numeric(14, 2),
    own_po_only boolean        not null,
    primary key (username, action)
);

insert into authority_grant (username, action, max_amount, own_po_only) values
    ('clara',  'hold',                null,     false),
    ('clara',  'request-credit-memo', null,     false),
    ('bob',    'approve-variance',    10000.00, true),
    ('mark',   'hold',                10000.00, false),
    ('mark',   'release-hold',        10000.00, false),
    ('mark',   'request-credit-memo', 10000.00, false),
    ('mark',   'approve-variance',    10000.00, false),
    ('mark',   'short-pay',           10000.00, false),
    ('mark',   'reject',              10000.00, false),
    ('connie', 'hold',                null,     false),
    ('connie', 'release-hold',        null,     false),
    ('connie', 'request-credit-memo', null,     false),
    ('connie', 'approve-variance',    null,     false),
    ('connie', 'short-pay',           null,     false),
    ('connie', 'reject',              null,     false);
