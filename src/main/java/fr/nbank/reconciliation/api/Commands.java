package fr.nbank.reconciliation.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public final class Commands {

    private Commands() {
    }

    public record CreateImport(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9_\\-]{2,40}") String sourceSystem,
            @NotBlank @Pattern(regexp = "ACCOUNTING_ENTRIES|EXTERNAL_TRANSACTIONS") String sourceType,
            @NotNull LocalDate businessDate,
            @NotBlank @Size(max = 80) String sourceBatchId,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Min(0) Integer expectedRecordCount,
            Map<String, Object> metadata) {
    }

    public record CommitImport(@DecimalMin("0.0") BigDecimal controlTotal) {
    }

    public record RunReconciliation(
            @NotBlank String accountCode,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull LocalDate periodFrom,
            @NotNull LocalDate periodTo,
            String ruleVersion,
            Boolean replay) {
    }

    public record ResolveException(@NotBlank @Size(max = 500) String proposal, @NotBlank @Size(max = 500) String comment) {
    }

    public record ApproveException(
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(max = 500) String comment) {
    }

    public record ReopenException(@NotBlank @Size(max = 500) String comment) {
    }

    public record CreateRule(
            @NotBlank @Size(max = 40) String version,
            @NotNull @Min(1) Integer priority,
            @NotNull @Min(0) @Max(30) Integer dateWindowDays,
            @NotNull Boolean allowGrouped,
            @NotBlank @Pattern(regexp = "OPPOSITE|SAME") String directionMode) {
    }

    public record RegisterAttachment(
            @NotBlank @Size(max = 200) String filename,
            @NotBlank @Size(max = 80) String contentType,
            @NotBlank @Pattern(regexp = "sha256:[a-f0-9]{64}") String checksum,
            @NotNull @Min(1) @Max(20_000_000) Long byteSize) {
    }
}
