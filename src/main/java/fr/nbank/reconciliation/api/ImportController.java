package fr.nbank.reconciliation.api;

import fr.nbank.reconciliation.ingestion.ImportService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

    private final ImportService imports;
    private final Idempotency idempotency;

    public ImportController(ImportService imports, Idempotency idempotency) {
        this.imports = imports;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<Views.ImportView> create(@Valid @RequestBody Commands.CreateImport command) {
        return idempotency.execute(command, Views.ImportView.class, 201, () -> imports.open(command));
    }

    @GetMapping
    public Views.PageView<Views.ImportView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return imports.list(pageOf(page), sizeOf(size));
    }

    @GetMapping("/{importId}")
    public Views.ImportView get(@PathVariable String importId) {
        return imports.get(importId);
    }

    @GetMapping("/{importId}/records")
    public Views.PageView<Views.RecordView> records(
            @PathVariable String importId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return imports.records(importId, pageOf(page), sizeOf(size));
    }

    @PostMapping(value = "/{importId}/files", consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Views.ImportView> file(
            @PathVariable String importId,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestBody String body) {
        return idempotency.execute(importId + ":" + body, Views.ImportView.class, 200,
                () -> imports.stage(importId, contentType, body));
    }

    @PostMapping("/{importId}/commit")
    public ResponseEntity<Views.ImportView> commit(
            @PathVariable String importId,
            @RequestBody(required = false) Commands.CommitImport command) {
        var fingerprint = java.util.Map.of("importId", importId, "controlTotal", command == null ? "" : String.valueOf(command.controlTotal()));
        return idempotency.execute(fingerprint, Views.ImportView.class, 200,
                () -> imports.commit(importId, command == null ? null : command.controlTotal()));
    }

    private static int pageOf(int page) {
        return Math.max(page, 0);
    }

    private static int sizeOf(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
