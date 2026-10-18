package fr.nbank.reconciliation.sourceconnectors;

import fr.nbank.reconciliation.api.Views;
import fr.nbank.reconciliation.persistence.Rows;
import fr.nbank.reconciliation.security.Access;
import org.springframework.stereotype.Component;

/**
 * État du connecteur Core banking. Le type (Fineract ou T24) vient des réglages.
 * Les chemins HTTP ne sont pas figés : ils sont saisis pour l'instance installée.
 */
@Component
public class FineractConnector {

    private final CoreBankingPullService core;

    public FineractConnector(CoreBankingPullService core) {
        this.core = core;
    }

    public Views.ConnectorView status() {
        Access.require("RECON_ADMIN", "RECON_SERVICE", "RECON_OPERATOR");
        Rows.CoreProfile profile = core.resolved();
        boolean configured = !profile.baseUrl().isBlank() && !profile.entriesPath().isBlank();
        String message = configured
                ? "Core banking prêt (" + profile.connectorType() + ", tenant " + profile.tenant() + ")."
                : "Core banking non configuré. Choisir Fineract ou T24 et renseigner l'adresse dans Réglages.";
        return new Views.ConnectorView("CORE_BANKING", configured, message);
    }
}
