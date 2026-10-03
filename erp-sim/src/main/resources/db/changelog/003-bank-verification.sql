--liquibase formatted sql

--changeset jcarman:003-bank-verification
-- A bank change is verified by a call to the contact of record, then confirmed by someone else.
alter table vendor_bank_account add column call_back_by text;
alter table vendor_bank_account add column call_back_phone text;
alter table vendor_bank_account add column call_back_confirmed boolean;
alter table vendor_bank_account add column called_at timestamptz;
alter table vendor_bank_account add column confirmed_by text;
alter table vendor_bank_account add column confirmed_at timestamptz;

insert into authority_grant (username, action, max_amount, own_po_only) values
    ('mark',   'verify-bank-change', null, false),
    ('connie', 'verify-bank-change', null, false);
