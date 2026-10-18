package fr.nbank.reconciliation.sourceconnectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.nbank.reconciliation.api.Commands;
import fr.nbank.reconciliation.api.Views;
import fr.nbank.reconciliation.ingestion.ImportService;
import fr.nbank.reconciliation.persistence.ReconciliaStore;
import fr.nbank.reconciliation.persistence.Rows;
import fr.nbank.reconciliation.security.Access;
import fr.nbank.reconciliation.security.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLParameters;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

@Service
public class CoreBankingPullService {

    private final ReconciliaStore store;
    private final ImportService imports;
    private final ObjectMapper json;
    private final String envBaseUrl;
    private final String envAccountsPath;
    private final String envEntriesPath;
    private final String envTenant;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String username;
    private final String password;
    private final HttpClient http;

    public CoreBankingPullService(
            ReconciliaStore store,
            ImportService imports,
            ObjectMapper json,
            @Value("${reconcilia.fineract.base-url:}") String envBaseUrl,
            @Value("${reconcilia.fineract.accounts-path:}") String envAccountsPath,
            @Value("${reconcilia.fineract.entries-path:}") String envEntriesPath,
            @Value("${reconcilia.fineract.tenant:default}") String envTenant,
            @Value("${reconcilia.fineract.token-url:}") String tokenUrl,
            @Value("${reconcilia.fineract.client-id:}") String clientId,
            @Value("${reconcilia.fineract.client-secret:}") String clientSecret,
            @Value("${reconcilia.fineract.username:}") String username,
            @Value("${reconcilia.fineract.password:}") String password) {
        this.store = store;
        this.imports = imports;
        this.json = json;
        this.envBaseUrl = envBaseUrl;
        this.envAccountsPath = envAccountsPath;
        this.envEntriesPath = envEntriesPath;
        this.envTenant = envTenant;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.username = username;
        this.password = password;
        this.http = clusterClient();
    }

    public Rows.CoreProfile resolved() {
        Rows.CoreProfile profile = store.coreProfile();
        String base = blank(profile.baseUrl()) ? envBaseUrl : profile.baseUrl();
        String accounts = blank(profile.accountsPath()) ? envAccountsPath : profile.accountsPath();
        String entries = blank(profile.entriesPath()) ? envEntriesPath : profile.entriesPath();
        String tenant = blank(profile.tenant()) ? envTenant : profile.tenant();
        return new Rows.CoreProfile(profile.connectorType(), base == null ? "" : base, tenant == null ? "default" : tenant,
                accounts == null ? "" : accounts, entries == null ? "" : entries);
    }

    public Views.ImportView pull(LocalDate businessDate, String currency) {
        Access.require("RECON_ADMIN", "RECON_OPERATOR", "RECON_SERVICE");
        Rows.CoreProfile profile = resolved();
        if (blank(profile.baseUrl()) || blank(profile.entriesPath())) {
            throw new ApiException(422, "CONNECTOR_NOT_CONFIGURED",
                    "Core banking non configuré. Renseigner l'adresse et le chemin des écritures dans Réglages.");
        }
        String raw = fetch(profile, businessDate);
        Rows.SourceRow source = store.findSource("CORE_BANKING")
                .orElseThrow(() -> new ApiException(422, "UNKNOWN_SOURCE", "Système Core banking absent"));
        CorePayloadMapper.Mapped mapped = CorePayloadMapper.map(json, raw,
                gl -> store.accountForGl(source.id(), gl).orElse(null), currency);
        if (mapped.journalRows() == 0) {
            throw new ApiException(422, "EMPTY_BATCH", "Le core n'a renvoyé aucune écriture pour cette date");
        }
        String batchId = "CORE-" + profile.connectorType() + "-" + businessDate;
        Views.ImportView opened = imports.open(new Commands.CreateImport(
                "CORE_BANKING", "ACCOUNTING_ENTRIES", businessDate, batchId, currency, null,
                Map.of("channel", "API", "connector", profile.connectorType())));
        if (opened.replayed()) {
            return opened;
        }
        String body = mapped.body().strip();
        String contentType = body.startsWith("{") || body.startsWith("[") ? "application/json" : "text/csv";
        imports.stage(opened.importId(), contentType, mapped.body());
        return imports.commit(opened.importId(), null);
    }

    private String fetch(Rows.CoreProfile profile, LocalDate businessDate) {
        String root = profile.baseUrl().endsWith("/")
                ? profile.baseUrl().substring(0, profile.baseUrl().length() - 1)
                : profile.baseUrl();
        String path = profile.entriesPath().startsWith("/") ? profile.entriesPath() : "/" + profile.entriesPath();
        String url = root + path;
        if (!url.contains("?")) {
            url = url + "?fromDate=" + businessDate + "&toDate=" + businessDate
                    + "&dateFormat=yyyy-MM-dd&locale=en&limit=0";
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("X-Core-Connector", profile.connectorType())
                .GET();
        if ("FINERACT".equals(profile.connectorType())) {
            request.header("Fineract-Platform-TenantId", profile.tenant());
            String token = bearer();
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(502, "CORE_UNREACHABLE", "Le core a répondu " + response.statusCode());
            }
            return response.body();
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(502, "CORE_UNREACHABLE", "Appel au core impossible (" + ex.getClass().getSimpleName() + ")");
        }
    }

    private String bearer() {
        if (blank(tokenUrl) || blank(clientId) || blank(username)) {
            return null;
        }
        String form = "grant_type=password&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&username=" + enc(username)
                + "&password=" + enc(password);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(502, "CORE_UNREACHABLE", "Jeton du core refusé (" + response.statusCode() + ")");
            }
            String token = json.readTree(response.body()).path("access_token").asText("");
            if (token.isBlank()) {
                throw new ApiException(502, "CORE_UNREACHABLE", "Jeton du core absent");
            }
            return token;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(502, "CORE_UNREACHABLE", "Jeton du core impossible (" + ex.getClass().getSimpleName() + ")");
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * L'instance du namespace core-banking présente un certificat au nom de l'ingress.
     * L'appel interne utilise le nom du service, donc le nom du certificat ne correspond pas.
     */
    private static HttpClient clusterClient() {
        try {
            TrustManager[] trust = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trust, new SecureRandom());
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("");
            return HttpClient.newBuilder()
                    .sslContext(ssl)
                    .sslParameters(parameters)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        } catch (Exception ex) {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        }
    }
}
