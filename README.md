# Reconcilia Bank

API de rapprochement pour le lancement en Tunisie. Monolithe modulaire Spring Boot, PostgreSQL, atelier React dans `reconciliation-app`.

## Démarrage

```bash
docker compose -f docker-compose.reconciliation.yml up --build
```

- API : `http://localhost:8110` — OpenAPI `/swagger-ui.html`
- Atelier : `http://localhost:8111`
- PostgreSQL : `localhost:5437`, base `reconciliation`

Sans Docker, avec Java 21 et PostgreSQL déjà joignable sur `localhost:5437` :

```bash
mvn spring-boot:run
```

Le profil `local` démarre le même schéma sur H2 en mode PostgreSQL, uniquement pour l'atelier :

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Tests : `mvn test`.

Documentation d'implémentation :

- [Architecture](docs/architecture.md)
- [API](docs/api.md)
- [Modèle de données](docs/data-model.md)
- [Sécurité](docs/security.md)
- [Tests](docs/testing.md)
- [Déploiement](docs/deployment.md)

Déploiement : namespace `reconciliation`. Kong expose :

- `reconciliation-api.api.dev.sandbox.internal.nbank.fr`
- `reconciliation-app.dev.sandbox.internal.nbank.fr`

## Spécification technique

Version 1.0 · Banque en lancement en Tunisie · Apache Fineract · Cloud privé

L'objectif est de définir une architecture directement exploitable par une équipe de développement et d'infrastructure, pour une charge initiale de 100 000 écritures par jour, avec intégration par API et fichiers, rapprochement automatisé, gestion des suspens et auditabilité.

### 1. Architecture technique cible

Architecture en services applicatifs, avec un moteur de rapprochement séparé des connecteurs bancaires. Pour le lancement, un monolithe modulaire avec des workers asynchrones, plutôt qu'un grand nombre de microservices.

Utilisateurs et systèmes sources : portail web, Apache Fineract, grand livre, BCT, correspondants.

API Gateway et zone d'intégration : TLS, authentification, API REST, SFTP, validation des fichiers.

Chaîne : ingestion (mapping, déduplication, contrôles) → rapprochement (règles, candidats, décisions) → workflow (suspens, affectation, validation) → audit et reporting → PostgreSQL, stockage objet chiffré, file de messages, sauvegardes.

| Composant | Technologie cible | Fonction |
| --- | --- | --- |
| Backend | Java avec Spring Boot | API métier et règles de gestion |
| Frontend | React avec TypeScript | Interface de rapprochement et de supervision |
| Base de données | PostgreSQL | Données métier, rapprochements et workflow |
| Traitement asynchrone | RabbitMQ ou équivalent | Files d'ingestion et de rapprochement |
| Stockage documentaire | Stockage objet compatible S3 | Fichiers sources, justificatifs et exports |
| Identité | OIDC avec fournisseur d'identité d'entreprise | Authentification et habilitations |
| Déploiement | Conteneurs sur cloud privé | Isolation, redondance et déploiement maîtrisé |
| Observabilité | OpenTelemetry et stack de supervision approuvée | Logs, métriques, traces et alertes |

Ces technologies sont une proposition de référence. Elles doivent être alignées sur les standards de la DSI.

### 2. Interfaces API

Les endpoints Fineract exacts doivent être figés à partir de la version installée. Les identifiants `FIN-API-*` et `REC-API-*` sont des identifiants de spécification, pas des chemins HTTP.

| Interface | Opération | Usage |
| --- | --- | --- |
| `FIN-API-001` | GET Fineract | Référentiel des comptes et devises |
| `FIN-API-002` | GET Fineract | Transactions des comptes concernés |
| `FIN-API-003` | GET Fineract | Écritures et mouvements comptables |
| `FIN-API-004` | GET Fineract | Soldes et données de contrôle |
| `REC-API-001` | POST | Déclencher un import |
| `REC-API-002` | GET | Consulter l'état d'un lot |
| `REC-API-003` | GET | Consulter les suspens |
| `REC-API-004` | POST | Proposer une résolution |
| `REC-API-005` | POST | Valider une résolution, si autorisé |
| `REC-API-006` | GET | Consulter un rapprochement et ses preuves |

Préfixe `/api/v1` :

```
POST /api/v1/imports
GET  /api/v1/imports/{importId}
GET  /api/v1/reconciliations
GET  /api/v1/reconciliations/{reconciliationId}
GET  /api/v1/exceptions
GET  /api/v1/exceptions/{exceptionId}
POST /api/v1/exceptions/{exceptionId}/resolve
POST /api/v1/exceptions/{exceptionId}/approve
GET  /api/v1/reports/reconciliation
```

Contrats : JSON UTF-8, dates ISO-8601, horodatages techniques UTC, montants décimaux exacts, pagination, corrélation, validation stricte, erreurs sans donnée sensible, clé d'idempotence, OpenAPI versionnée.

Ouverture de lot :

```json
{
  "sourceSystem": "FINERACT",
  "sourceType": "ACCOUNTING_ENTRIES",
  "businessDate": "2026-10-16",
  "sourceBatchId": "FIN-GL-20261016-001",
  "currency": "TND",
  "expectedRecordCount": 100000,
  "metadata": { "schemaVersion": "1.0" }
}
```

Réponse, sans déclarer l'import terminé :

```json
{
  "importId": "imp-20261016-0001",
  "status": "RECEIVED",
  "acceptedRecords": 0,
  "rejectedRecords": 0,
  "correlationId": "corr-8f71c2"
}
```

### 3. Fichiers

Profils ajoutables sans modifier le moteur : CSV UTF-8, XLSX pour les imports manuels, JSON, XML ou formats bancaires lorsque le connecteur est validé, fichiers fixes seulement si la source l'impose.

Traitement : dépôt, contrôle d'émetteur, nom, format et taille, empreinte et détection des fichiers déjà traités, schéma, totaux, staging, modèle canonique, rapport, archivage. Le lot est atomique au sens métier : rejets, acceptés et quarantaine sont distingués. Un fichier partiel n'est pas déclaré intégralement conforme.

CSV canonique proposé, pas un format BCT présumé :

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
TX10001;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;DEBIT;REF-001
TX10002;BCT-TND-001;2026-10-16;2026-10-16;TND;12000.000;CREDIT;REF-002
```

### 4. Modèle physique

| Table | Fonction |
| --- | --- |
| `source_system` | Référentiel des systèmes sources |
| `bank_account` | Comptes bancaires et correspondants |
| `gl_account_mapping` | Mapping des comptes comptables |
| `import_batch` | Suivi des imports |
| `import_record` | Données importées normalisées |
| `accounting_entry` | Écritures comptables |
| `external_transaction` | Mouvements des relevés |
| `reconciliation_run` | Exécution d'un rapprochement |
| `reconciliation_match` | Groupe d'écritures rapprochées |
| `reconciliation_match_item` | Détail des éléments rapprochés |
| `reconciliation_exception` | Suspens et anomalies |
| `exception_action` | Actions de résolution |
| `approval` | Validations indépendantes |
| `audit_event` | Journal d'audit |
| `attachment` | Métadonnées des justificatifs |

Intégrité : identifiants uniques, clés d'idempotence, clés étrangères, `NUMERIC` et non `FLOAT`, devises et comptes rattachés au référentiel, historique des versions de règles, conservation des sources, transactions atomiques sur les statuts critiques, pas de suppression physique d'un rapprochement validé.

### 5. Moteur

1. Sélectionner les écritures éligibles selon le compte, la période et la devise.
2. Exclure les éléments déjà rapprochés, sauf réexécution contrôlée.
3. Générer des candidats à partir des références, comptes, montants et dates.
4. Appliquer les règles approuvées dans leur ordre de priorité.
5. Déterminer si la correspondance est unique, groupée ou ambiguë.
6. Créer les associations et les suspens.
7. Calculer les totaux de contrôle.
8. Enregistrer la version de règle, les données utilisées et la décision.
9. Publier vers le workflow et le reporting.

Performance : index sur comptes, références, dates et montants ; fenêtre temporelle ; lots ; reprise sans duplication ; extournes ; rejeu avec une nouvelle version de règles sans écraser l'historique ; mesure du temps par étape. Pas de comparaison exhaustive.

### 6. Dimensionnement de départ

| Composant | Configuration indicative |
| --- | --- |
| Backend | 3 instances, 4 vCPU, 8–16 Go RAM |
| PostgreSQL | 2 nœuds, 8 vCPU, 32 Go RAM |
| Workers | 2 à 4 instances, 4 vCPU, 8 Go RAM |
| Messages | Cluster redondant de 3 nœuds |
| Objet | 1 à 2 To à confirmer |
| Sauvegardes | Stockage distinct, chiffré |

Environnements : DEV, INT, UAT, PREPROD, PROD, DR. Pas de copie de données réelles sans autorisation et masquage.

### 7. Sécurité

| Rôle | Droits |
| --- | --- |
| `RECON_ADMIN` | Paramétrage technique et fonctionnel autorisé |
| `RECON_OPERATOR` | Import et traitement |
| `RECON_ANALYST` | Analyse et qualification des suspens |
| `RECON_APPROVER` | Validation indépendante |
| `RECON_AUDITOR` | Consultation des preuves et historiques |
| `RECON_SERVICE` | Intégration technique limitée |

Habilitations vérifiées côté serveur. Authentification centralisée et MFA pour les accès sensibles, secrets en coffre-fort, TLS, chiffrement des bases et sauvegardes, journalisation, protection contre l'injection et les fichiers malveillants, tests de vulnérabilité, revue des dépendances et des droits.

### 8. Recette

| ID | Scénario | Critère |
| --- | --- | --- |
| IT-01 | Import API Fineract | Enregistrements attendus récupérés |
| IT-02 | Pagination | Aucun enregistrement omis |
| IT-03 | Rejeu d'un lot | Aucun doublon métier |
| IT-04 | Fichier mal formé | Rejet documenté, aucun succès fictif |
| IT-05 | Référence absente | Traitement selon la qualité des données |
| IT-06 | Rupture de connexion | Reprise sans perte ni duplication |
| IT-07 | Écart sur les totaux | Lot bloqué ou en anomalie |
| IT-08 | Transaction tardive | Intégration et rapprochement contrôlés |
| MT-01 | Montant, compte, devise et référence identiques | Rapprochement automatique |
| MT-02 | Montant différent | Suspens |
| MT-03 | Plusieurs candidats | Validation manuelle |
| MT-04 | Rapprochement groupé | Somme exacte et détail conservé |
| MT-05 | Mouvement externe sans comptabilité | Suspens |
| MT-06 | Écriture sans mouvement externe | Suspens |
| MT-07 | Extourne | Lien avec l'opération d'origine |
| MT-08 | Utilisateur non habilité | Refus journalisé |
| MT-09 | Auto-validation | Action bloquée |
| MT-10 | Réouverture | Justification et historique conservés |

Mise en production seulement après tests critiques, absence de défaut bloquant, concordance des totaux, validation des règles par la direction financière, séparation des tâches, sécurité, continuité, rapports et procès-verbal.

### 9. Structure logicielle

`api`, `security`, `source-connectors` (fineract, accounting, bct, correspondent-banks), `ingestion`, `canonical-data`, `reconciliation-engine`, `exception-management`, `approval-workflow`, `audit`, `reporting`, `persistence`, `observability`, `deployment`, `tests`.

### 10. Décisions encore ouvertes

1. Version exacte de Fineract et son OpenAPI.
2. Système propriétaire de la comptabilité générale.
3. Formats et canaux BCT et correspondants.
4. Règles de rapprochement signées et convention de signe.
5. Disponibilité, RPO et RTO.
6. Conservation et localisation des données.

Références : [documentation Apache Fineract](https://fineract.apache.org/docs/current/), [référence historique](https://fineract.apache.org/docs/legacy/), [dépôt](https://github.com/apache/fineract).

Le prototype doit démontrer l'extraction, le mapping canonique et le rapprochement d'un lot représentatif avant de figer les ressources de production.
