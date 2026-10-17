package fr.nbank.reconciliation.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.nbank.reconciliation.persistence.ReconciliaStore;
import fr.nbank.reconciliation.security.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;

@Component
public class Idempotency {

    private final ReconciliaStore store;
    private final ObjectMapper json;
    private final HttpServletRequest request;

    public Idempotency(ReconciliaStore store, ObjectMapper json, HttpServletRequest request) {
        this.store = store;
        this.json = json;
        this.request = request;
    }

    public <T> ResponseEntity<T> execute(Object fingerprint, Class<T> type, int success, Supplier<T> action) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank() || key.length() > 120) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "En-tête Idempotency-Key requis");
        }
        String hash = sha(fingerprint);
        var existing = store.findIdem(key.trim());
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash)) {
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Clé d'idempotence déjà utilisée pour une autre commande");
            }
            try {
                T body = json.readValue(existing.get().responseBody(), type);
                return ResponseEntity.status(existing.get().httpStatus()).body(body);
            } catch (JsonProcessingException ex) {
                throw new ApiException(500, "INTERNAL_ERROR", "Erreur interne");
            }
        }
        T body = action.get();
        try {
            int status = success;
            if (body instanceof Views.ImportView view && view.replayed()) {
                status = 200;
            }
            if (body instanceof Views.ImportView view && "REJECTED".equals(view.status()) && view.detail() != null) {
                status = 422;
            }
            store.saveIdem(key.trim(), hash, status, json.writeValueAsString(body));
            return ResponseEntity.status(status).body(body);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, "INTERNAL_ERROR", "Erreur interne");
        }
    }

    private String sha(Object fingerprint) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(fingerprint).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new ApiException(400, "VALIDATION_ERROR", "Commande non hachable");
        }
    }
}
