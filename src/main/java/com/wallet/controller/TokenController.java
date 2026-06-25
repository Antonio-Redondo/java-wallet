package com.wallet.controller;

import com.wallet.config.OAuthClientProperties;
import com.wallet.dto.TokenResponse;
import com.wallet.exception.OAuthTokenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Self-contained OAuth 2.0 token endpoint implementing the
 * <strong>client-credentials grant</strong> (RFC 6749 §4.4).
 *
 * <p>A client POSTs its {@code client_id}/{@code client_secret} (and optionally a
 * subset of {@code scope}) and receives a signed JWT to present as a bearer token
 * on subsequent API calls. This stands in for a real Authorization Server so the
 * demo needs no external infrastructure; see {@link OAuthClientProperties}.
 */
@RestController
@RequestMapping("/oauth")
@Tag(name = "OAuth", description = "Obtain an access token (client-credentials grant)")
public class TokenController {

    private static final Logger log = LoggerFactory.getLogger(TokenController.class);
    private static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";

    private final JwtEncoder jwtEncoder;
    private final OAuthClientProperties props;

    public TokenController(JwtEncoder jwtEncoder, OAuthClientProperties props) {
        this.jwtEncoder = jwtEncoder;
        this.props = props;
    }

    @Operation(
            summary = "Issue an access token (client-credentials grant)",
            description = """
                    Exchange client credentials for a signed JWT bearer token.
                    Send as `application/x-www-form-urlencoded`:
                    `grant_type=client_credentials&client_id=...&client_secret=...`.
                    Optionally narrow `scope` to a subset of the client's allowed scopes.""")
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public TokenResponse token(
            @RequestParam("grant_type") String grantType,
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam(value = "scope", required = false) String requestedScope) {

        log.debug("Token request: grant_type='{}', client_id='{}', scope='{}'",
                grantType, clientId, requestedScope);

        if (!GRANT_CLIENT_CREDENTIALS.equals(grantType)) {
            log.warn("Token request rejected: unsupported grant_type '{}'", grantType);
            throw new OAuthTokenException("unsupported_grant_type",
                    "only the client_credentials grant is supported", HttpStatus.BAD_REQUEST);
        }
        if (!props.clientId().equals(clientId) || !props.clientSecret().equals(clientSecret)) {
            log.warn("Token request rejected: invalid credentials for client_id '{}'", clientId);
            throw new OAuthTokenException("invalid_client",
                    "client authentication failed", HttpStatus.UNAUTHORIZED);
        }

        String grantedScopes = resolveScopes(clientId, requestedScope);

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(props.tokenTtlSeconds()))
                .subject(clientId)
                .claim("scope", grantedScopes)
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        log.info("Issued access token for client '{}' [scopes: {}], ttl {}s",
                clientId, grantedScopes, props.tokenTtlSeconds());
        return new TokenResponse(token, "Bearer", props.tokenTtlSeconds(), grantedScopes);
    }

    /**
     * Default to all scopes the client is allowed; if the request narrows the
     * scope, every requested value must be within the allowed set.
     */
    private String resolveScopes(String clientId, String requestedScope) {
        Set<String> allowed = splitScopes(props.scopes());
        if (requestedScope == null || requestedScope.isBlank()) {
            return String.join(" ", allowed);
        }
        Set<String> requested = splitScopes(requestedScope);
        if (!allowed.containsAll(requested)) {
            requested.removeAll(allowed);
            log.warn("Token request rejected: client '{}' requested unauthorized scope(s) {}",
                    clientId, requested);
            throw new OAuthTokenException("invalid_scope",
                    "requested scope exceeds what this client may be granted",
                    HttpStatus.BAD_REQUEST);
        }
        return String.join(" ", requested);
    }

    private static Set<String> splitScopes(String scopes) {
        return Arrays.stream(scopes.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
