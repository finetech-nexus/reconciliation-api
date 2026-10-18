-- Compte de la compensation monétique, pour que l'exemple SMT se charge.
INSERT INTO bank_account (id, account_code, name, currency, source_system_id, correspondent) VALUES
    ('20000000-0000-0000-0000-000000000201', 'SMT-TND-001', 'Compensation monétique TND', 'TND', '10000000-0000-0000-0000-000000000005', 'SMT');
