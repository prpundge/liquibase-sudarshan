CREATE TABLE customer_type (
    id          INTEGER      NOT NULL,
    code        VARCHAR(10)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    priority    SMALLINT,
    external_id UUID,
    CONSTRAINT pk_customer_type PRIMARY KEY (id)
);

CREATE UNIQUE INDEX ux_customer_type_code ON customer_type (code);
