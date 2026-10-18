# API `/api/v1`

Préfixe : `/api/v1`. JSON UTF-8. OpenAPI : `/v3/api-docs` et `/swagger-ui.html`.

En-têtes :

| En-tête | Usage |
| --- | --- |
| `X-Correlation-Id` | Identifiant de corrélation. Généré s'il est absent, renvoyé sur la réponse. |
| `X-Actor-Id` | UUID de l'acteur. |
| `X-Actor-Roles` | `RECON_ADMIN`, `RECON_OPERATOR`, `RECON_ANALYST`, `RECON_APPROVER`, `RECON_AUDITOR`, `RECON_SERVICE`. |
| `Idempotency-Key` | Obligatoire sur les commandes POST. |

Les collections sont paginées : `page`, `size` (max 100), `content`, `totalElements`, `totalPages`.

Une erreur a la forme `{ "code", "message", "correlationId", "timestamp" }` et ne contient pas de donnée technique interne.

## Catalogue

| Spec | Méthode | Chemin | Rôles d'écriture |
| --- | --- | --- | --- |
| REC-API-001 | POST | `/api/v1/imports` | OPERATOR, ADMIN, SERVICE |
| | POST | `/api/v1/imports/{importId}/files` | idem |
| | POST | `/api/v1/imports/{importId}/commit` | idem |
| REC-API-002 | GET | `/api/v1/imports/{importId}` | lecture |
| REC-API-006 | GET | `/api/v1/reconciliations/{reconciliationId}` | lecture |
| | POST | `/api/v1/reconciliations` | OPERATOR, ADMIN |
| REC-API-003 | GET | `/api/v1/exceptions` et `/{exceptionId}` | lecture |
| REC-API-004 | POST | `/api/v1/exceptions/{exceptionId}/resolve` | ANALYST, ADMIN |
| REC-API-005 | POST | `/api/v1/exceptions/{exceptionId}/approve` | APPROVER, ADMIN, autre acteur |
| | GET | `/api/v1/reports/reconciliation` | lecture |
| FIN-API-* | GET | `/api/v1/connectors/fineract` | état du connecteur |

`POST /imports` répond `RECEIVED` et ne déclare pas le lot terminé. Le fichier est ensuite déposé, puis `commit` produit `LOADED`, `PARTIAL`, `REJECTED` ou `QUARANTINED`.

Un fichier mal formé répond `422` avec `status=REJECTED` et `acceptedRecords=0`.

Un écart de `controlTotal` place le lot en `QUARANTINED` et ne promeut aucune ligne.

Le même `sourceBatchId` pour un système source renvoie le lot existant, sans seconde écriture canonique.

Les fichiers d'exemple de chaque système sont dans [sources.md](sources.md). Le cycle récurrent est dans [recurrent-imports.md](recurrent-imports.md).

## CSV canonique

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
```

Séparateur `;`, décimal `.`, sens `DEBIT` ou `CREDIT`. Ce format est un profil, pas un format BCT. XLSX, XML et largeur fixe sont refusés tant que le connecteur n'est pas validé : le moteur n'est pas modifié pour les accepter.

## Exemple d'ouverture de lot

```json
{
  "sourceSystem": "CORE_BANKING",
  "sourceType": "ACCOUNTING_ENTRIES",
  "businessDate": "2026-10-16",
  "sourceBatchId": "FIN-GL-20261016-001",
  "currency": "TND",
  "expectedRecordCount": 100000,
  "metadata": { "schemaVersion": "1.0" }
}
```

```json
{
  "importId": "imp-20261016-0001",
  "status": "RECEIVED",
  "acceptedRecords": 0,
  "rejectedRecords": 0,
  "correlationId": "corr-8f71c2"
}
```
