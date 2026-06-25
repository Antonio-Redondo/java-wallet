package com.wallet.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * OAuth 2.0 security wiring.
 *
 * <h2>Model</h2>
 * The wallet is a stateless <strong>OAuth 2.0 Resource Server</strong>: every
 * request to a protected endpoint must carry a {@code Authorization: Bearer
 * <jwt>} header. The JWT is validated (signature + expiry) by the
 * {@link JwtDecoder}; the token's {@code scope} claim is mapped to Spring
 * {@code SCOPE_*} authorities and checked against the endpoint rules below.
 *
 * <h2>Scopes</h2>
 * <ul>
 *   <li>{@code wallet.write} — required for state-changing calls
 *       ({@code POST /accounts}, {@code POST /transfers}).</li>
 *   <li>{@code wallet.read} — required for read calls
 *       ({@code GET /accounts/**}).</li>
 * </ul>
 *
 * <h2>Keys</h2>
 * To keep the project zero-setup, an RSA key pair is generated in-memory at
 * startup and used both to sign tokens (at {@code /oauth/token}) and to verify
 * them here. In production you would instead point at an external issuer with
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} and remove the
 * token-minting endpoint.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(OAuthClientProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Endpoints that must stay open (token issuance, API docs, H2 console). */
    private static final String[] PUBLIC_PATHS = {
            "/oauth/token",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/h2-console/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless API authenticated by bearer tokens: no CSRF, no session.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, "/accounts", "/transfers")
                        .hasAuthority("SCOPE_wallet.write")
                        .requestMatchers(HttpMethod.GET, "/accounts/**")
                        .hasAuthority("SCOPE_wallet.read")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // The H2 console renders inside a frame; allow same-origin framing.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        log.info("Security enabled: OAuth2 resource server (JWT). Public paths: {}",
                String.join(", ", PUBLIC_PATHS));
        return http.build();
    }

    /**
     * RSA key pair, generated once per JVM, that signs and verifies access
     * tokens. Regenerated on every restart, so tokens do not survive a restart.
     */
    @Bean
    public RSAKey rsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        log.info("Generated in-memory RSA signing key (kid={})", key.getKeyID());
        return key;
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /** Used by the token endpoint to sign newly minted JWTs. */
    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /** Used by the resource server to verify incoming JWTs. */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey) throws Exception {
        return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
    }
}
