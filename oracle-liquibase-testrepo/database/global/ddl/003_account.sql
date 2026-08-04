--liquibase formatted sql

--changeset banking-team:ddl-003-account labels:ddl
--comment: Account table (Oracle)

CREATE TABLE account (
    id               NUMBER(19)        NOT NULL,
    account_number   VARCHAR2(30 CHAR) NOT NULL,
    balance          NUMBER(10,2),
    customer_type_id NUMBER(10),
    created_at       DATE,
    CONSTRAINT pk_account PRIMARY KEY (id)
);

ALTER TABLE account
    ADD CONSTRAINT fk_account_customer_type
    FOREIGN KEY (customer_type_id) REFERENCES customer_type (id);

CREATE UNIQUE INDEX ux_account_number ON account (account_number);

--rollback DROP TABLE account;
