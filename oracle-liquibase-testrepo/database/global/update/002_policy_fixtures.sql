--liquibase formatted sql

-- INTENTIONAL policy fixtures — "Simulate Release" flags both changesets:
--   upd-002-reset-sort-order  UPDATE without WHERE   -> WARNING in SIT/UAT, ERROR in PROD
--   upd-003-drop-legacy       DROP TABLE (approved)  -> INFO, because the changeset
--                             carries an '--approved-destructive <ticket>' marker.
-- Remove this file if you copy the repository as a project starting point.

--changeset banking-team:upd-002-reset-sort-order labels:update
--comment: INTENTIONAL: rewrites every row - the WHERE clause is missing

UPDATE account_type SET sort_order = 0;

--rollback SELECT 1 FROM dual;

--changeset banking-team:upd-003-drop-legacy labels:update
--approved-destructive DB-1234
--comment: DBA-approved removal of the legacy rate history table (ticket DB-1234)

DROP TABLE legacy_rate_history;

--rollback SELECT 1 FROM dual;
