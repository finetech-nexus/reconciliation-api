# Exemples par système

Le CSV canonique et le JSON portent les mêmes champs. Les montants sont des chaînes décimales. Core banking charge des écritures (`ACCOUNTING_ENTRIES`). BCT, SIBTEL, SMT et SWIFT chargent le relevé externe (`EXTERNAL_TRANSACTIONS`).

En-tête CSV :

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
```

La colonne optionnelle `reversal_of` sert à l'extourne. Un mouvement dont la référence commence par `REV-` est aussi traité comme une extourne.

Dans l'application, chaque exemple se télécharge depuis Documentation.

## Core banking

Écritures du grand livre, compte `BCT-TND-001`. Le crédit de 25 000 sur `REF-001` se rapproche du débit BCT de même montant.

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
GL-001;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;CREDIT;REF-001
GL-002;BCT-TND-001;2026-10-16;2026-10-16;TND;11999.000;DEBIT;REF-002
GL-003;BCT-TND-001;2026-10-16;2026-10-16;TND;3000.000;CREDIT;REF-005
GL-004;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;CREDIT;REF-005
GL-005;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;DEBIT;REF-AMB
GL-006;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;DEBIT;REF-AMB
GL-007;BCT-TND-001;2026-10-16;2026-10-16;TND;1500.000;CREDIT;REF-ORPHAN
```

```json
{
  "records": [
    {
      "source_id": "GL-001",
      "account_id": "BCT-TND-001",
      "booking_date": "2026-10-16",
      "value_date": "2026-10-16",
      "currency": "TND",
      "amount": "25000.000",
      "direction": "CREDIT",
      "reference": "REF-001"
    }
  ]
}
```

## BCT

Relevé du compte courant `BCT-TND-001`. Le net SIBTEL et SMT s'y règle.

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
TX-001;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;DEBIT;REF-001
TX-002;BCT-TND-001;2026-10-16;2026-10-16;TND;12000.000;CREDIT;REF-002
TX-003;BCT-TND-001;2026-10-16;2026-10-16;TND;8000.000;DEBIT;REF-005
TX-004;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;DEBIT;REF-AMB
TX-005;BCT-TND-001;2026-10-16;2026-10-16;TND;700.000;DEBIT;REF-NONE
```

## SIBTEL

Compensation des virements et prélèvements en dinars. Le participant est BIAT, compte `CORR-TND-08`.

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
SIB-001;CORR-TND-08;2026-10-16;2026-10-16;TND;1500.000;DEBIT;VIR-BIAT-001
SIB-002;CORR-TND-08;2026-10-16;2026-10-16;TND;320.500;CREDIT;PRE-BIAT-014
```

## SMT

Compensation monétique, compte `SMT-TND-001`. Le net 820.000 revient sur le relevé BCT.

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
SMT-001;SMT-TND-001;2026-10-16;2026-10-16;TND;860.000;CREDIT;CAP-4412
SMT-002;SMT-TND-001;2026-10-16;2026-10-16;TND;40.000;DEBIT;REFUND-4418
```

## SWIFT

Relevé du nostro EUR NexusBankEu, compte `CORR-EUR-014`. Un virement TND entre deux banques tunisiennes ne se charge pas sur ce système.

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
SW-001;CORR-EUR-014;2026-10-16;2026-10-16;EUR;10000.000;CREDIT;FT261016NEXUS
SW-002;CORR-EUR-014;2026-10-16;2026-10-16;EUR;25.000;DEBIT;CHG261016
```

## Extourne

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
GL-009;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;DEBIT;REF-009
```

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference;reversal_of
TX-009;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;CREDIT;REV-REF-009;REF-009
```

## Résultat du jeu Core banking et BCT

| Type | Source |
| --- | --- |
| EXACT | REF-001, 25000.000 des deux côtés |
| GROUPED | REF-005, 3000.000 + 5000.000 = 8000.000 |
| REVERSAL | REF-009 / REV-REF-009, 25000.000 |
| AMOUNT_MISMATCH | REF-002, 11999.000 contre 12000.000 |
| AMBIGUOUS_MATCH | REF-AMB, deux écritures de 5000.000 |
| UNMATCHED_TRANSACTION | REF-NONE, 700.000 sans écriture |
| UNMATCHED_ENTRY | REF-ORPHAN, 1500.000 sans mouvement |
