package fr.nbank.reconciliation.api;

import fr.nbank.reconciliation.workflow.ExceptionWorkflowService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exceptions")
public class ExceptionController {

    private final ExceptionWorkflowService workflow;
    private final Idempotency idempotency;

    public ExceptionController(ExceptionWorkflowService workflow, Idempotency idempotency) {
        this.workflow = workflow;
        this.idempotency = idempotency;
    }

    @GetMapping
    public Views.PageView<Views.ExceptionView> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return workflow.list(status, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/{exceptionId}")
    public Views.ExceptionView get(@PathVariable String exceptionId) {
        return workflow.get(exceptionId);
    }

    @PostMapping("/{exceptionId}/resolve")
    public ResponseEntity<Views.ExceptionView> resolve(
            @PathVariable String exceptionId,
            @Valid @RequestBody Commands.ResolveException command) {
        return idempotency.execute(exceptionId + command.proposal() + command.comment(), Views.ExceptionView.class, 200,
                () -> workflow.resolve(exceptionId, command));
    }

    @PostMapping("/{exceptionId}/approve")
    public ResponseEntity<Views.ExceptionView> approve(
            @PathVariable String exceptionId,
            @Valid @RequestBody Commands.ApproveException command) {
        return idempotency.execute(exceptionId + command.decision() + command.comment(), Views.ExceptionView.class, 200,
                () -> workflow.approve(exceptionId, command));
    }

    @PostMapping("/{exceptionId}/reopen")
    public ResponseEntity<Views.ExceptionView> reopen(
            @PathVariable String exceptionId,
            @Valid @RequestBody Commands.ReopenException command) {
        return idempotency.execute(exceptionId + command.comment(), Views.ExceptionView.class, 200,
                () -> workflow.reopen(exceptionId, command));
    }

    @PostMapping("/{exceptionId}/attachments")
    public ResponseEntity<Views.AttachmentView> attach(
            @PathVariable String exceptionId,
            @Valid @RequestBody Commands.RegisterAttachment command) {
        return idempotency.execute(command, Views.AttachmentView.class, 201, () -> workflow.attach(exceptionId, command));
    }
}
