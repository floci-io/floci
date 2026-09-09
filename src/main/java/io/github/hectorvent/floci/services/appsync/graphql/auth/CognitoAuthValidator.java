package io.github.hectorvent.floci.services.appsync.graphql.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class CognitoAuthValidator {

    private final JwtClaimsDecoder jwtClaimsDecoder;
    private final Clock clock;

    @Inject
    public CognitoAuthValidator(JwtClaimsDecoder jwtClaimsDecoder, Clock clock) {
        this.jwtClaimsDecoder = jwtClaimsDecoder;
        this.clock = clock;
    }

    public Map<String, Object> validate(String authorization, Map<String, Object> userPoolConfig, List<String> sourceIp) {
        Map<String, Object> claims = jwtClaimsDecoder.decode(authorization)
                .orElseThrow(AppSyncAuth::unauthorized);
        if (claims.get("sub") == null || String.valueOf(claims.get("sub")).isBlank()) {
            throw AppSyncAuth.unauthorized();
        }
        if (!unexpired(claims)) {
            throw AppSyncAuth.unauthorized();
        }
        if (!issuerMatches(claims, userPoolConfig)) {
            throw AppSyncAuth.unauthorized();
        }
        if (!audienceMatches(claims, userPoolConfig)) {
            throw AppSyncAuth.unauthorized();
        }
        String defaultAction = coerceString(userPoolConfig == null ? null : userPoolConfig.get("defaultAction"), "ALLOW");
        return IdentityBuilder.cognito(claims, sourceIp, defaultAction);
    }

    boolean matchesProvider(Map<String, Object> claims, Map<String, Object> userPoolConfig) {
        return claims.get("sub") != null
                && unexpired(claims)
                && issuerMatches(claims, userPoolConfig)
                && audienceMatches(claims, userPoolConfig);
    }

    private boolean unexpired(Map<String, Object> claims) {
        Long exp = asLong(claims.get("exp"));
        if (exp == null) {
            return false;
        }
        return exp > clock.instant().getEpochSecond();
    }

    private boolean issuerMatches(Map<String, Object> claims, Map<String, Object> config) {
        String iss = coerceString(claims.get("iss"), null);
        if (iss == null) {
            return false;
        }
        String expected = expectedIssuer(config);
        return expected != null && expected.equals(iss);
    }

    static String expectedIssuer(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        String issuer = coerceString(config.get("issuer"), null);
        if (issuer != null) {
            return issuer;
        }
        String poolId = coerceString(config.get("userPoolId"), null);
        String region = coerceString(config.get("awsRegion"), null);
        if (poolId == null || region == null) {
            return null;
        }
        return "https://cognito-idp." + region + ".amazonaws.com/" + poolId;
    }

    private boolean audienceMatches(Map<String, Object> claims, Map<String, Object> config) {
        if (config == null) {
            return true;
        }
        String regex = coerceString(config.get("appIdClientRegex"), null);
        String clientId = coerceString(config.get("clientId"), null);
        if (regex == null && clientId == null) {
            return true;
        }
        if (regex != null && clientMatches(claims, value -> Pattern.compile(regex).matcher(value).matches())) {
            return true;
        }
        return clientId != null && clientMatches(claims, clientId::equals);
    }

    static boolean clientMatches(Map<String, Object> claims, java.util.function.Predicate<String> matcher) {
        Object aud = claims.get("aud");
        if (aud instanceof String s && matcher.test(s)) {
            return true;
        }
        if (aud instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && matcher.test(String.valueOf(item))) {
                    return true;
                }
            }
        }
        Object clientId = claims.get("client_id");
        return clientId != null && matcher.test(String.valueOf(clientId));
    }

    static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static String coerceString(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }
}
