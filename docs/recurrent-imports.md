# Imports récurrents : core banking, BCT et correspondants

Un relevé n'écrase pas le précédent. L'historique est la suite des lots (`import_batch`). Le curseur (`statement_cursor`) n'est qu'un repère de reprise, visible dans Réglages : dernière date chargée, numéro de séquence, lot, et un trou éventuel.

C'est le fonctionnement des outils de rapprochement nostro (relevé numéroté, solde d'ouverture égal au solde de clôture précédent, rejeu idempotent). Le numéro de relevé MT940 (champ 28C), le numéro de séquence camt.053, ou le nom du fichier de règlement BCT jouent le même rôle que `sourceBatchId`.

## Ce qui est conservé

| Événement | Effet |
| --- | --- |
| Même système et même `sourceBatchId` | Rejeu. Aucune seconde écriture. |
| Même date, nouvel identifiant de lot | Avenant. La séquence du compte augmente de 1. Les deux lots restent. |
| Date postérieure d'un jour | Le repère avance. Séquence remise à 1. |
| Saut de plus d'un jour | Le lot est accepté et le repère signale un trou. Le relevé manquant reste à recevoir. |
| Date antérieure au repère | Relevé tardif. Il est conservé. Le repère ne recule pas. |

Un contenu différent pour le même identifiant n'est pas réécrit en silence. Il faut un nouvel identifiant : c'est l'avenant, pas une correction cachée.

La purge n'est pas le cycle quotidien. Elle efface les mouvements, les lots, les rapprochements et les repères. Les comptes, les correspondants, le choix du core et la piste d'audit restent. En production, la durée de conservation (souvent dix ans pour les pièces bancaires) est une politique d'archivage, pas ce bouton.

## Core banking

Le système source des écritures est `CORE_BANKING`. Fineract et T24 sont le connecteur choisi dans Réglages. Par défaut, Fineract pointe vers l'instance du namespace `core-banking` : `https://fineract-server.core-banking.svc.cluster.local:8443`, tenant `nexusbank`, journal `/fineract-provider/api/v1/journalentries`. Aucun chemin d'une autre version n'est codé en dur.

Deux façons d'importer, le même pipeline ensuite :

1. Fichier canonique CSV ou JSON, déposé dans Imports.
2. Appel automatique : `POST /api/v1/imports/core-banking/pull` avec la date métier et la devise. L'identifiant de lot est `CORE-{FINERACT|T24}-{date}`. Le même jour se rejoue.

Fineract renvoie un journal `pageItems` (identifiant, compte général, date, montant, sens, devise). Il est traduit vers le fichier canonique. Le code du compte général suit le mapping vers le compte de rapprochement (`GL-BCT-TND` vers `BCT-TND-001`). T24 doit exposer soit ce journal, soit directement le JSON canonique, à l'adresse saisie. Tant que l'adresse est vide, l'appel est refusé : on ne fabrique pas d'écritures.

## BCT

La Banque centrale ne se tire pas par l'appel du core. Le canal est le fichier : relevé de compte courant ou règlement SGMT, un lot par compte et par jour ouvré. Identifiant conseillé : `BCT-{compte}-{date}`, par exemple `BCT-TND-001-2026-10-16`.

L'historique est la liste des lots BCT. Le curseur du compte `BCT-TND-001` dit jusqu'où la journée est chargée. Un fichier du 18 sans fichier du 17 laisse le trou visible. Le rapprochement continue sur ce qui est chargé ; le jour manquant reste à déposer.

## Canaux tunisiens

Le système d'import n'est pas « correspondant ». En Tunisie, un mouvement externe vient d'un rail :

| Système | Rôle |
| --- | --- |
| BCT | Relevé du compte courant et règlement SGMT. Le net de la compensation s'y inscrit. |
| SIBTEL | Compensation des virements et prélèvements en dinars. Les banques tunisiennes (BNA, STB, BIAT, …) sont des participants de cette compensation. |
| SMT | Compensation monétique. Le règlement net revient sur le compte BCT. |
| SWIFT | Relevé du correspondant étranger. Aujourd'hui le nostro EUR est NexusBankEu (`CORR-EUR-014`). Un virement TND entre deux BIC tunisiens est refusé sur ce canal. |

Chaque rail se charge par fichier, un lot par compte et par jour. Le livre européen et le livre BCT (`BCT-TND-001`) restent deux comptes. Un relevé EUR ne se charge pas sur le compte dinar.
