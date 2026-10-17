INSERT INTO source_system (id, code, name, channel) VALUES
    ('10000000-0000-0000-0000-000000000001', 'FINERACT', 'Apache Fineract', 'API'),
    ('10000000-0000-0000-0000-000000000002', 'BCT', 'Banque Centrale de Tunisie', 'FILE'),
    ('10000000-0000-0000-0000-000000000003', 'CORRESPONDENT', 'Banques correspondantes', 'FILE');

INSERT INTO bank_account (id, account_code, name, currency, source_system_id, correspondent) VALUES
    ('20000000-0000-0000-0000-000000000001', 'BCT-TND-001', 'Nostro BCT TND', 'TND', '10000000-0000-0000-0000-000000000002', 'BCT'),
    ('20000000-0000-0000-0000-000000000002', 'CORR-EUR-014', 'Correspondant EUR 014', 'EUR', '10000000-0000-0000-0000-000000000003', 'Correspondant 014');

INSERT INTO gl_account_mapping (id, source_gl_code, bank_account_id, source_system_id, description) VALUES
    ('21000000-0000-0000-0000-000000000001', 'GL-BCT-TND', '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Compte général nostro BCT'),
    ('21000000-0000-0000-0000-000000000002', 'GL-CORR-EUR', '20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'Compte général correspondant EUR');

INSERT INTO file_profile (id, source_system_id, format, delimiter, decimal_separator, has_header) VALUES
    ('22000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'CSV', ';', '.', TRUE),
    ('22000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'CSV', ';', '.', TRUE),
    ('22000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', 'CSV', ';', '.', TRUE);

INSERT INTO reconciliation_rule (id, version, priority, date_window_days, allow_grouped, direction_mode, approved_at) VALUES
    ('30000000-0000-0000-0000-000000000001', '1.0', 1, 2, TRUE, 'OPPOSITE', CURRENT_TIMESTAMP);

INSERT INTO id_sequence (name, last_value) VALUES
    ('import_batch', 0),
    ('reconciliation_run', 0),
    ('reconciliation_exception', 0);
