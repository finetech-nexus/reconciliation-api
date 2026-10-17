package fr.nbank.reconciliation.reporting;

import fr.nbank.reconciliation.api.Views;
import fr.nbank.reconciliation.persistence.ReconciliaStore;
import fr.nbank.reconciliation.security.Access;
import fr.nbank.reconciliation.security.Actor;
import fr.nbank.reconciliation.security.ApiException;
import fr.nbank.reconciliation.workflow.ReconciliationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
public class ReportService {

    private final ReconciliaStore store;
    private final ReconciliationService reconciliations;

    public ReportService(ReconciliaStore store, ReconciliationService reconciliations) {
        this.store = store;
        this.reconciliations = reconciliations;
    }

    public Views.ReportView report(LocalDate from, LocalDate to) {
        Access.read();
        if (to.isBefore(from)) {
            throw new ApiException(400, "VALIDATION_ERROR", "La période est inversée");
        }
        int matched = store.sumMatchedCount(from, to);
        int exceptions = store.sumExceptionCount(from, to);
        BigDecimal rate = matched + exceptions == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(matched).divide(BigDecimal.valueOf(matched + exceptions), 4, RoundingMode.HALF_UP);
        List<Views.RunView> runs = reconciliations.list(0, 10).content();
        return new Views.ReportView(
                from,
                to,
                store.countImports(from, to),
                store.countOpenExceptions(),
                store.sumMatched(from, to),
                store.sumOpenExceptions(),
                rate,
                runs);
    }

    public Views.PageView<Views.AuditView> audit(int page, int size) {
        Actor actor = Access.require("RECON_AUDITOR", "RECON_ADMIN");
        var slice = store.pageAudit(page, size);
        var content = slice.content().stream().map(row -> new Views.AuditView(
                row.eventTime(),
                row.actorId() == null ? null : row.actorId().toString(),
                row.action(),
                row.entityType(),
                row.entityId(),
                row.correlationId(),
                row.detail())).toList();
        return Views.PageView.of(content, page, size, slice.total());
    }

    public List<Views.AccountView> accounts() {
        Actor actor = Access.read();
        return store.listAccounts(store.scopeIds(actor.id())).stream()
                .map(account -> new Views.AccountView(account.accountCode(), account.name(), account.currency(), account.correspondent()))
                .toList();
    }
}
