package fr.nbank.reconciliation.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.nbank.reconciliation.api.Commands;
import fr.nbank.reconciliation.api.Views;
import fr.nbank.reconciliation.audit.AuditService;
import fr.nbank.reconciliation.observability.Correlation;
import fr.nbank.reconciliation.persistence.ReconciliaStore;
import fr.nbank.reconciliation.persistence.Rows;
import fr.nbank.reconciliation.security.Access;
import fr.nbank.reconciliation.security.Actor;
import fr.nbank.reconciliation.security.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
public class ImportService {

    private final ReconciliaStore store;
    private final RecordParsers parsers;
    private final AuditService audit;
    private final ObjectMapper json;

    public ImportService(ReconciliaStore store, RecordParsers parsers, AuditService audit, ObjectMapper json) {
        this.store = store;
        this.parsers = parsers;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public Views.ImportView open(Commands.CreateImport command) {
        Actor actor = Access.require("RECON_ADMIN", "RECON_OPERATOR", "RECON_SERVICE");
        Rows.SourceRow source = store.findSource(command.sourceSystem())
                .orElseThrow(() -> new ApiException(422, "UNKNOWN_SOURCE", "Système source inconnu"));
        var existing = store.findBatchBySource(source.id(), command.sourceBatchId());
        if (existing.isPresent()) {
            audit.event(actor.id(), "IMPORT_REPLAYED", "IMPORT", existing.get().publicId(), "Rejeu sans doublon");
            return view(existing.get(), true, null);
        }
        UUID id = UUID.randomUUID();
        String publicId = store.nextPublicId("import_batch", "imp", command.businessDate());
        String metadata = writeMetadata(command.metadata());
        store.insertBatch(id, publicId, source.id(), command.sourceType(), command.businessDate(),
                command.sourceBatchId(), command.currency(), command.expectedRecordCount(), Correlation.current(), metadata);
        store.insertWork(UUID.randomUUID(), "ingestion", publicId, "DONE");
        audit.event(actor.id(), "IMPORT_RECEIVED", "IMPORT", publicId, command.sourceType());
        return view(store.findBatch(publicId).orElseThrow(), false, null);
    }

    @Transactional
    public Views.ImportView stage(String importId, String contentType, String body) {
        Actor actor = Access.require("RECON_ADMIN", "RECON_OPERATOR", "RECON_SERVICE");
        Rows.BatchRow batch = requireOpenBatch(importId);
        String checksum = "sha256:" + sha256(body);
        var duplicate = store.findPublicIdByChecksum(checksum);
        if (duplicate.isPresent() && !duplicate.get().equals(batch.publicId())) {
            throw new ApiException(409, "FILE_ALREADY_PROCESSED", "Empreinte déjà traitée");
        }
        ParsedFile parsed = parsers.parse(contentType, body);
        store.clearStaged(batch.id());
        if (parsed.fatal() != null) {
            store.updateBatch(batch.id(), "REJECTED", 0, 0, 0, BigDecimal.ZERO, null, checksum);
            audit.event(actor.id(), "IMPORT_REJECTED", "IMPORT", batch.publicId(), parsed.fatal());
            return view(store.findBatch(batch.publicId()).orElseThrow(), false, parsed.fatal());
        }
        Set<String> seen = new HashSet<>();
        for (ParsedFile.ParsedRow row : parsed.rows()) {
            String reason = row.rejectReason();
            String status = "STAGED";
            String recordId = row.sourceRecordId() == null ? "line-" + row.line() : row.sourceRecordId();
            if (reason == null && !seen.add(recordId)) {
                status = "REJECTED";
                reason = "Doublon dans le fichier";
                recordId = recordId + "#dup-" + row.line();
            }
            store.insertStage(new Rows.StageRow(
                    UUID.randomUUID(),
                    recordId,
                    row.accountCode(),
                    row.bookingDate(),
                    row.valueDate(),
                    row.currency(),
                    row.amount(),
                    row.direction(),
                    row.reference(),
                    row.reversalOf(),
                    status,
                    reason), batch.id());
        }
        store.updateBatch(batch.id(), "STAGING", 0, 0, 0, BigDecimal.ZERO, null, checksum);
        audit.event(actor.id(), "IMPORT_STAGED", "IMPORT", batch.publicId(), parsed.rows().size() + " lignes");
        return view(store.findBatch(batch.publicId()).orElseThrow(), false, null);
    }

    @Transactional
    public Views.ImportView commit(String importId, BigDecimal controlTotal) {
        Actor actor = Access.require("RECON_ADMIN", "RECON_OPERATOR", "RECON_SERVICE");
        Rows.BatchRow batch = store.findBatch(importId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Lot introuvable"));
        if ("LOADED".equals(batch.status()) || "PARTIAL".equals(batch.status())) {
            return view(batch, true, null);
        }
        if (!"STAGING".equals(batch.status()) && !"RECEIVED".equals(batch.status())) {
            throw new ApiException(409, "INVALID_STATE", "Le lot n'est pas en attente de contrôle");
        }
        var staged = store.listStaged(batch.id());
        if (staged.isEmpty()) {
            throw new ApiException(422, "EMPTY_BATCH", "Aucune ligne en staging");
        }
        BigDecimal acceptedAmount = BigDecimal.ZERO;
        int accepted = 0;
        int rejected = 0;
        int quarantined = 0;
        Set<UUID> acceptedIds = new HashSet<>();
        for (Rows.StageRow row : staged) {
            if ("REJECTED".equals(row.status())) {
                rejected++;
                continue;
            }
            String reason = validate(batch, row);
            if (reason == null) {
                accepted++;
                acceptedIds.add(row.id());
                acceptedAmount = acceptedAmount.add(row.amount());
            } else if (reason.startsWith("QUARANTINE:")) {
                quarantined++;
                store.updateStageStatus(row.id(), "QUARANTINED", reason.substring("QUARANTINE:".length()));
            } else {
                rejected++;
                store.updateStageStatus(row.id(), "REJECTED", reason);
            }
        }
        if (controlTotal != null && controlTotal.compareTo(acceptedAmount) != 0) {
            for (Rows.StageRow row : staged) {
                store.updateStageStatus(row.id(), "QUARANTINED", "Écart de total de contrôle");
            }
            store.updateBatch(batch.id(), "QUARANTINED", 0, 0, staged.size(), BigDecimal.ZERO, controlTotal, null);
            audit.event(actor.id(), "IMPORT_QUARANTINED", "IMPORT", batch.publicId(), "Écart de totaux");
            return view(store.findBatch(batch.publicId()).orElseThrow(), false, "Écart de totaux");
        }
        if (accepted > 0) {
            promote(batch, staged, acceptedIds);
        }
        String status;
        if (accepted == 0 && quarantined > 0 && rejected == 0) {
            status = "QUARANTINED";
        } else if (accepted == 0) {
            status = "REJECTED";
        } else if (rejected > 0 || quarantined > 0) {
            status = "PARTIAL";
        } else {
            status = "LOADED";
        }
        int finalAccepted = "QUARANTINED".equals(status) && accepted == 0 ? 0 : accepted;
        store.updateBatch(batch.id(), status, finalAccepted, rejected, quarantined, acceptedAmount, controlTotal, null);
        store.insertWork(UUID.randomUUID(), "ingestion", batch.publicId(), "DONE");
        if (accepted > 0) {
            advanceCursors(batch, staged, acceptedIds);
        }
        audit.event(actor.id(), "IMPORT_COMMITTED", "IMPORT", batch.publicId(), status);
        return view(store.findBatch(batch.publicId()).orElseThrow(), false, null);
    }

    public Views.ImportView get(String importId) {
        Actor actor = Access.read();
        Rows.BatchRow batch = store.findBatch(importId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Lot introuvable"));
        assertBatchScope(actor, batch);
        return view(batch, false, null);
    }

    public Views.PageView<Views.ImportView> list(int page, int size) {
        Actor actor = Access.read();
        var slice = store.pageBatches(store.scopeIds(actor.id()), page, size);
        return Views.PageView.of(slice.content().stream().map(row -> view(row, false, null)).toList(), page, size, slice.total());
    }

    public Views.PageView<Views.RecordView> records(String importId, int page, int size) {
        Actor actor = Access.read();
        Rows.BatchRow batch = store.findBatch(importId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Lot introuvable"));
        assertBatchScope(actor, batch);
        var slice = store.pageRecords(batch.id(), page, size);
        var content = slice.content().stream().map(row -> new Views.RecordView(
                row.sourceRecordId(), row.accountCode(), row.bookingDate(), row.currency(), row.amount(),
                row.direction(), row.reference(), row.status(), row.rejectReason())).toList();
        return Views.PageView.of(content, page, size, slice.total());
    }

    private void advanceCursors(Rows.BatchRow batch, java.util.List<Rows.StageRow> staged, Set<UUID> acceptedIds) {
        java.util.Set<String> accounts = new java.util.LinkedHashSet<>();
        for (Rows.StageRow row : staged) {
            if (acceptedIds.contains(row.id()) && row.accountCode() != null) {
                accounts.add(row.accountCode());
            }
        }
        for (String account : accounts) {
            var current = store.findCursor(batch.sourceSystemId(), account);
            LocalDate previous = current.map(Rows.CursorRow::lastBusinessDate).orElse(null);
            int previousSequence = current.map(Rows.CursorRow::lastSequence).orElse(0);
            boolean newer = previous == null || batch.businessDate().isAfter(previous);
            boolean sameDay = previous != null && previous.equals(batch.businessDate());
            boolean gap = previous != null && batch.businessDate().isAfter(previous.plusDays(1));
            LocalDate watermark = newer ? batch.businessDate() : previous;
            int sequence = sameDay ? previousSequence + 1 : (newer ? 1 : previousSequence);
            boolean gapFlag = gap || (current.map(Rows.CursorRow::gapDetected).orElse(false) && !newer && !sameDay);
            store.saveCursor(batch.sourceSystemId(), account, watermark, sequence, batch.publicId(), gapFlag);
        }
    }

    private void promote(Rows.BatchRow batch, java.util.List<Rows.StageRow> staged, Set<UUID> acceptedIds) {
        boolean accounting = "ACCOUNTING_ENTRIES".equals(batch.sourceType());
        for (Rows.StageRow row : staged) {
            if (!acceptedIds.contains(row.id())) {
                continue;
            }
            Rows.AccountRow account = store.findAccount(row.accountCode()).orElseThrow();
            UUID id = UUID.randomUUID();
            if (accounting) {
                store.insertAccounting(id, batch.sourceSystemId(), row.sourceRecordId(), batch.id(), account.id(),
                        row.bookingDate(), row.valueDate(), row.currency(), row.amount(), row.direction(),
                        row.reference(), row.reversalOf());
            } else {
                store.insertExternal(id, batch.sourceSystemId(), row.sourceRecordId(), batch.id(), account.id(),
                        row.bookingDate(), row.valueDate(), row.currency(), row.amount(), row.direction(),
                        row.reference(), row.reversalOf());
            }
            store.updateStageStatus(row.id(), "ACCEPTED", null);
        }
    }

    private String validate(Rows.BatchRow batch, Rows.StageRow row) {
        if (row.currency() == null || !row.currency().equals(batch.currency())) {
            return "QUARANTINE:Devise différente de celle du lot";
        }
        var account = store.findAccount(row.accountCode());
        if (account.isEmpty()) {
            return "QUARANTINE:Compte inconnu";
        }
        if (!account.get().currency().equals(row.currency())) {
            return "QUARANTINE:Devise différente du compte";
        }
        if (!store.inScope(Access.read().id(), account.get().id())) {
            return "QUARANTINE:Compte hors périmètre";
        }
        boolean duplicate = "ACCOUNTING_ENTRIES".equals(batch.sourceType())
                ? store.accountingExists(batch.sourceSystemId(), row.sourceRecordId())
                : store.externalExists(batch.sourceSystemId(), row.sourceRecordId());
        if (duplicate) {
            return "Doublon métier déjà chargé";
        }
        return null;
    }

    private Rows.BatchRow requireOpenBatch(String importId) {
        Rows.BatchRow batch = store.findBatch(importId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Lot introuvable"));
        if ("LOADED".equals(batch.status()) || "PARTIAL".equals(batch.status())) {
            throw new ApiException(409, "INVALID_STATE", "Lot déjà promu");
        }
        return batch;
    }

    private void assertBatchScope(Actor actor, Rows.BatchRow batch) {
        if (store.unrestricted(actor.id())) {
            return;
        }
        var records = store.pageRecords(batch.id(), 0, 1);
        if (records.content().isEmpty()) {
            return;
        }
        var account = store.findAccount(records.content().get(0).accountCode());
        if (account.isPresent() && !store.inScope(actor.id(), account.get().id())) {
            throw new ApiException(403, "ACCESS_DENIED", "Compte hors périmètre");
        }
    }

    private Views.ImportView view(Rows.BatchRow batch, boolean replayed, String detail) {
        return new Views.ImportView(
                batch.publicId(), batch.status(), batch.acceptedRecords(), batch.rejectedRecords(),
                batch.quarantinedRecords(), Correlation.current(), batch.sourceCode(), batch.sourceType(),
                batch.businessDate(), batch.sourceBatchId(), batch.currency(), batch.expectedRecordCount(),
                batch.acceptedAmount(), batch.controlTotal(), replayed, detail);
    }

    private String writeMetadata(java.util.Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new ApiException(400, "VALIDATION_ERROR", "Métadonnées illisibles");
        }
    }

    private static String sha256(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
