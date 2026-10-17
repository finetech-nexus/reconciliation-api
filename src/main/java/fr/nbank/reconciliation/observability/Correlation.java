package fr.nbank.reconciliation.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Correlation extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static String current() {
        String value = CURRENT.get();
        return value == null ? "corr-unknown" : value;
    }

    public static String ensure() {
        String value = CURRENT.get();
        if (value == null) {
            value = newId();
            CURRENT.set(value);
            MDC.put("correlationId", value);
        }
        return value;
    }

    private static String newId() {
        return "corr-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming == null || incoming.isBlank() || incoming.length() > 80 ? newId() : incoming.trim();
        CURRENT.set(id);
        MDC.put("correlationId", id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            CURRENT.remove();
            MDC.remove("correlationId");
        }
    }
}
