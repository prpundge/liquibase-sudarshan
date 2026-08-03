--liquibase formatted sql

--changeset banking-team:003-insert-account-types context:GLOBAL labels:staticdata runOnChange:true
--comment: Upsert global account types using a temporary staging table

--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM account_type WHERE code IS NULL

CREATE TEMP TABLE tmp_account_type (
    code        VARCHAR(15)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    active      BOOLEAN      NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_account_type (
    code,
    name,
    description,
    active
)
VALUES
    ('SAVINGS', 'Savings Account', 'Standard savings account', TRUE),
    ('CURRENT', 'Current Account', 'Standard current account', TRUE);

MERGE INTO account_type AS target
USING tmp_account_type AS source
ON target.code = source.code

WHEN MATCHED THEN
    UPDATE SET
        name        = source.name,
        description = source.description,
        active      = source.active

WHEN NOT MATCHED THEN
    INSERT (
        code,
        name,
        description,
        active
    )
    VALUES (
        source.code,
        source.name,
        source.description,
        source.active
    );

--rollback DELETE FROM account_type
--rollback WHERE code IN ('SAVINGS', 'CURRENT');
