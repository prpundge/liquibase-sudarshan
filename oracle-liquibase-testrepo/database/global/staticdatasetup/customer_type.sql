--liquibase formatted sql

--changeset banking-team:global-002a-drop-tmp context:GLOBAL runAlways:true failOnError:false
--comment: GTT definitions persist; drop before EVERY run (runAlways) so the re-create below never hits ORA-00955
DROP TABLE tmp_customer_type;
--rollback SELECT 1 FROM dual;

--changeset banking-team:global-002-customer-types context:GLOBAL labels:staticdata runOnChange:true
--comment: Upsert customer types using a sequence for new ids (VALID example)

CREATE GLOBAL TEMPORARY TABLE tmp_customer_type (
    code          VARCHAR2(10 CHAR)  NOT NULL,
    name          VARCHAR2(100 CHAR) NOT NULL,
    priority      NUMBER(3),
    discount_rate NUMBER(5,2)
) ON COMMIT DELETE ROWS;

INSERT INTO tmp_customer_type (code, name, priority, discount_rate)
VALUES ('RETAIL', 'Retail Customer', 100, 0.00);

INSERT INTO tmp_customer_type (code, name, priority, discount_rate)
VALUES ('CORP', 'Corporate Customer', 200, 12.50);

INSERT INTO tmp_customer_type (code, name, priority, discount_rate)
VALUES ('GOV', 'Government Customer', 300, 7.75);

MERGE INTO customer_type t
USING tmp_customer_type s
ON (t.code = s.code)
WHEN MATCHED THEN
    UPDATE SET
        t.name          = s.name,
        t.priority      = s.priority,
        t.discount_rate = s.discount_rate
WHEN NOT MATCHED THEN
    INSERT (id, code, name, priority, discount_rate, created_at)
    VALUES (customer_type_seq.NEXTVAL, s.code, s.name, s.priority, s.discount_rate, SYSTIMESTAMP);

--rollback DELETE FROM customer_type WHERE code IN ('RETAIL', 'CORP', 'GOV');
