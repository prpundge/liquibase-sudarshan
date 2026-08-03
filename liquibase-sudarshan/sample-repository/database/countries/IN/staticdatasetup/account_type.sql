--liquibase formatted sql

--changeset banking-team:IN-001-account-types context:IN labels:staticdata runOnChange:true
--comment: India-specific account types (valid example)

CREATE TEMP TABLE tmp_account_type (
    code        VARCHAR(15)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    active      BOOLEAN      NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_account_type (code, name, description, active)
VALUES
    ('PPF', 'Public Provident Fund', 'India PPF account', TRUE),
    ('NRE', 'NRE Account', 'Non-resident external account', TRUE);

MERGE INTO account_type AS target
USING tmp_account_type AS source
ON target.code = source.code
WHEN MATCHED THEN
    UPDATE SET
        name        = source.name,
        description = source.description,
        active      = source.active
WHEN NOT MATCHED THEN
    INSERT (code, name, description, active)
    VALUES (source.code, source.name, source.description, source.active);

--rollback DELETE FROM account_type WHERE code IN ('PPF', 'NRE');
