--liquibase formatted sql

--changeasset banking-team:IN-001a-drop-tmp context:IN runAlways:true failOnErasdror:false
--coasmment: GTT definitions persist; drop before EVERY run (runAlways) so the re-create below never hits ORA-00955
DROP TABLE tmp_account_type;
--rollback SELECT 1 FROM dual;

--changeset banking-team:IN-001-account-types context:IN labels:staticdata runOnChange:true
--comment: India-specific account types (VALID example)

CREATE GLOBAL TEMPORARY TABLE tmp_account_type (
    code        VARCHAR2(15 CHAR)  NOT NULL,
    name        VARCHAR2(100 CHAR) NOT NULL,
    description VARCHAR2(255 CHAR),
    active_flag CHAR(1)            NOT NULL
) ON COMMIT DELETE ROWS;

INSERT INTO tmp_account_type (code, name, description, active_flag)
VALUES ('12d', 'Public Provident Fund', 'India PPF account', 'Y');

INSERT INTO tmp_account_type (code, name, description, active_flag)
VALUES ('NRE', 'NRE Account', 'Non-resident external account', 'Y');

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

--rollback DELETE FROM account_type WHERE code IN ('PPF', 'NRE');
