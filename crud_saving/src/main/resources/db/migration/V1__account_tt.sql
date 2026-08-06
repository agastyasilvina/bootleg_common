-- account_tt already exists in your schema; this is the shape the code assumes plus the two
-- constraints that make the reconcile safe. Apply them as ALTERs against the live table rather
-- than running the CREATE.

-- CREATE TABLE IF NOT EXISTS account_tt (
--     account_tt_id     BIGSERIAL   PRIMARY KEY,
--     application_tt_id BIGINT      NOT NULL,
--     account_number    VARCHAR(64) NOT NULL,
--     a                 VARCHAR(255),
--     b                 VARCHAR(255),
--     c                 VARCHAR(255),
--     created_at        TIMESTAMPTZ NOT NULL,
--     updated_at        TIMESTAMPTZ NOT NULL
-- );

-- 1. The uniqueness the service depends on.
--
-- This is not an optimisation. The service reads the application's accounts, decides which
-- account numbers are new, then inserts them — and two concurrent requests can both make that
-- decision before either commits. Nothing in application code can close that window; only the
-- database can. Without this you get duplicate account_numbers under one application, and every
-- later request then fails on Collectors.toMap.
--
-- DEFERRED so the check runs at COMMIT rather than per statement, which makes the
-- delete-before-insert ordering in the service belt-and-braces rather than a correctness
-- requirement. Trade-off: a deferrable constraint cannot back an ON CONFLICT clause, so if you
-- ever rewrite the reconcile as an upsert, drop DEFERRABLE and keep the ordering mandatory.
ALTER TABLE account_tt
    ADD CONSTRAINT uq_account_tt_application_account
    UNIQUE (application_tt_id, account_number)
    DEFERRABLE INITIALLY DEFERRED;

-- 2. The parent link. The application row always exists before this endpoint is called, so the
-- FK should never fire in normal operation — which is exactly why it is worth having: it turns
-- "the session resolved to a stale or bogus application" into a loud constraint violation
-- instead of orphan rows nobody notices until a join starts dropping accounts.
ALTER TABLE account_tt
    ADD CONSTRAINT fk_account_tt_application
    FOREIGN KEY (application_tt_id) REFERENCES application_tt (application_tt_id);

-- 3. findByApplicationTtId runs on every request. The unique constraint's index leads with
-- application_tt_id and already serves this, so add it only if you drop or reshape that
-- constraint.
-- CREATE INDEX IF NOT EXISTS idx_account_tt_application ON account_tt (application_tt_id);
