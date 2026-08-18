package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcAuthValidatorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private OidcAuthValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OidcAuthValidator(new JwtClaimsDecoder(mapper), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void soleModeSkipsIssuer() {
        Map<String, Object> config = config("https://configured-issuer", "my-client");
        Map<String, Object> claims = baseClaims("https://other-issuer", "my-client");
        Map<String, Object> identity = validator.validate("Bearer " + jwt(claims), config, true);
        assertEquals("sub-1", identity.get("sub"));
        assertEquals("https://other-issuer", identity.get("issuer"));
        assertTrue(!identity.containsKey("sourceIp"));
    }

    @Test
    void multiModeEnforcesIssuer() {
        Map<String, Object> config = config("https://configured-issuer", "my-client");
        Map<String, Object> claims = baseClaims("https://other-issuer", "my-client");
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + jwt(claims), config, false));
    }

    @Test
    void iatTtlExceededIs401() {
        Map<String, Object> config = config("https://issuer", "my-client");
        config.put("iatTTL", 60);
        Map<String, Object> claims = baseClaims("https://issuer", "my-client");
        claims.put("iat", NOW.getEpochSecond() - 120);
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + jwt(claims), config, true));
    }

    @Test
    void clientIdRegexMatchesAzp() {
        Map<String, Object> config = config("https://issuer", "client-.*");
        Map<String, Object> claims = baseClaims("https://issuer", "other");
        claims.remove("aud");
        claims.put("azp", "client-99");
        Map<String, Object> identity = validator.validate("Bearer " + jwt(claims), config, true);
        assertEquals("sub-1", identity.get("sub"));
    }

    @Test
    void clientIdMismatchIs401() {
        Map<String, Object> config = config("https://issuer", "client-1");
        Map<String, Object> claims = baseClaims("https://issuer", "nope");
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + jwt(claims), config, true));
    }

    private Map<String, Object> config(String issuer, String clientId) {
        Map<String, Object> config = new HashMap<>();
        config.put("issuer", issuer);
        config.put("clientId", clientId);
        return config;
    }

    private Map<String, Object> baseClaims(String iss, String aud) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "sub-1");
        claims.put("iss", iss);
        claims.put("aud", aud);
        claims.put("iat", NOW.getEpochSecond() - 10);
        claims.put("exp", NOW.getEpochSecond() + 3600);
        return claims;
    }

    private String jwt(Map<String, Object> claims) {
        return JwtClaimsDecoder.encode(claims, mapper);
    }
}
