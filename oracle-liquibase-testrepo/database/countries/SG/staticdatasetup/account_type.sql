--liquibase formatted sql

--changeset banking-team:SG-001a-drop-tmp context:SG runAlways:true failOnError:false
--comment: GTT definitions persist; drop before EVERY run (runAlways) so the re-create below never hits ORA-00955
DROP TABLE tmp_account_type_sg;
--rollback SELECT 1 FROM dual;

--changeset banking-team:SG-001-account-types context:SG labels:staticdata runOnChange:true
--comment: INTENTIONALLY BROKEN - open in the IDE (or run the CLI) to see Liquibase Sudarshan findings

-- Expected findings in this file:
--   E1  code VARCHAR2(50)  exceeds target account_type.code VARCHAR2(15)   (quick fix offered)
--   E2  name VARCHAR2(255) exceeds target account_type.name VARCHAR2(100)
--   W1  remarks column is never used by the MERGE (warning)
--   E3  value 'SAVINGS_ACCOUNT_FOR_CORPORATE' is longer than 15 characters
--   E4  NULL into NOT NULL account_type.name
--   E5  duplicate primary key value 'SAVINGS' (rows 3 and 4)
--   E6  '' into NOT NULL name (only with the Oracle empty-string option / --oracle flag)
--   E7  'YES' does not fit active_flag CHAR(1)

CREATE GLOBAL TEMPORARY TABLE tmp_account_type_sg (
    code        VARCHAR2(50 CHAR)  NOT NULL,
    name        VARCHAR2(255 CHAR) NOT NULL,
    description VARCHAR2(255 CHAR),
    active_flag CHAR(1)            NOT NULL,
    remarks     VARCHAR2(20 CHAR)
) ON COMMIT DELETE ROWS;

INSERT INTO tmp_account_type_sg (code, name, description, active_flag)
VALUES ('SAVINGS_ACCOUNT_FOR_CORPORATE', 'Corporate Savings', 'Savings for corporates', 'Y');

INSERT INTO tmp_account_type_sg (code, name, description, active_flag)
VALUES ('FIXED', NULL, 'Fixed deposit', 'Y');

INSERT INTO tmp_account_type_sg (code, name, description, active_flag)
VALUES ('SAVINGS', 'Savings A', 'First savings', 'Y');

INSERT INTO tmp_account_type_sg (code, name, description, active_flag)
VALUES ('SAVINGS', 'Savings B', 'Second savings', 'Y');

INSERT INTO tmp_account_type_sg (code, name, description, active_flag)
VALUES ('MULTI', '', 'Multi currency', 'YES');

MERGE INTO account_type t
USING tmp_account_type_sg s
ON (t.code = s.code)
WHEN MATCHED THEN
    UPDATE SET
        t.name        = s.name,
        t.description = s.description,
        t.active_flag = s.active_flag
WHEN NOT MATCHED THEN
    INSERT (code, name, description, active_flag)
    VALUES (s.code, s.name, s.description, s.active_flag);

-- rollback only deletes SG-introduced codes; 'SAVINGS' belongs to the GLOBAL dataset
--rollback DELETE FROM account_type
--rollback WHERE code IN ('FIXED', 'MULTI');
