-- Les banques tunisiennes sont des participants de la compensation, pas des correspondants.
-- SWIFT reste le canal du nostro étranger (NexusBankEu, EUR).
INSERT INTO source_system (id, code, name, channel) VALUES
    ('10000000-0000-0000-0000-000000000004', 'SIBTEL', 'SIBTEL', 'FILE'),
    ('10000000-0000-0000-0000-000000000005', 'SMT', 'Monétique Tunisie', 'FILE'),
    ('10000000-0000-0000-0000-000000000006', 'SWIFT', 'SWIFT', 'FILE');

INSERT INTO file_profile (id, source_system_id, format, delimiter, decimal_separator, has_header) VALUES
    ('22000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000004', 'CSV', ';', '.', TRUE),
    ('22000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000005', 'CSV', ';', '.', TRUE),
    ('22000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000006', 'CSV', ';', '.', TRUE);

UPDATE bank_account
SET source_system_id = '10000000-0000-0000-0000-000000000004'
WHERE account_code LIKE 'CORR-TND-%';

UPDATE bank_account
SET source_system_id = '10000000-0000-0000-0000-000000000006'
WHERE account_code = 'CORR-EUR-014';
