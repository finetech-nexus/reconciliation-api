# Sécurité et habilitations

Le contrôle est fait dans l'API à chaque commande. Masquer un bouton ne suffit pas.

| Rôle | Droits |
| --- | --- |
| `RECON_ADMIN` | Règles, imports, rapprochements, propositions. Ne peut pas approuver sa propre proposition. |
| `RECON_OPERATOR` | Imports et lancement de rapprochement. |
| `RECON_ANALYST` | Proposition de résolution et réouverture. |
| `RECON_APPROVER` | Validation ou refus d'une proposition faite par un autre acteur. |
| `RECON_AUDITOR` | Lecture, rapport et piste d'audit. |
| `RECON_SERVICE` | Imports et état du connecteur Fineract. |

L'auto-validation renvoie `403 AUTO_APPROVAL_FORBIDDEN` et une ligne `audit_event`. Un rôle non habilité renvoie `403 ACCESS_DENIED`, également journalisé.

En production, `X-Actor-Id` et `X-Actor-Roles` doivent être posés par la passerelle après authentification OIDC, pas par le navigateur. Le sélecteur de rôle de l'atelier sert au développement et à la recette de la séparation des tâches.

TLS est terminé sur Kong puis sur l'ingress. Les secrets PostgreSQL du manifeste de développement sont des valeurs de bac à sable, à remplacer par le coffre-fort de la DSI avant toute recette sur données réelles.

Les messages d'erreur HTTP ne renvoient pas l'exception SQL. Les journaux serveur portent la corrélation.
