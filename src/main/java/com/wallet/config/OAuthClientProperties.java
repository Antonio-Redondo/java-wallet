package com.wallet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the self-contained OAuth 2.0 client-credentials flow.
 *
 * <p>For this demo a single static client is configured in
 * {@code application.properties}. In a real deployment clients would live in an
 * external Authorization Server (Keycloak, Auth0, Spring Authorization Server,
 * ...) and this service would only need {@code spring.security.oauth2
 * .resourceserver.jwt.issuer-uri} — the token-minting half here would be deleted.
 *
 * @param clientId         the demo client identifier
 * @param clientSecret     the demo client secret (plaintext for the demo only)
 * @param scopes           space-delimited scopes the client may be granted
 * @param tokenTtlSeconds  lifetime of an issued access token, in seconds
 * @param issuer           the {@code iss} claim stamped on issued tokens
 */
@ConfigurationProperties(prefix = "wallet.security")
public record OAuthClientProperties(
        String clientId,
        String clientSecret,
        String scopes,
        long tokenTtlSeconds,
        String issuer) {
}
