--liquibase formatted sql

-- Stage 6: environment-specific SQL. Jenkins runs this directory ONLY when the
-- target environment is SIT; the release simulator excludes it entirely for PROD.

--changeset banking-team:sit-001-test-account-type context:SIT labels:envdata
--comment: SIT-only smoke-test account type

INSERT INTO account_type (code, name, description, active_flag, sort_order)
VALUES ('SITTEST', 'SIT Smoke Test', 'SIT-only row - safe to delete', 'Y', 999);

--rollback DELETE FROM account_type WHERE code = 'SITTEST';
