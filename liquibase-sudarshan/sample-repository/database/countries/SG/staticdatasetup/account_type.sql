--liquibase formatted sql

--changeset banking-team:SG-001-account-types context:SG labels:staticdata runOnChange:true
--comment: INTENTIONALLY BROKEN example — open it in the IDE to see Liquibase Sudarshan errors

-- ERROR 1: code VARCHAR(50) exceeds target account_type.code VARCHAR(15)  (quick fix offered)
-- ERROR 2: name VARCHAR(255) exceeds target account_type.name VARCHAR(100)
CREATE TEMP TABLE tmp_account_type (
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    active      BOOLEAN      NOT NULL,
    -- WARNING: extra_column is never used by the MERGE below
    extra_column VARCHAR(20)
) ON COMMIT DROP;

INSERT INTO tmp_account_type (code, name, description, active, extra_column)
VALUES
    -- ERROR 3: value is longer than account_type.code VARCHAR(15)
    ('SAVINGS_ACCOUNT_FOR_CORPORATE', 'Savings', 'desc', TRUE, 'x'),
    -- ERROR 4: NULL into NOT NULL account_type.name
    ('FIXED', NULL, 'desc', TRUE, 'x'),
    -- ERROR 5 + 6: duplicate primary key value 'SAVINGS' (rows 3 and 4)
    ('SAVINGS', 'Savings A', 'desc', TRUE, 'x'),
    ('SAVINGS', 'Savings B', 'desc', TRUE, 'x');

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

--rollback DELETE FROM account_type WHERE code IN ('SAVINGS', 'FIXED');
