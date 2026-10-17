# Modèle de données

PostgreSQL. Montants en `NUMERIC(20,6)`. Identifiants techniques UUID. Clés d'idempotence métier :

- `(source_system_id, source_batch_id)` sur `import_batch`
- `(source_system_id, source_entry_id)` sur `accounting_entry`
- `(source_system_id, external_transaction_id)` sur `external_transaction`
- `idempotency_key` pour les commandes HTTP

Tables : `source_system`, `bank_account`, `gl_account_mapping`, `file_profile`, `reconciliation_rule`, `import_batch`, `import_record`, `accounting_entry`, `external_transaction`, `reconciliation_run`, `reconciliation_match`, `reconciliation_match_item`, `reconciliation_exception`, `exception_action`, `approval`, `audit_event`, `attachment`, `actor_account_scope`, `work_item`, `id_sequence`.

Index de recherche : compte + devise + date + référence, compte + devise + montant, suspens `(status, due_at)` et `(account_id, detected_at)`.

Aucune API ne supprime un rapprochement. Une réexécution marque le run précédent `SUPERSEDED`.

Les justificatifs sont des métadonnées (`storage_key`, `checksum` sha256, taille, type). Les octets restent dans le stockage objet. Types acceptés : PDF, PNG, JPEG, texte, CSV. Taille maximale 20 Mo.

Le périmètre de comptes est la table `actor_account_scope`. Sans ligne pour un acteur, le référentiel de lancement est visible. Dès qu'une ligne existe, les autres comptes sont refusés.
