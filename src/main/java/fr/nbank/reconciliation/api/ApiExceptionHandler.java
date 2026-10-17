package fr.nbank.reconciliation.api;

import fr.nbank.reconciliation.audit.AuditService;
import fr.nbank.reconciliation.observability.Correlation;
import fr.nbank.reconciliation.security.ActorContext;
import fr.nbank.reconciliation.security.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final AuditService audit;

    public ApiExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Views.ApiError> api(ApiException ex) {
        if ("ACCESS_DENIED".equals(ex.code()) || "AUTO_APPROVAL_FORBIDDEN".equals(ex.code())) {
            audit.event(actorId(), ex.code(), "HTTP", null, ex.getMessage());
        }
        return ResponseEntity.status(ex.status()).body(error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Views.ApiError> validation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst()
                .orElse("Requête invalide");
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Views.ApiError> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", "JSON invalide"));
    }

    @ExceptionHandler({DataAccessException.class, Exception.class})
    public ResponseEntity<Views.ApiError> fallback(Exception ex) {
        log.error("Erreur interne correlation={}", Correlation.current(), ex);
        return ResponseEntity.internalServerError().body(error("INTERNAL_ERROR", "Erreur interne"));
    }

    private static Views.ApiError error(String code, String message) {
        return new Views.ApiError(code, message, Correlation.current(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static UUID actorId() {
        try {
            return ActorContext.get().id();
        } catch (ApiException ex) {
            return null;
        }
    }
}
