package fr.nbank.reconciliation.audit;

import fr.nbank.reconciliation.observability.Correlation;
import fr.nbank.reconciliation.persistence.ReconciliaStore;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {

    private final ReconciliaStore store;

    public AuditService(ReconciliaStore store) {
        this.store = store;
    }

    public void event(UUID actorId, String action, String entityType, String entityId, String detail) {
        store.audit(UUID.randomUUID(), actorId, action, entityType, entityId, Correlation.ensure(), detail);
    }
}
