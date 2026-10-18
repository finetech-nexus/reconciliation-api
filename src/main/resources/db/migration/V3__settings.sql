-- Le grand livre s'appelle Core banking. Fineract et T24 sont des connecteurs, pas le système source.
UPDATE source_system
SET code = 'CORE_BANKING', name = 'Core banking'
WHERE code = 'FINERACT';

CREATE TABLE core_profile (
    id UUID PRIMARY KEY,
    connector_type VARCHAR(20) NOT NULL,
    base_url VARCHAR(300) NOT NULL DEFAULT '',
    tenant VARCHAR(80) NOT NULL DEFAULT 'default',
    accounts_path VARCHAR(200) NOT NULL DEFAULT '',
    entries_path VARCHAR(200) NOT NULL DEFAULT '',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (connector_type IN ('FINERACT', 'T24'))
);

INSERT INTO core_profile (id, connector_type) VALUES
    ('40000000-0000-0000-0000-000000000001', 'FINERACT');

CREATE TABLE correspondent (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    bic VARCHAR(11),
    currency CHAR(3) NOT NULL,
    account_code VARCHAR(64) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO correspondent (id, code, name, bic, currency, account_code) VALUES
    ('41000000-0000-0000-0000-000000000001', 'CORR-014', 'Correspondant EUR 014', 'BNPAFRPP', 'EUR', 'CORR-EUR-014');

-- Repère de reprise par compte. L'historique des lots reste dans import_batch.
CREATE TABLE statement_cursor (
    source_system_id UUID NOT NULL REFERENCES source_system (id),
    account_code VARCHAR(64) NOT NULL,
    last_business_date DATE,
    last_sequence INT NOT NULL DEFAULT 0,
    last_batch_public_id VARCHAR(40),
    gap_detected BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_system_id, account_code)
);
