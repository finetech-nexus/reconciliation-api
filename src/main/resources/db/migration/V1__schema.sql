-- Reconcilia Bank — schéma opérationnel.
-- Les montants sont en NUMERIC. Aucune suppression physique des rapprochements validés
-- n'est exposée par l'application : l'historique reste en place, une réexécution
-- marque l'exécution précédente SUPERSEDED.

CREATE TABLE source_system (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    channel VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE bank_account (
    id UUID PRIMARY KEY,
    account_code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    currency CHAR(3) NOT NULL,
    source_system_id UUID NOT NULL REFERENCES source_system (id),
    correspondent VARCHAR(200),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE gl_account_mapping (
    id UUID PRIMARY KEY,
    source_gl_code VARCHAR(64) NOT NULL,
    bank_account_id UUID NOT NULL REFERENCES bank_account (id),
    source_system_id UUID NOT NULL REFERENCES source_system (id),
    description VARCHAR(300),
    UNIQUE (source_system_id, source_gl_code)
);

CREATE TABLE file_profile (
    id UUID PRIMARY KEY,
    source_system_id UUID NOT NULL REFERENCES source_system (id),
    format VARCHAR(20) NOT NULL,
    delimiter CHAR(1) NOT NULL DEFAULT ';',
    decimal_separator CHAR(1) NOT NULL DEFAULT '.',
    has_header BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (source_system_id, format)
);

CREATE TABLE reconciliation_rule (
    id UUID PRIMARY KEY,
    version VARCHAR(40) NOT NULL UNIQUE,
    priority INT NOT NULL,
    date_window_days INT NOT NULL,
    allow_grouped BOOLEAN NOT NULL,
    direction_mode VARCHAR(20) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (direction_mode IN ('OPPOSITE', 'SAME')),
    CHECK (date_window_days >= 0)
);

CREATE TABLE import_batch (
    id UUID PRIMARY KEY,
    public_id VARCHAR(40) NOT NULL UNIQUE,
    source_system_id UUID NOT NULL REFERENCES source_system (id),
    source_type VARCHAR(40) NOT NULL,
    business_date DATE NOT NULL,
    source_batch_id VARCHAR(80) NOT NULL,
    currency CHAR(3) NOT NULL,
    expected_record_count INT,
    status VARCHAR(30) NOT NULL,
    accepted_records INT NOT NULL DEFAULT 0,
    rejected_records INT NOT NULL DEFAULT 0,
    quarantined_records INT NOT NULL DEFAULT 0,
    accepted_amount NUMERIC(20, 6) NOT NULL DEFAULT 0,
    control_total NUMERIC(20, 6),
    checksum VARCHAR(80),
    correlation_id VARCHAR(80) NOT NULL,
    metadata TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_system_id, source_batch_id),
    CHECK (source_type IN ('ACCOUNTING_ENTRIES', 'EXTERNAL_TRANSACTIONS')),
    CHECK (status IN ('RECEIVED', 'STAGING', 'LOADED', 'PARTIAL', 'REJECTED', 'QUARANTINED'))
);

CREATE INDEX idx_import_checksum ON import_batch (checksum);

CREATE TABLE import_record (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES import_batch (id),
    source_record_id VARCHAR(80) NOT NULL,
    account_code VARCHAR(64),
    booking_date DATE,
    value_date DATE,
    currency CHAR(3),
    amount NUMERIC(20, 6),
    direction VARCHAR(10),
    reference VARCHAR(120),
    reversal_of VARCHAR(120),
    status VARCHAR(30) NOT NULL,
    reject_reason VARCHAR(300),
    UNIQUE (batch_id, source_record_id),
    CHECK (status IN ('STAGED', 'ACCEPTED', 'REJECTED', 'QUARANTINED'))
);

CREATE TABLE accounting_entry (
    id UUID PRIMARY KEY,
    source_system_id UUID NOT NULL REFERENCES source_system (id),
    source_entry_id VARCHAR(80) NOT NULL,
    batch_id UUID NOT NULL REFERENCES import_batch (id),
    account_id UUID NOT NULL REFERENCES bank_account (id),
    booking_date DATE NOT NULL,
    value_date DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    amount NUMERIC(20, 6) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    reference VARCHAR(120) NOT NULL,
    reversal_of VARCHAR(120),
    open_for_match BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_system_id, source_entry_id),
    CHECK (direction IN ('DEBIT', 'CREDIT')),
    CHECK (amount >= 0)
);

CREATE TABLE external_transaction (
    id UUID PRIMARY KEY,
    source_system_id UUID NOT NULL REFERENCES source_system (id),
    external_transaction_id VARCHAR(80) NOT NULL,
    batch_id UUID NOT NULL REFERENCES import_batch (id),
    account_id UUID NOT NULL REFERENCES bank_account (id),
    booking_date DATE NOT NULL,
    value_date DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    amount NUMERIC(20, 6) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    reference VARCHAR(120) NOT NULL,
    reversal_of VARCHAR(120),
    open_for_match BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_system_id, external_transaction_id),
    CHECK (direction IN ('DEBIT', 'CREDIT')),
    CHECK (amount >= 0)
);

CREATE TABLE reconciliation_run (
    id UUID PRIMARY KEY,
    public_id VARCHAR(40) NOT NULL UNIQUE,
    account_id UUID NOT NULL REFERENCES bank_account (id),
    currency CHAR(3) NOT NULL,
    period_from DATE NOT NULL,
    period_to DATE NOT NULL,
    rule_id UUID NOT NULL REFERENCES reconciliation_rule (id),
    rule_version VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    replay BOOLEAN NOT NULL DEFAULT FALSE,
    supersedes_run_id UUID,
    external_total NUMERIC(20, 6) NOT NULL,
    accounting_total NUMERIC(20, 6) NOT NULL,
    matched_total NUMERIC(20, 6) NOT NULL,
    difference NUMERIC(20, 6) NOT NULL,
    matched_count INT NOT NULL,
    exception_count INT NOT NULL,
    timings TEXT,
    correlation_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (status IN ('COMPLETED', 'ANOMALY', 'SUPERSEDED'))
);

CREATE TABLE reconciliation_match (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES reconciliation_run (id),
    match_type VARCHAR(30) NOT NULL,
    amount NUMERIC(20, 6) NOT NULL,
    currency CHAR(3) NOT NULL,
    reference VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (match_type IN ('EXACT', 'GROUPED', 'REVERSAL'))
);

CREATE TABLE reconciliation_match_item (
    match_id UUID NOT NULL REFERENCES reconciliation_match (id),
    record_id UUID NOT NULL,
    record_side VARCHAR(20) NOT NULL,
    amount NUMERIC(20, 6) NOT NULL,
    source_record_id VARCHAR(80) NOT NULL,
    PRIMARY KEY (match_id, record_id),
    CHECK (record_side IN ('ACCOUNTING', 'EXTERNAL'))
);

CREATE TABLE reconciliation_exception (
    id UUID PRIMARY KEY,
    public_id VARCHAR(40) NOT NULL UNIQUE,
    run_id UUID REFERENCES reconciliation_run (id),
    account_id UUID NOT NULL REFERENCES bank_account (id),
    external_transaction_id UUID,
    accounting_entry_id UUID,
    exception_type VARCHAR(50) NOT NULL,
    amount NUMERIC(20, 6) NOT NULL,
    currency CHAR(3) NOT NULL,
    reference VARCHAR(120),
    status VARCHAR(30) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    owner_id UUID,
    detail VARCHAR(500),
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE,
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (status IN ('OPEN', 'PENDING_APPROVAL', 'RESOLVED', 'REOPENED')),
    CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH'))
);

CREATE INDEX idx_exception_status_due ON reconciliation_exception (status, due_at);
CREATE INDEX idx_exception_account_date ON reconciliation_exception (account_id, detected_at);
CREATE INDEX idx_accounting_match ON accounting_entry (account_id, currency, booking_date, reference);
CREATE INDEX idx_accounting_amount ON accounting_entry (account_id, currency, amount);
CREATE INDEX idx_external_match ON external_transaction (account_id, currency, booking_date, reference);
CREATE INDEX idx_external_amount ON external_transaction (account_id, currency, amount);
CREATE INDEX idx_import_status ON import_batch (status, created_at);
CREATE INDEX idx_run_account_period ON reconciliation_run (account_id, period_from, period_to, status);

CREATE TABLE exception_action (
    id UUID PRIMARY KEY,
    exception_id UUID NOT NULL REFERENCES reconciliation_exception (id),
    actor_id UUID,
    action_type VARCHAR(40) NOT NULL,
    comment VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE approval (
    id UUID PRIMARY KEY,
    action_id UUID NOT NULL REFERENCES exception_action (id),
    approver_id UUID NOT NULL,
    decision VARCHAR(20) NOT NULL,
    comment VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (decision IN ('APPROVE', 'REJECT'))
);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    event_time TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_id UUID,
    action VARCHAR(60) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(80),
    correlation_id VARCHAR(80) NOT NULL,
    detail VARCHAR(500)
);

CREATE INDEX idx_audit_time ON audit_event (event_time, actor_id);

CREATE TABLE attachment (
    id UUID PRIMARY KEY,
    exception_id UUID NOT NULL REFERENCES reconciliation_exception (id),
    filename VARCHAR(200) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    storage_key VARCHAR(300) NOT NULL UNIQUE,
    checksum VARCHAR(80) NOT NULL,
    byte_size BIGINT NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (byte_size > 0)
);

CREATE TABLE actor_account_scope (
    actor_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES bank_account (id),
    PRIMARY KEY (actor_id, account_id)
);

CREATE TABLE idempotency_key (
    idem_key VARCHAR(120) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    http_status INT NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE work_item (
    id UUID PRIMARY KEY,
    queue_name VARCHAR(40) NOT NULL,
    payload VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE,
    CHECK (status IN ('PENDING', 'DONE', 'FAILED'))
);

CREATE TABLE id_sequence (
    name VARCHAR(40) PRIMARY KEY,
    last_value INT NOT NULL
);
