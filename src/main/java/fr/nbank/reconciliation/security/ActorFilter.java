package fr.nbank.reconciliation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.nbank.reconciliation.audit.AuditService;
import fr.nbank.reconciliation.observability.Correlation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ActorFilter extends OncePerRequestFilter {

    static final Set<String> ROLES = Set.of(
            "RECON_ADMIN",
            "RECON_OPERATOR",
            "RECON_ANALYST",
            "RECON_APPROVER",
            "RECON_AUDITOR",
            "RECON_SERVICE");

    private final ObjectMapper json;
    private final AuditService audit;

    public ActorFilter(ObjectMapper json, AuditService audit) {
        this.json = json;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String idHeader = request.getHeader("X-Actor-Id");
        String rolesHeader = request.getHeader("X-Actor-Roles");
        if (idHeader == null || idHeader.isBlank() || rolesHeader == null || rolesHeader.isBlank()) {
            deny(response, 401, "UNAUTHENTICATED", "En-têtes X-Actor-Id et X-Actor-Roles requis");
            return;
        }
        UUID actorId;
        try {
            actorId = UUID.fromString(idHeader.trim());
        } catch (IllegalArgumentException ex) {
            deny(response, 400, "VALIDATION_ERROR", "X-Actor-Id doit être un UUID");
            return;
        }
        Set<String> roles = new java.util.LinkedHashSet<>();
        for (String part : rolesHeader.split("[, ]+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!ROLES.contains(part)) {
                deny(response, 400, "VALIDATION_ERROR", "Rôle inconnu");
                return;
            }
            roles.add(part);
        }
        if (roles.isEmpty()) {
            deny(response, 401, "UNAUTHENTICATED", "Aucun rôle fourni");
            return;
        }
        ActorContext.set(new Actor(actorId, Set.copyOf(roles)));
        try {
            chain.doFilter(request, response);
        } finally {
            ActorContext.clear();
        }
    }

    private void deny(HttpServletResponse response, int status, String code, String message) throws IOException {
        audit.event(null, "ACCESS_DENIED", "HTTP", null, message);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("correlationId", Correlation.current());
        body.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());
        json.writeValue(response.getOutputStream(), body);
    }
}
