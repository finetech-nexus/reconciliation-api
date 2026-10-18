-- Comptes de caisse Fineract, une devise par compte nostro.
-- 10110 est la caisse BCT du produit épargne TND. 10120 est la caisse correspondant du produit EUR.
INSERT INTO gl_account_mapping (id, source_gl_code, bank_account_id, source_system_id, description) VALUES
    ('21000000-0000-0000-0000-000000000003', '10110', '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Caisse BCT TND'),
    ('21000000-0000-0000-0000-000000000004', '10120', '20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'Caisse correspondant EUR');
