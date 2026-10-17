package fr.nbank.reconciliation.api;

import fr.nbank.reconciliation.workflow.ReconciliationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ReconciliationController {

    private final ReconciliationService reconciliations;
    private final Idempotency idempotency;

    public ReconciliationController(ReconciliationService reconciliations, Idempotency idempotency) {
        this.reconciliations = reconciliations;
        this.idempotency = idempotency;
    }

    @PostMapping("/reconciliations")
    public ResponseEntity<Views.RunView> run(@Valid @RequestBody Commands.RunReconciliation command) {
        return idempotency.execute(command, Views.RunView.class, 201, () -> reconciliations.run(command));
    }

    @GetMapping("/reconciliations")
    public Views.PageView<Views.RunView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reconciliations.list(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/reconciliations/{reconciliationId}")
    public Views.RunView get(@PathVariable String reconciliationId) {
        return reconciliations.get(reconciliationId);
    }

    @GetMapping("/rules")
    public List<Views.RuleView> rules() {
        return reconciliations.rules();
    }

    @PostMapping("/rules")
    public ResponseEntity<Views.RuleView> addRule(@Valid @RequestBody Commands.CreateRule command) {
        return idempotency.execute(command, Views.RuleView.class, 201, () -> reconciliations.addRule(command));
    }
}
