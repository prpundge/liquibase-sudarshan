--liquibase formatted sql

-- Stage 6: environment-specific SQL for UAT deployments only (never PROD).

--changeset banking-team:uat-001-test-account-type context:UAT labels:envdata
--comment: UAT-only smoke-test account type

INSERT INTO account_type (code, name, description, active)
VALUES ('UATTEST', 'UAT Smoke Test', 'UAT-only row - safe to delete', TRUE);

--rollback DELETE FROM account_type WHERE code = 'UATTEST';
