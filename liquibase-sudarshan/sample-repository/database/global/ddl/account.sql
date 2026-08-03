CREATE TABLE account (
    id               BIGINT        NOT NULL,
    account_number   VARCHAR(30)   NOT NULL,
    balance          DECIMAL(10,2),
    customer_type_id INTEGER,
    created_at       TIMESTAMP,
    CONSTRAINT pk_account PRIMARY KEY (id)
);

ALTER TABLE account
    ADD CONSTRAINT fk_account_customer_type
    FOREIGN KEY (customer_type_id) REFERENCES customer_type (id);

CREATE UNIQUE INDEX ux_account_number ON account (account_number);
