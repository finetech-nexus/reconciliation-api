package fr.nbank.reconciliation.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class Views {

    private Views() {
    }

    public record ImportView(
            String importId,
            String status,
            int acceptedRecords,
            int rejectedRecords,
            int quarantinedRecords,
            String correlationId,
            String sourceSystem,
            String sourceType,
            LocalDate businessDate,
            String sourceBatchId,
            String currency,
            Integer expectedRecordCount,
            BigDecimal acceptedAmount,
            BigDecimal controlTotal,
            boolean replayed,
            String detail) {
    }

    public record RecordView(
            String sourceRecordId,
            String accountCode,
            LocalDate bookingDate,
            String currency,
            BigDecimal amount,
            String direction,
            String reference,
            String status,
            String rejectReason) {
    }

    public record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageView<T> of(List<T> content, int page, int size, long total) {
            int pages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
            return new PageView<>(content, page, size, total, pages);
        }
    }

    public record RunView(
            String reconciliationId,
            String status,
            String ruleVersion,
            String accountCode,
            String currency,
            LocalDate periodFrom,
            LocalDate periodTo,
            boolean replay,
            int matchedCount,
            int exceptionCount,
            BigDecimal externalTotal,
            BigDecimal accountingTotal,
            BigDecimal matchedTotal,
            BigDecimal difference,
            boolean balanced,
            String timings,
            String correlationId,
            OffsetDateTime createdAt,
            List<MatchView> matches) {
    }

    public record MatchView(String matchType, BigDecimal amount, String reference, List<ItemView> items) {
    }

    public record ItemView(String side, String sourceRecordId, BigDecimal amount) {
    }

    public record ExceptionView(
            String exceptionId,
            String accountCode,
            String exceptionType,
            BigDecimal amount,
            String currency,
            String reference,
            String status,
            String priority,
            String ownerId,
            String detail,
            OffsetDateTime detectedAt,
            OffsetDateTime dueAt,
            OffsetDateTime resolvedAt,
            String reconciliationId,
            List<ActionView> actions) {
    }

    public record ActionView(String actionType, String actorId, String comment, OffsetDateTime createdAt, String decision) {
    }

    public record AccountView(String accountCode, String name, String currency, String correspondent) {
    }

    public record RuleView(String version, int priority, int dateWindowDays, boolean allowGrouped, String directionMode) {
    }

    public record ReportView(
            LocalDate from,
            LocalDate to,
            int imports,
            int openExceptions,
            BigDecimal matchedAmount,
            BigDecimal exceptionAmount,
            BigDecimal autoMatchRate,
            List<RunView> runs) {
    }

    public record AuditView(
            OffsetDateTime eventTime,
            String actorId,
            String action,
            String entityType,
            String entityId,
            String correlationId,
            String detail) {
    }

    public record AttachmentView(String filename, String contentType, String storageKey, String checksum, long byteSize) {
    }

    public record ConnectorView(String connector, boolean configured, String message) {
    }

    public record ApiError(String code, String message, String correlationId, OffsetDateTime timestamp) {
    }
}
