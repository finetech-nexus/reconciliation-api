# Exemples source par type

Deux types sont acceptés : `ACCOUNTING_ENTRIES` et `EXTERNAL_TRANSACTIONS`. Le CSV canonique et le JSON portent les mêmes champs. Les montants sont des chaînes décimales.

En-tête CSV :

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
```

La colonne optionnelle `reversal_of` sert à l'extourne. Un mouvement dont la référence commence par `REV-` est aussi traité comme une extourne.

## ACCOUNTING_ENTRIES

Écritures du grand livre, système `CORE_BANKING`. Fineract ou T24 est le connecteur choisi dans Réglages, pas le nom du lot. Le cycle BCT et correspondant est décrit dans [recurrent-imports.md](recurrent-imports.md).

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
GL-001;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;CREDIT;REF-001
GL-002;BCT-TND-001;2026-10-16;2026-10-16;TND;11999.000;DEBIT;REF-002
GL-003;BCT-TND-001;2026-10-16;2026-10-16;TND;3000.000;CREDIT;REF-005
GL-004;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;CREDIT;REF-005
GL-005;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;DEBIT;REF-AMB
GL-006;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;DEBIT;REF-AMB
GL-007;BCT-TND-001;2026-10-16;2026-10-16;TND;1500.000;CREDIT;REF-ORPHAN
GL-009;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;DEBIT;REF-009
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

## EXTERNAL_TRANSACTIONS

Mouvements BCT ou correspondant.

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference
TX-001;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;DEBIT;REF-001
TX-002;BCT-TND-001;2026-10-16;2026-10-16;TND;12000.000;CREDIT;REF-002
TX-003;BCT-TND-001;2026-10-16;2026-10-16;TND;8000.000;DEBIT;REF-005
TX-004;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;DEBIT;REF-AMB
TX-005;BCT-TND-001;2026-10-16;2026-10-16;TND;700.000;DEBIT;REF-NONE
```

Extourne, avec `reversal_of` :

```
source_id;account_id;booking_date;value_date;currency;amount;direction;reference;reversal_of
TX-009;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;CREDIT;REV-REF-009;REF-009
```

```json
{
  "records": [
    {
      "sourceRecordId": "TX-001",
      "accountCode": "BCT-TND-001",
      "bookingDate": "2026-10-16",
      "valueDate": "2026-10-16",
      "currency": "TND",
      "amount": "25000.000",
      "direction": "DEBIT",
      "reference": "REF-001"
    }
  ]
}
```

## Résultat du jeu d'exemple

| Type | Source |
| --- | --- |
| EXACT | REF-001, 25000.000 des deux côtés |
| GROUPED | REF-005, 3000.000 + 5000.000 = 8000.000 |
| REVERSAL | REF-009 / REV-REF-009, 25000.000 |
| AMOUNT_MISMATCH | REF-002, 11999.000 contre 12000.000 |
| AMBIGUOUS_MATCH | REF-AMB, deux écritures de 5000.000 |
| UNMATCHED_TRANSACTION | REF-NONE, 700.000 sans écriture |
| UNMATCHED_ENTRY | REF-ORPHAN, 1500.000 sans mouvement |
