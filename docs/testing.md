# Tests et recette

`mvn test` exécute :

- le moteur sur correspondance exacte, groupée, ambiguë, écart de montant, orphelins, extourne, et non-mutation du résultat déjà calculé ;
- un scénario HTTP : import, rejeu sans doublon, rapprochement, refus d'un auditeur, refus d'auto-validation, approbation par un autre acteur ;
- un fichier sans en-tête canonique (`422`, lot `REJECTED`, zéro ligne acceptée) ;
- un écart de total de contrôle (`QUARANTINED`, rien promu).

Ces scénarios couvrent IT-03, IT-04, IT-07, MT-01 à MT-06, MT-08, MT-09 et la conservation d'historique de MT-10 côté réouverture d'API.

La charge de 100 000 écritures, le pic à cinq fois le débit, l'arrêt d'un worker et la restauration de sauvegarde restent des essais d'environnement. Ils ne sont pas simulés par les tests unitaires.

Critères de mise en production de la spec : ils restent conditionnés à la validation financière des règles, au gel de l'OpenAPI Fineract, et au procès-verbal de recette. Le prototype ne les déclare pas acquis.
