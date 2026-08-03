CREATE TABLE account_type (
    code        VARCHAR(15)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    active      BOOLEAN      NOT NULL,
    CONSTRAINT pk_account_type PRIMARY KEY (code)
);
