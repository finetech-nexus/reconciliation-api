# Déploiement

Namespace Kubernetes : `reconciliation`.

| Élément | Nom |
| --- | --- |
| PostgreSQL | `reconciliation-postgresql` |
| API | `reconciliation-api:8110` |
| Atelier | `reconciliation-app:8111` |
| Flux | `clusters/dev/reconciliation.yaml` |
| Kong API | `reconciliation-api-route` |
| Kong atelier | `reconciliation-app-route` |

Hôtes, via Kong puis ingress-nginx :

- `reconciliation-api.api.dev.sandbox.internal.nbank.fr`
- `reconciliation-app.dev.sandbox.internal.nbank.fr`

Images : `nexusbank/reconciliation-api` et `nexusbank/reconciliation-app`.

Santé : `/actuator/health` pour l'API, `/health` pour l'atelier. Prometheus : `/actuator/prometheus`.

Local :

```bash
docker compose -f docker-compose.reconciliation.yml up --build
```

API `http://localhost:8110`, atelier `http://localhost:8111`, PostgreSQL `localhost:5437`.

Le manifeste de développement demande 10 Go pour PostgreSQL et une réplique. Le dimensionnement de production (3 API, PostgreSQL 8 vCPU / 32 Go, workers dédiés) reste une cible à mesurer, pas la taille du bac à sable.
