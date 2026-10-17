package fr.nbank.reconciliation.api;

import fr.nbank.reconciliation.reporting.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports/reconciliation")
    public Views.ReportView reconciliation(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        return reports.report(start, end);
    }

    @GetMapping("/accounts")
    public List<Views.AccountView> accounts() {
        return reports.accounts();
    }

    @GetMapping("/audit")
    public Views.PageView<Views.AuditView> audit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reports.audit(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }
}
