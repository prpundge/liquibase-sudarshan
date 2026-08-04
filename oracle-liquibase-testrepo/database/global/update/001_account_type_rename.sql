--liquibase formatted sql

-- Stage 4 of the release (global update): corrective DML that lives OUTSIDE
-- staticdatasetup. Files in this directory run after global and country static
-- data, in numeric-prefix order — exactly as Jenkins executes them.

--changeset banking-team:upd-001-savings-rename labels:update
--comment: Correct the display name of the SAVINGS account type

UPDATE account_type
   SET name = 'Savings Account'
 WHERE code = 'SAVINGS';

--rollback UPDATE account_type SET name = 'Savings' WHERE code = 'SAVINGS';
