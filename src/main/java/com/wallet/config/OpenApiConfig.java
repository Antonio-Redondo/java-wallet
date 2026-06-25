package com.wallet.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI metadata for the generated Swagger UI.
 *
 * <p>springdoc scans the controllers and DTOs automatically; this class only
 * supplies the document-level information (title, description, server) shown at
 * the top of the Swagger page. The interactive UI is served at
 * {@code /swagger-ui.html} and the raw spec at {@code /v3/api-docs}.
 *
 * <p>The {@code bearer-jwt} security scheme below adds an <em>Authorize</em>
 * button to Swagger UI: obtain a token from {@code POST /oauth/token}, paste it,
 * and every "Try it out" call is sent with the {@code Authorization: Bearer}
 * header.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Java Wallet API",
                version = "1.0.0",
                description = """
                        A double-entry bookkeeping (wallet) service.

                        Create accounts, then deposit, withdraw or transfer funds via a single
                        `POST /transfers` endpoint - the operation is inferred from which account
                        ids are present (both = transfer, only `to` = deposit, only `from` =
                        withdrawal). Balances are always reconcilable to an append-only ledger,
                        and concurrent transfers are serialised with pessimistic database locks.

                        Money is exact `BigDecimal` (scale 4); supply an optional `idempotencyKey`
                        to make a transfer safe to retry.

                        **Auth:** all `/accounts` and `/transfers` endpoints require an OAuth 2.0
                        JWT bearer token. Get one from `POST /oauth/token` using the
                        client-credentials grant (`wallet.read` gates reads, `wallet.write` gates
                        writes), then click **Authorize** above.""",
                contact = @Contact(name = "Java Wallet"),
                license = @License(name = "MIT")),
        servers = @Server(url = "http://localhost:8080", description = "Local demo server"),
        security = @SecurityRequirement(name = "bearer-jwt"))
@SecurityScheme(
        name = "bearer-jwt",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT obtained from POST /oauth/token (client-credentials grant)")
public class OpenApiConfig {
}
