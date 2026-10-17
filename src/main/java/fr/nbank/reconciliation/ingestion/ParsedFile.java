package fr.nbank.reconciliation.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ParsedFile(String fatal, List<ParsedRow> rows) {

    public static ParsedFile error(String fatal) {
        return new ParsedFile(fatal, List.of());
    }

    public record ParsedRow(
            int line,
            String sourceRecordId,
            String accountCode,
            LocalDate bookingDate,
            LocalDate valueDate,
            String currency,
            BigDecimal amount,
            String direction,
            String reference,
            String reversalOf,
            String rejectReason) {
    }
}
