--liquibase formatted sql

--changeset banking-team:global-001a-drop-tmp context:GLOBAL runAlways:true failOnError:false
--comment: GTT definitions persist; drop before EVERY run (runAlways) so the re-create below never hits ORA-00955
DROP TABLE tmp_account_type;
--rollback SELECT 1 FROM dual;

--changeset banking-team:global-001-account-types context:GLOBAL labels:staticdata runOnChange:true
--comment: Upsert global account types via Oracle global temporary table (VALID example)

--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM account_type WHERE code IS NULL

CREATE GLOBAL TEMPORARY TABLE tmp_account_type (
    code        VARCHAR2(15 CHAR)  NOT NULL,
    name        VARCHAR2(100 CHAR) NOT NULL,
    description VARCHAR2(255 CHAR),
    active_flag CHAR(1)            NOT NULL
) ON COMMIT DELETE ROWS;

INSERT INTO tmp_account_type (code, name, description, active_flag)
VALUES ('SAVINGS', 'Savings Account', 'Standard savings account', 'Y');

INSERT INTO tmp_account_type (code, name, description, active_flag)
VALUES ('CURRENT', 'Current Account', 'Standard current account', 'Y');

INSERT INTO tmp_account_type (code, name, description, active_flag)
VALUES ('FIXED_DEPOSIT', 'Fixed Deposit', 'Term deposit account', 'N');

MERGE INTO account_type t
USING tmp_account_type s
ON (t.code = s.code)
WHEN MATCHED THEN
    UPDATE SET
        t.name        = s.name,
        t.description = s.description,
        t.active_flag = s.active_flag
WHEN NOT MATCHED THEN
    INSERT (code, name, description, active_flag, created_at)
    VALUES (s.code, s.name, s.description, s.active_flag, SYSDATE);

--rollback DELETE FROM account_type
--rollback WHERE code IN ('SAVINGS', 'CURRENT', 'FIXED_DEPOSIT');
