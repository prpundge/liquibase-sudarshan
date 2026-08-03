--liquibase formatted sql

--changeset banking-team:US-001a-drop-tmp context:US runAlways:true failOnError:false
--comment: GTT definitions persist; drop before EVERY run (runAlways) so the re-create below never hits ORA-00955
DROP TABLE tmp_customer_type_us;
--rollback SELECT 1 FROM dual;

--changeset banking-team:US-001-customer-types context:US labels:staticdata runOnChange:true
--comment: INTENTIONALLY BROKEN - Oracle NUMBER precision/scale and MERGE mapping violations

-- Expected findings in this file:
--   E1  staging discount_rate NUMBER(7,3) exceeds target customer_type.discount_rate NUMBER(5,2)
--   E2  priority value 12345 exceeds NUMBER(3) precision
--   E3  discount_rate value 12.345 exceeds NUMBER(5,2) scale
--   E4  column 'not_a_column' does not exist in the staging table
--   E5  INSERT has 2 values but 3 columns
--   E6  MERGE references s.missing_col which does not exist in the staging table
--   E7  duplicate primary key value id=1 (rows 1 and 6)
--   E8  NOT NULL column 'name' is missing from an INSERT column list
--   E9  '2024-13-01 10:30:00' is not a valid TIMESTAMP (month 13)
--   E10 duplicate changeset id 'banking-team:US-001-customer-types'
--   W2  rollback references unknown table 'no_such_table'

CREATE GLOBAL TEMPORARY TABLE tmp_customer_type_us (
    id            NUMBER(10)         NOT NULL,
    code          VARCHAR2(10 CHAR)  NOT NULL,
    name          VARCHAR2(100 CHAR) NOT NULL,
    priority      NUMBER(3),
    discount_rate NUMBER(7,3),
    created_at    TIMESTAMP
) ON COMMIT DELETE ROWS;

INSERT INTO tmp_customer_type_us (id, code, name, priority, discount_rate, created_at)
VALUES (1, 'RETAIL', 'Retail Customer', 999, 10.25, SYSTIMESTAMP);

INSERT INTO tmp_customer_type_us (id, code, name, priority, discount_rate, created_at)
VALUES (2, 'CORP', 'Corporate Customer', 12345, 5.50, SYSTIMESTAMP);

INSERT INTO tmp_customer_type_us (id, code, name, priority, discount_rate, created_at)
VALUES (3, 'GOV', 'Government Customer', 1, 12.345, SYSTIMESTAMP);

INSERT INTO tmp_customer_type_us (id, code, name, not_a_column)
VALUES (4, 'SME', 'Small Business', 'oops');

INSERT INTO tmp_customer_type_us (id, code, name)
VALUES (5, 'ENT');

INSERT INTO tmp_customer_type_us (id, code, name, priority, discount_rate, created_at)
VALUES (1, 'RETAIL2', 'Retail Two', 1, 1.00, SYSTIMESTAMP);

MERGE INTO customer_type t
USING tmp_customer_type_us s
ON (t.id = s.id)
WHEN MATCHED THEN
    UPDATE SET
        t.code          = s.code,
        t.name          = s.missing_col,
        t.priority      = s.priority,
        t.discount_rate = s.discount_rate
WHEN NOT MATCHED THEN
    INSERT (id, code, name, priority, discount_rate, created_at)
    VALUES (s.id, s.code, s.name, s.priority, s.discount_rate, s.created_at);

-- rollback only deletes US-introduced codes; RETAIL/CORP/GOV belong to the GLOBAL dataset
--rollback DELETE FROM customer_type
--rollback WHERE code IN ('SME', 'ENT', 'RETAIL2');

--changeset banking-team:US-001-customer-types context:US
--comment: E10 - this changeset id duplicates the one above (Liquibase would reject the changelog)

INSERT INTO tmp_customer_type_us (id, code)
VALUES (7, 'X1');

INSERT INTO tmp_customer_type_us (id, code, name, created_at)
VALUES (8, 'X2', 'Bad Timestamp', '2024-13-01 10:30:00');

--rollback DELETE FROM no_such_table WHERE code = 'X1';
