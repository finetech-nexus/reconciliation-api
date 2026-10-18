package fr.nbank.reconciliation.api;

import fr.nbank.reconciliation.sourceconnectors.FineractConnector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/connectors/core-banking", "/api/v1/connectors/fineract"})
public class ConnectorController {

    private final FineractConnector fineract;

    public ConnectorController(FineractConnector fineract) {
        this.fineract = fineract;
    }

    @GetMapping
    public Views.ConnectorView status() {
        return fineract.status();
    }
}
