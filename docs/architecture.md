# Architecture

Reconcilia est un monolithe modulaire. Les connecteurs sources ne contiennent pas les règles de rapprochement.

```
Utilisateurs / Fineract / Grand livre / BCT / Correspondants
        │
        ▼
API /api/v1  ·  corrélation, idempotence, rôles
        │
        ├── ingestion          profils CSV et JSON, staging, contrôles
        ├── canonical          accounting_entry, external_transaction
        ├── engine             candidats par référence, pas de produit cartésien
        ├── workflow           suspens, proposition, validation, réouverture
        ├── audit              audit_event
        └── reporting          totaux et taux de rapprochement automatique
        │
        ▼
PostgreSQL
```

| Module du code | Rôle spec |
| --- | --- |
| `api` | REC-API-001 à REC-API-006 et routes `/api/v1` |
| `sourceconnectors` | FIN-API-001 à FIN-API-004, chemins fournis par configuration |
| `ingestion` | Fichiers, dédoublonnage, équilibre |
| `engine` | Algorithme de rapprochement |
| `workflow` | Suspens et séparation des tâches |
| `audit` | Piste d'audit |
| `reporting` | Indicateurs |
| `persistence` | Schéma PostgreSQL |
| `observability` | Corrélation, OpenAPI, actuator, Prometheus |

La file de travaux est la table `work_item`. Elle matérialise la frontière asynchrone du lancement. Le moteur peut être appelé par un worker RabbitMQ plus tard sans changer les règles.

Le système source du grand livre est `CORE_BANKING`. Fineract ou T24 se choisit dans Réglages. Le connecteur reste inactif tant que l'adresse et le chemin des écritures ne sont pas saisis, ou tant que `FINERACT_BASE_URL` et `FINERACT_ENTRIES_PATH` ne sont pas posés pour l'instance. Aucun chemin d'une autre version n'est codé en dur. Les relevés BCT et correspondants gardent leur historique de lots : voir [recurrent-imports.md](recurrent-imports.md).

## Montants et temps

- Montants `NUMERIC(20,6)`, transmis en JSON décimal exact.
- Dates métier `YYYY-MM-DD`. Horodatages techniques en UTC, ISO-8601.
- La convention de signe n'est pas encore figée par la direction financière. La version de règle `1.0` rapproche sur la valeur absolue, le compte, la devise, la référence et la fenêtre de dates. Le sens débit/crédit est conservé sur l'écriture. `direction_mode` (`OPPOSITE` ou `SAME`) est versionné pour la décision restante.

## Réexécution

Une nouvelle exécution ne supprime pas les correspondances précédentes. Avec `replay=true`, l'exécution précédente du même compte et de la même période passe au statut `SUPERSEDED`. Les lignes de `reconciliation_match` restent en place.
