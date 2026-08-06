-- account_tt already exists; these are the two constraints the endpoint relies on.
-- Apply them as ALTERs against the live table.
--
-- Columns the code assumes:
--   account_tt_id     BIGSERIAL   PRIMARY KEY
--   application_tt_id BIGINT      NOT NULL
--   account_number    VARCHAR(64) NOT NULL
--   a, b, c           VARCHAR(255)
--   created_at        TIMESTAMPTZ NOT NULL
-- There is no updated_at: rows are deleted and reinserted, never updated, so it would always
-- equal created_at. If your table declares one NOT NULL, add the field back to AccountEntity.

-- 1. Uniqueness within an application.
--
-- Still needed even though the endpoint replaces wholesale. Two concurrent requests for the same
-- application each delete only the rows their own snapshot can see, then both insert — so without
-- this you end up with the union of both payloads rather than the last one. With it, the loser
-- fails and retries against a settled state. Application code cannot close that window.
--
-- Not DEFERRABLE: the DELETE completes before any INSERT within the transaction, so there is
-- never a transient duplicate for the check to trip over. (An earlier draft deferred it because
-- the service updated rows in place; that no longer happens.)
ALTER TABLE account_tt
    ADD CONSTRAINT uq_account_tt_application_account
    UNIQUE (application_tt_id, account_number);

-- 2. The parent link. The application row always exists by the time this endpoint is called, so
-- this should never fire — which is why it earns its place: it turns "the session resolved to a
-- stale or bogus application" into a loud violation rather than orphan rows nobody notices.
ALTER TABLE account_tt
    ADD CONSTRAINT fk_account_tt_application
    FOREIGN KEY (application_tt_id) REFERENCES application_tt (application_tt_id);

-- 3. BEFORE YOU SHIP: check whether anything references account_tt.account_tt_id.
--
--     SELECT conrelid::regclass AS referencing_table, conname
--     FROM   pg_constraint
--     WHERE  confrelid = 'account_tt'::regclass;
--
-- Replace-all deletes every account row on each call, so any child table pointing at those ids
-- either blocks the delete (FK violation, endpoint starts failing) or loses its rows silently
-- (ON DELETE CASCADE). If that query returns anything, replace-all is not safe as written.
