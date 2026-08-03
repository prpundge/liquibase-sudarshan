--liquibase formatted sql

--changeset banking-team:ddl-001-account-type labels:ddl
--comment: Account type reference table (Oracle)

CREATE TABLE account_type (
    code        VARCHAR2(15 CHAR)  NOT NULL,
    name        VARCHAR2(100 CHAR) NOT NULL,
    description VARCHAR2(255 CHAR),
    active_flag CHAR(1)            NOT NULL,
    sort_order  NUMBER(5),
    created_at  DATE,
    CONSTRAINT pk_account_type PRIMARY KEY (code),
    CONSTRAINT ck_account_type_active CHECK (active_flag IN ('Y', 'N'))
);

--rollback DROP TABLE account_type;
