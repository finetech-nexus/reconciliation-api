-- Adresse de l'instance Fineract déployée dans le namespace core-banking.
UPDATE core_profile
SET connector_type = 'FINERACT',
    base_url = 'https://fineract-server.core-banking.svc.cluster.local:8443',
    tenant = 'nexusbank',
    accounts_path = '/fineract-provider/api/v1/glaccounts',
    entries_path = '/fineract-provider/api/v1/journalentries',
    updated_at = CURRENT_TIMESTAMP
WHERE base_url = '' OR base_url IS NULL;
