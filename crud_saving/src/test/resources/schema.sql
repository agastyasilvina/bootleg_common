-- Test-only schema. The real account_tt already exists; V1__account_tt.sql carries just the
-- ALTERs to apply to it. This recreates enough of both tables for the tests to run.

-- Minimal stand-in. Your real application_tt has many more columns; the code only ever needs its
-- primary key, so nothing else is reproduced here.
CREATE TABLE IF NOT EXISTS application_tt (
    application_tt_id BIGSERIAL PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS account_tt (
    account_tt_id     BIGSERIAL   PRIMARY KEY,
    application_tt_id BIGINT      NOT NULL,
    account_number    VARCHAR(64) NOT NULL,
    a                 VARCHAR(255),
    b                 VARCHAR(255),
    c                 VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_account_tt_application_account
        UNIQUE (application_tt_id, account_number),

    CONSTRAINT fk_account_tt_application
        FOREIGN KEY (application_tt_id) REFERENCES application_tt (application_tt_id)
);
