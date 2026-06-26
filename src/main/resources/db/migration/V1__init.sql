-- Initial schema for the wallet service (PostgreSQL).
--
-- This migration is the source of truth for the schema in real deployments:
-- on the `postgres` profile Flyway applies it and Hibernate runs with
-- ddl-auto=validate, so the running entities are checked against this schema
-- rather than the schema being silently generated from the entities.
--
-- The column types mirror the JPA entity mappings exactly (UUID ids, NUMERIC(19,4)
-- money, TIMESTAMP WITH TIME ZONE instants) so validation passes.

create table accounts (
    id         uuid           not null,
    currency   varchar(3)     not null,
    balance    numeric(19, 4) not null,
    created_at timestamp(6) with time zone not null,
    primary key (id)
);

create table transactions (
    id              uuid           not null,
    idempotency_key varchar(255),
    type            varchar(16)    not null,
    from_account_id uuid,
    to_account_id   uuid,
    amount          numeric(19, 4) not null,
    currency        varchar(3)     not null,
    timestamp       timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_transaction_idempotency_key unique (idempotency_key)
);

create table ledger_entries (
    id             uuid           not null,
    account_id     uuid           not null,
    transaction_id uuid           not null,
    amount         numeric(19, 4) not null,
    balance_after  numeric(19, 4) not null,
    timestamp      timestamp(6) with time zone not null,
    primary key (id)
);

create index idx_ledger_account on ledger_entries (account_id);
