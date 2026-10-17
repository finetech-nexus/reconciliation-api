package fr.nbank.reconciliation.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class Rows {

    private Rows() {
    }

    public record SourceRow(UUID id, String code, String name) {
    }

    public record AccountRow(UUID id, String accountCode, String name, String currency, String correspondent) {
    }

    public record RuleRow(
            UUID id,
            String version,
            int priority,
            int dateWindowDays,
            boolean allowGrouped,
            String directionMode) {
    }

    public record BatchRow(
            UUID id,
            String publicId,
            UUID sourceSystemId,
            String sourceCode,
            String sourceType,
            LocalDate businessDate,
            String sourceBatchId,
            String currency,
            Integer expectedRecordCount,
            String status,
            int acceptedRecords,
            int rejectedRecords,
            int quarantinedRecords,
            BigDecimal acceptedAmount,
            BigDecimal controlTotal,
            String checksum,
            String correlationId) {
    }

    public record StageRow(
            UUID id,
            String sourceRecordId,
            String accountCode,
            LocalDate bookingDate,
            LocalDate valueDate,
            String currency,
            BigDecimal amount,
            String direction,
            String reference,
            String reversalOf,
            String status,
            String rejectReason) {
    }

    public record CanonicalRow(
            UUID id,
            String sourceRecordId,
            LocalDate bookingDate,
            BigDecimal amount,
            String reference,
            String reversalOf) {
    }

    public record RunRow(
            UUID id,
            String publicId,
            String accountCode,
            String currency,
            LocalDate periodFrom,
            LocalDate periodTo,
            String ruleVersion,
            String status,
            boolean replay,
            BigDecimal externalTotal,
            BigDecimal accountingTotal,
            BigDecimal matchedTotal,
            BigDecimal difference,
            int matchedCount,
            int exceptionCount,
            String timings,
            String correlationId,
            OffsetDateTime createdAt) {
    }

    public record MatchRow(UUID id, String matchType, BigDecimal amount, String reference) {
    }

    public record MatchItemRow(UUID matchId, String recordSide, BigDecimal amount, String sourceRecordId) {
    }

    public record ExceptionRow(
            UUID id,
            String publicId,
            String accountCode,
            UUID externalId,
            UUID accountingId,
            String exceptionType,
            BigDecimal amount,
            String currency,
            String reference,
            String status,
            String priority,
            UUID ownerId,
            String detail,
            OffsetDateTime detectedAt,
            OffsetDateTime dueAt,
            OffsetDateTime resolvedAt,
            String runPublicId) {
    }

    public record ActionRow(UUID id, UUID actorId, String actionType, String comment, OffsetDateTime createdAt, String decision) {
    }

    public record AuditRow(
            OffsetDateTime eventTime,
            UUID actorId,
            String action,
            String entityType,
            String entityId,
            String correlationId,
            String detail) {
    }

    public record IdemRow(String requestHash, int httpStatus, String responseBody) {
    }

    public record Slice<T>(java.util.List<T> content, long total) {
    }
}
