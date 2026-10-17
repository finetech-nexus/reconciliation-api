package fr.nbank.reconciliation.sourceconnectors;

import fr.nbank.reconciliation.api.Views;
import fr.nbank.reconciliation.security.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Connecteur Fineract isolé du moteur de rapprochement.
 * Les chemins HTTP ne sont pas figés ici : ils doivent reprendre l'OpenAPI
 * de la version installée (FIN-API-001 à FIN-API-004).
 */
@Component
public class FineractConnector {

    private final String baseUrl;
    private final String accountsPath;
    private final String entriesPath;
    private final String tenant;

    public FineractConnector(
            @Value("${reconcilia.fineract.base-url:}") String baseUrl,
            @Value("${reconcilia.fineract.accounts-path:}") String accountsPath,
            @Value("${reconcilia.fineract.entries-path:}") String entriesPath,
            @Value("${reconcilia.fineract.tenant:default}") String tenant) {
        this.baseUrl = baseUrl;
        this.accountsPath = accountsPath;
        this.entriesPath = entriesPath;
        this.tenant = tenant;
    }

    public Views.ConnectorView status() {
        Access.require("RECON_ADMIN", "RECON_SERVICE", "RECON_OPERATOR");
        boolean configured = !baseUrl.isBlank() && !accountsPath.isBlank() && !entriesPath.isBlank();
        String message = configured
                ? "Connecteur prêt pour le tenant " + tenant + ". Les chemins proviennent de la configuration, pas d'une version supposée."
                : "Connecteur non configuré. Figer FINERACT_BASE_URL, FINERACT_ACCOUNTS_PATH et FINERACT_ENTRIES_PATH sur l'OpenAPI déployé.";
        return new Views.ConnectorView("FINERACT", configured, message);
    }
}
