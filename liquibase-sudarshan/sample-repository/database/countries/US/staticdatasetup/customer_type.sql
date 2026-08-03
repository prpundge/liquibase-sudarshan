--liquibase formatted sql

--changeset banking-team:US-001-customer-types context:US labels:staticdata runOnChange:true
--comment: INTENTIONALLY BROKEN example — datatype violations across the board

CREATE TEMP TABLE tmp_customer_type (
    id          INTEGER      NOT NULL,
    code        VARCHAR(10)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    priority    SMALLINT,
    external_id UUID
) ON COMMIT DROP;

INSERT INTO tmp_customer_type (id, code, name, priority, external_id)
VALUES
    -- ERROR: priority 999999 exceeds SMALLINT range
    (1, 'RETAIL', 'Retail Customer', 999999, '0e37df36-f698-11e6-8dd4-cb9ced3df976'),
    -- ERROR: '12345' is not a valid UUID
    (2, 'CORP', 'Corporate Customer', 1, '12345'),
    -- ERROR: id value is not a valid INTEGER (overflow)
    (3000000000, 'GOV', 'Government Customer', 2, NULL);

-- ERROR: 'not_a_column' does not exist in the staging table
INSERT INTO tmp_customer_type (id, code, name, not_a_column)
VALUES (4, 'SME', 'Small Business', 'oops');

MERGE INTO customer_type AS target
USING tmp_customer_type AS source
ON target.id = source.id
WHEN MATCHED THEN
    UPDATE SET
        code        = source.code,
        name        = source.name,
        priority    = source.priority,
        external_id = source.external_id
WHEN NOT MATCHED THEN
    INSERT (id, code, name, priority, external_id)
    VALUES (source.id, source.code, source.name, source.priority, source.external_id);

--rollback DELETE FROM customer_type WHERE code IN ('RETAIL', 'CORP', 'GOV', 'SME');
