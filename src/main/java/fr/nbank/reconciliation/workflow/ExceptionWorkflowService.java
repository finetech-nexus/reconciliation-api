package fr.nbank.reconciliation.workflow;

import fr.nbank.reconciliation.api.Commands;
import fr.nbank.reconciliation.api.Views;
import fr.nbank.reconciliation.audit.AuditService;
import fr.nbank.reconciliation.persistence.ReconciliaStore;
import fr.nbank.reconciliation.persistence.Rows;
import fr.nbank.reconciliation.security.Access;
import fr.nbank.reconciliation.security.Actor;
import fr.nbank.reconciliation.security.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ExceptionWorkflowService {

    private static final Set<String> CONTENT_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "text/plain", "text/csv");

    private final ReconciliaStore store;
    private final AuditService audit;
    private final String storageDir;

    public ExceptionWorkflowService(ReconciliaStore store, AuditService audit, @Value("${reconcilia.storage-dir}") String storageDir) {
        this.store = store;
        this.audit = audit;
        this.storageDir = storageDir;
    }

    public Views.ExceptionView get(String exceptionId) {
        Actor actor = Access.read();
        return toView(load(exceptionId, actor), true);
    }

    public Views.PageView<Views.ExceptionView> list(String status, int page, int size) {
        Actor actor = Access.read();
        var slice = store.pageExceptions(store.scopeIds(actor.id()), status, page, size);
        return Views.PageView.of(slice.content().stream().map(row -> toView(row, false)).toList(), page, size, slice.total());
    }

    @Transactional
    public Views.ExceptionView resolve(String exceptionId, Commands.ResolveException command) {
        Actor actor = Access.require("RECON_ANALYST", "RECON_ADMIN");
        Rows.ExceptionRow row = load(exceptionId, actor);
        if (!Set.of("OPEN", "REOPENED").contains(row.status())) {
            throw new ApiException(409, "INVALID_STATE", "Le suspens n'est pas qualifiable");
        }
        store.insertAction(UUID.randomUUID(), row.id(), actor.id(), "PROPOSE", command.proposal() + " — " + command.comment());
        store.updateException(row.id(), "PENDING_APPROVAL", actor.id(), null);
        audit.event(actor.id(), "EXCEPTION_RESOLVE", "EXCEPTION", row.publicId(), command.proposal());
        return get(exceptionId);
    }

    @Transactional
    public Views.ExceptionView approve(String exceptionId, Commands.ApproveException command) {
        Actor actor = Access.require("RECON_APPROVER", "RECON_ADMIN");
        Rows.ExceptionRow row = load(exceptionId, actor);
        if (!"PENDING_APPROVAL".equals(row.status())) {
            throw new ApiException(409, "INVALID_STATE", "Aucune proposition à valider");
        }
        UUID proposer = store.latestProposalActor(row.id());
        if (proposer != null && proposer.equals(actor.id())) {
            audit.event(actor.id(), "AUTO_APPROVAL_FORBIDDEN", "EXCEPTION", row.publicId(), "Auto-validation interdite");
            throw new ApiException(403, "AUTO_APPROVAL_FORBIDDEN", "L'auteur de la proposition ne peut pas la valider");
        }
        UUID actionId = UUID.randomUUID();
        store.insertAction(actionId, row.id(), actor.id(), "DECIDE", command.comment());
        store.insertApproval(UUID.randomUUID(), actionId, actor.id(), command.decision(), command.comment());
        if ("APPROVE".equals(command.decision())) {
            store.updateException(row.id(), "RESOLVED", null, OffsetDateTime.now(ZoneOffset.UTC));
            closeRecords(row);
            audit.event(actor.id(), "EXCEPTION_APPROVE", "EXCEPTION", row.publicId(), command.decision());
        } else {
            store.updateException(row.id(), "OPEN", null, null);
            audit.event(actor.id(), "EXCEPTION_REJECT", "EXCEPTION", row.publicId(), command.decision());
        }
        return get(exceptionId);
    }

    @Transactional
    public Views.ExceptionView reopen(String exceptionId, Commands.ReopenException command) {
        Actor actor = Access.require("RECON_ANALYST", "RECON_ADMIN");
        Rows.ExceptionRow row = load(exceptionId, actor);
        if (!"RESOLVED".equals(row.status())) {
            throw new ApiException(409, "INVALID_STATE", "Seul un suspens résolu peut être rouvert");
        }
        store.insertAction(UUID.randomUUID(), row.id(), actor.id(), "REOPEN", command.comment());
        store.updateException(row.id(), "REOPENED", actor.id(), null);
        reopenRecords(row);
        audit.event(actor.id(), "EXCEPTION_REOPEN", "EXCEPTION", row.publicId(), command.comment());
        return get(exceptionId);
    }

    @Transactional
    public Views.AttachmentView attach(String exceptionId, Commands.RegisterAttachment command) {
        Actor actor = Access.require("RECON_ANALYST", "RECON_OPERATOR", "RECON_ADMIN");
        Rows.ExceptionRow row = load(exceptionId, actor);
        String contentType = command.contentType().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(400, "UNSUPPORTED_MEDIA", "Type de justificatif refusé");
        }
        String filename = command.filename().replaceAll("[^A-Za-z0-9._-]", "_");
        if (filename.contains("..")) {
            throw new ApiException(400, "VALIDATION_ERROR", "Nom de fichier refusé");
        }
        String storageKey = storageDir + "/" + row.publicId() + "/" + UUID.randomUUID() + "-" + filename;
        store.insertAttachment(UUID.randomUUID(), row.id(), filename, contentType, storageKey, command.checksum(), command.byteSize(), actor.id());
        audit.event(actor.id(), "ATTACHMENT_REGISTERED", "EXCEPTION", row.publicId(), storageKey);
        return new Views.AttachmentView(filename, contentType, storageKey, command.checksum(), command.byteSize());
    }

    private void closeRecords(Rows.ExceptionRow row) {
        if (row.externalId() != null) {
            store.setOpen("external_transaction", row.externalId(), false);
        }
        if (row.accountingId() != null) {
            store.setOpen("accounting_entry", row.accountingId(), false);
        }
    }

    private void reopenRecords(Rows.ExceptionRow row) {
        if (row.externalId() != null) {
            store.setOpen("external_transaction", row.externalId(), true);
        }
        if (row.accountingId() != null) {
            store.setOpen("accounting_entry", row.accountingId(), true);
        }
    }

    private Rows.ExceptionRow load(String exceptionId, Actor actor) {
        Rows.ExceptionRow row = store.findException(exceptionId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Suspens introuvable"));
        Rows.AccountRow account = store.findAccount(row.accountCode()).orElseThrow();
        if (!store.inScope(actor.id(), account.id())) {
            throw new ApiException(403, "ACCESS_DENIED", "Compte hors périmètre");
        }
        return row;
    }

    private Views.ExceptionView toView(Rows.ExceptionRow row, boolean withActions) {
        var actions = withActions
                ? store.listActions(row.id()).stream().map(action -> new Views.ActionView(
                        action.actionType(),
                        action.actorId() == null ? null : action.actorId().toString(),
                        action.comment(),
                        action.createdAt(),
                        action.decision())).toList()
                : java.util.List.<Views.ActionView>of();
        return new Views.ExceptionView(
                row.publicId(), row.accountCode(), row.exceptionType(), row.amount(), row.currency(), row.reference(),
                row.status(), row.priority(), row.ownerId() == null ? null : row.ownerId().toString(), row.detail(),
                row.detectedAt(), row.dueAt(), row.resolvedAt(), row.runPublicId(), actions);
    }
}
