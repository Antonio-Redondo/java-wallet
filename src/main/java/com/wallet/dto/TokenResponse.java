package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OAuth 2.0 access-token response (RFC 6749 §5.1).
 *
 * @param accessToken the signed JWT bearer token
 * @param tokenType   always {@code Bearer}
 * @param expiresIn   token lifetime in seconds
 * @param scope       space-delimited scopes granted to the token
 */
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("scope") String scope) {
}
