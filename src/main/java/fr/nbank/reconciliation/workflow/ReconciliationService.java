package fr.nbank.reconciliation.workflow;

import fr.nbank.reconciliation.api.Commands;
import fr.nbank.reconciliation.api.Views;
import fr.nbank.reconciliation.audit.AuditService;
import fr.nbank.reconciliation.engine.ReconciliationEngine;
import fr.nbank.reconciliation.observability.Correlation;
import fr.nbank.reconciliation.persistence.ReconciliaStore;
import fr.nbank.reconciliation.persistence.Rows;
import fr.nbank.reconciliation.security.Access;
import fr.nbank.reconciliation.security.Actor;
import fr.nbank.reconciliation.security.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ReconciliationService {

    private final ReconciliaStore store;
    private final ReconciliationEngine engine;
    private final AuditService audit;

    public ReconciliationService(ReconciliaStore store, ReconciliationEngine engine, AuditService audit) {
        this.store = store;
        this.engine = engine;
        this.audit = audit;
    }

    @Transactional
    public Views.RunView run(Commands.RunReconciliation command) {
        Actor actor = Access.require("RECON_ADMIN", "RECON_OPERATOR");
        if (command.periodTo().isBefore(command.periodFrom())) {
            throw new ApiException(400, "VALIDATION_ERROR", "La période est inversée");
        }
        Rows.AccountRow account = store.findAccount(command.accountCode())
                .orElseThrow(() -> new ApiException(422, "UNKNOWN_ACCOUNT", "Compte inconnu"));
        if (!store.inScope(actor.id(), account.id())) {
            throw new ApiException(403, "ACCESS_DENIED", "Compte hors périmètre");
        }
        if (!account.currency().equals(command.currency())) {
            throw new ApiException(422, "CURRENCY_MISMATCH", "Devise différente du compte");
        }
        Rows.RuleRow rule = command.ruleVersion() == null || command.ruleVersion().isBlank()
                ? store.latestRule().orElseThrow(() -> new ApiException(422, "NO_RULE", "Aucune règle approuvée"))
                : store.findRule(command.ruleVersion()).orElseThrow(() -> new ApiException(422, "UNKNOWN_RULE", "Version de règle inconnue"));
        boolean replay = Boolean.TRUE.equals(command.replay());
        long started = System.nanoTime();
        var external = store.loadExternal(account.id(), command.currency(), command.periodFrom(), command.periodTo(), replay);
        var accounting = store.loadAccounting(account.id(), command.currency(), command.periodFrom(), command.periodTo(), replay);
        long loaded = System.nanoTime();
        var result = engine.reconcile(map(external), map(accounting), rule.dateWindowDays(), rule.allowGrouped());
        long matched = System.nanoTime();
        UUID runId = UUID.randomUUID();
        String publicId = store.nextPublicId("reconciliation_run", "run", command.periodTo());
        if (replay) {
            store.supersede(account.id(), command.periodFrom(), command.periodTo(), runId);
        }
        boolean balanced = result.difference().compareTo(BigDecimal.ZERO) == 0;
        String timings = "load=" + ms(loaded - started) + ",match=" + ms(matched - loaded);
        store.insertRun(runId, publicId, account.id(), command.currency(), command.periodFrom(), command.periodTo(),
                rule.id(), rule.version(), balanced ? "COMPLETED" : "ANOMALY", replay, result.externalTotal(),
                result.accountingTotal(), result.matchedTotal(), result.difference(), result.matches().size(),
                result.breaks().size(), timings, Correlation.current());
        for (var group : result.matches()) {
            UUID matchId = UUID.randomUUID();
            store.insertMatch(matchId, runId, group.type(), group.amount(), command.currency(), group.reference());
            store.insertMatchItem(matchId, group.external().id(), "EXTERNAL", group.external().amount(), group.external().sourceRecordId());
            store.setOpen("external_transaction", group.external().id(), false);
            closeIfOpen(group.external().id(), publicId);
            for (var gl : group.accounting()) {
                store.insertMatchItem(matchId, gl.id(), "ACCOUNTING", gl.amount(), gl.sourceRecordId());
                store.setOpen("accounting_entry", gl.id(), false);
                closeIfOpen(gl.id(), publicId);
            }
        }
        OffsetDateTime detected = OffsetDateTime.now(ZoneOffset.UTC);
        for (var item : result.breaks()) {
            UUID recordId = item.externalId() != null ? item.externalId() : item.accountingId();
            if (recordId != null && store.hasOpenException(recordId)) {
                continue;
            }
            store.insertException(UUID.randomUUID(), store.nextPublicId("reconciliation_exception", "exc", command.periodTo()),
                    runId, account.id(), item.externalId(), item.accountingId(), item.type(), item.amount(),
                    command.currency(), item.reference(), item.priority(), item.detail(), detected, detected.plusDays(2));
        }
        store.insertWork(UUID.randomUUID(), "reconciliation", publicId, "DONE");
        audit.event(actor.id(), "RECONCILIATION_RUN", "RECONCILIATION", publicId, rule.version());
        return get(publicId);
    }

    public Views.RunView get(String reconciliationId) {
        Actor actor = Access.read();
        Rows.RunRow run = store.findRun(reconciliationId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Rapprochement introuvable"));
        Rows.AccountRow account = store.findAccount(run.accountCode()).orElseThrow();
        if (!store.inScope(actor.id(), account.id())) {
            throw new ApiException(403, "ACCESS_DENIED", "Compte hors périmètre");
        }
        return toView(run, true);
    }

    public Views.PageView<Views.RunView> list(int page, int size) {
        Actor actor = Access.read();
        var slice = store.pageRuns(store.scopeIds(actor.id()), page, size);
        return Views.PageView.of(slice.content().stream().map(row -> toView(row, false)).toList(), page, size, slice.total());
    }

    @Transactional
    public Views.RuleView addRule(Commands.CreateRule command) {
        Actor actor = Access.require("RECON_ADMIN");
        if (store.findRule(command.version()).isPresent()) {
            throw new ApiException(409, "RULE_EXISTS", "Cette version de règle existe déjà");
        }
        store.insertRule(UUID.randomUUID(), command.version(), command.priority(), command.dateWindowDays(),
                command.allowGrouped(), command.directionMode());
        audit.event(actor.id(), "RULE_VERSIONED", "RULE", command.version(), command.directionMode());
        return new Views.RuleView(command.version(), command.priority(), command.dateWindowDays(), command.allowGrouped(), command.directionMode());
    }

    public List<Views.RuleView> rules() {
        Access.read();
        return store.listRules().stream()
                .map(rule -> new Views.RuleView(rule.version(), rule.priority(), rule.dateWindowDays(), rule.allowGrouped(), rule.directionMode()))
                .toList();
    }

    private void closeIfOpen(UUID recordId, String runPublicId) {
        for (UUID exceptionId : store.openExceptionIds(recordId)) {
            store.systemResolve(exceptionId, "Soldé par le rapprochement " + runPublicId);
        }
    }

    private Views.RunView toView(Rows.RunRow run, boolean withMatches) {
        List<Views.MatchView> matches = List.of();
        if (withMatches) {
            var items = store.listMatchItems(run.id());
            matches = store.listMatches(run.id()).stream().map(match -> new Views.MatchView(
                    match.matchType(),
                    match.amount(),
                    match.reference(),
                    items.stream()
                            .filter(item -> item.matchId().equals(match.id()))
                            .map(item -> new Views.ItemView(item.recordSide(), item.sourceRecordId(), item.amount()))
                            .toList())).toList();
        }
        return new Views.RunView(
                run.publicId(), run.status(), run.ruleVersion(), run.accountCode(), run.currency(),
                run.periodFrom(), run.periodTo(), run.replay(), run.matchedCount(), run.exceptionCount(),
                run.externalTotal(), run.accountingTotal(), run.matchedTotal(), run.difference(),
                run.difference().compareTo(BigDecimal.ZERO) == 0, run.timings(), run.correlationId(),
                run.createdAt(), matches);
    }

    private static List<ReconciliationEngine.Row> map(List<Rows.CanonicalRow> rows) {
        List<ReconciliationEngine.Row> mapped = new ArrayList<>();
        for (Rows.CanonicalRow row : rows) {
            mapped.add(new ReconciliationEngine.Row(row.id(), row.sourceRecordId(), row.bookingDate(), row.amount(), row.reference(), row.reversalOf()));
        }
        return mapped;
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
