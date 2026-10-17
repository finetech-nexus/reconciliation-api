package fr.nbank.reconciliation.observability;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI reconciliaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Reconcilia Bank API")
                .version("1.0.0")
                .description("API v1 du rapprochement bancaire. Montants en décimal exact JSON. Horodatages UTC ISO-8601."));
    }

    @Bean
    public OperationCustomizer correlationHeaders() {
        return (operation, handler) -> {
            operation.addParametersItem(header("X-Correlation-Id", false));
            operation.addParametersItem(header("X-Actor-Id", true));
            operation.addParametersItem(header("X-Actor-Roles", true));
            operation.addParametersItem(header("Idempotency-Key", false));
            return operation;
        };
    }

    private static Parameter header(String name, boolean required) {
        return new Parameter().in("header").name(name).required(required).schema(new StringSchema());
    }
}
