--liquibase formatted sql

--changeset banking-team:ddl-002-customer-type labels:ddl
--comment: Customer type reference table with sequence (Oracle)

CREATE TABLE customer_type (
    id            NUMBER(10)         NOT NULL,
    code          VARCHAR2(10 CHAR)  NOT NULL,
    name          VARCHAR2(100 CHAR) NOT NULL,
    priority      NUMBER(3),
    discount_rate NUMBER(5,2),
    created_at    TIMESTAMP,
    CONSTRAINT pk_customer_type PRIMARY KEY (id)
);

CREATE UNIQUE INDEX ux_customer_type_code ON customer_type (code);

CREATE SEQUENCE customer_type_seq START WITH 1 INCREMENT BY 1 NOCACHE;

--rollback DROP SEQUENCE customer_type_seq;
--rollback DROP TABLE customer_type;
