package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CognitoAuthValidatorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private CognitoAuthValidator validator;
    private Map<String, Object> config;

    @BeforeEach
    void setUp() {
        validator = new CognitoAuthValidator(new JwtClaimsDecoder(mapper), Clock.fixed(NOW, ZoneOffset.UTC));
        config = new HashMap<>();
        config.put("userPoolId", "us-east-1_abc");
        config.put("awsRegion", "us-east-1");
        config.put("defaultAction", "ALLOW");
        config.put("appIdClientRegex", "client-1");
    }

    @Test
    void validJwtBuildsUsernameAndAuthTypeIdentity() {
        Map<String, Object> claims = baseClaims();
        claims.put("cognito:username", "alice");
        Map<String, Object> identity = validator.validate("Bearer " + jwt(claims), config, List.of("127.0.0.1"));
        assertEquals("alice", identity.get("username"));
        assertEquals("abc", identity.get("sub"));
        assertNull(identity.get("cognitoUserPoolId"));
    }

    @Test
    void expiredJwtIs401() {
        Map<String, Object> claims = baseClaims();
        claims.put("exp", NOW.getEpochSecond() - 10);
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + jwt(claims), config, List.of()));
    }

    @Test
    void malformedTokenIs401() {
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer not-a-jwt", config, List.of()));
    }

    @Test
    void missingSubIs401() {
        Map<String, Object> claims = baseClaims();
        claims.remove("sub");
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + jwt(claims), config, List.of()));
    }

    @Test
    void groupsNullVsEmpty() {
        Map<String, Object> missing = validator.validate("Bearer " + jwt(baseClaims()), config, List.of());
        assertNull(missing.get("groups"));

        Map<String, Object> emptyClaims = baseClaims();
        emptyClaims.put("cognito:groups", List.of());
        Map<String, Object> empty = validator.validate("Bearer " + jwt(emptyClaims), config, List.of());
        assertEquals(List.of(), empty.get("groups"));
    }

    private Map<String, Object> baseClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "abc");
        claims.put("iss", "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_abc");
        claims.put("aud", "client-1");
        claims.put("exp", NOW.getEpochSecond() + 3600);
        return claims;
    }

    private String jwt(Map<String, Object> claims) {
        return JwtClaimsDecoder.encode(claims, mapper);
    }
}
