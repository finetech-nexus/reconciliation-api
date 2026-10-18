package fr.nbank.reconciliation.api;

import fr.nbank.reconciliation.audit.AuditService;
import fr.nbank.reconciliation.persistence.ReconciliaStore;
import fr.nbank.reconciliation.persistence.Rows;
import fr.nbank.reconciliation.security.Access;
import fr.nbank.reconciliation.security.Actor;
import fr.nbank.reconciliation.security.ApiException;
import fr.nbank.reconciliation.sourceconnectors.CoreBankingPullService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final ReconciliaStore store;
    private final CoreBankingPullService core;
    private final AuditService audit;

    public SettingsController(ReconciliaStore store, CoreBankingPullService core, AuditService audit) {
        this.store = store;
        this.core = core;
        this.audit = audit;
    }

    @GetMapping("/core-banking")
    public Views.CoreProfileView coreBanking() {
        Access.read();
        return coreView(core.resolved());
    }

    @PutMapping("/core-banking")
    @Transactional
    public Views.CoreProfileView saveCore(@Valid @RequestBody Commands.UpdateCore command) {
        Actor actor = Access.require("RECON_ADMIN");
        store.saveCore(command.connectorType(), empty(command.baseUrl()), empty(command.tenant()),
                empty(command.accountsPath()), empty(command.entriesPath()));
        audit.event(actor.id(), "CORE_PROFILE_UPDATED", "SETTINGS", command.connectorType(), "Connecteur core banking");
        return coreView(core.resolved());
    }

    @GetMapping("/correspondents")
    public List<Views.CorrespondentView> correspondents() {
        Access.read();
        return store.listCorrespondents().stream().map(SettingsController::correspondentView).toList();
    }

    @PostMapping("/correspondents")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Views.CorrespondentView createCorrespondent(@Valid @RequestBody Commands.CreateCorrespondent command) {
        Actor actor = Access.require("RECON_ADMIN");
        if (store.findCorrespondent(command.code()).isPresent()) {
            throw new ApiException(409, "CORRESPONDENT_EXISTS", "Ce correspondant existe déjà");
        }
        Rows.SourceRow source = store.findSource("CORRESPONDENT")
                .orElseThrow(() -> new ApiException(422, "UNKNOWN_SOURCE", "Système correspondant absent"));
        store.insertCorrespondent(UUID.randomUUID(), command.code(), command.name(), blankToNull(command.bic()),
                command.currency(), command.accountCode());
        if (store.findAccount(command.accountCode()).isEmpty()) {
            store.insertAccount(UUID.randomUUID(), command.accountCode(), command.name(), command.currency(),
                    source.id(), command.code());
        }
        audit.event(actor.id(), "CORRESPONDENT_CREATED", "CORRESPONDENT", command.code(), command.accountCode());
        return store.findCorrespondent(command.code()).map(SettingsController::correspondentView).orElseThrow();
    }

    @GetMapping("/cursors")
    public List<Views.CursorView> cursors() {
        Access.read();
        return store.listCursors().stream().map(row -> new Views.CursorView(
                row.sourceCode(), row.accountCode(), row.lastBusinessDate(), row.lastSequence(),
                row.lastBatchPublicId(), row.gapDetected())).toList();
    }

    @PostMapping("/purge")
    @Transactional
    public Views.PurgeView purge(@Valid @RequestBody Commands.PurgeData command) {
        Actor actor = Access.require("RECON_ADMIN");
        if (!"PURGE".equals(command.confirmation())) {
            throw new ApiException(400, "CONFIRMATION_REQUIRED", "Saisir PURGE pour effacer les mouvements");
        }
        store.purgeOperational();
        audit.event(actor.id(), "DATA_PURGED", "SETTINGS", "operational",
                "Mouvements, lots, rapprochements et repères effacés. Comptes, correspondants et audit conservés.");
        return new Views.PurgeView(true);
    }

    private static Views.CoreProfileView coreView(Rows.CoreProfile profile) {
        boolean configured = !profile.baseUrl().isBlank() && !profile.entriesPath().isBlank();
        return new Views.CoreProfileView("CORE_BANKING", profile.connectorType(), profile.baseUrl(), profile.tenant(),
                profile.accountsPath(), profile.entriesPath(), configured);
    }

    private static Views.CorrespondentView correspondentView(Rows.CorrespondentRow row) {
        return new Views.CorrespondentView(row.code(), row.name(), row.bic(), row.currency(), row.accountCode());
    }

    private static String empty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
