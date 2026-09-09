package io.github.hectorvent.floci.services.appsync.graphql.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class OidcAuthValidator {

    private final JwtClaimsDecoder jwtClaimsDecoder;
    private final Clock clock;

    @Inject
    public OidcAuthValidator(JwtClaimsDecoder jwtClaimsDecoder, Clock clock) {
        this.jwtClaimsDecoder = jwtClaimsDecoder;
        this.clock = clock;
    }

    public Map<String, Object> validate(
            String authorization,
            Map<String, Object> oidcConfig,
            boolean skipIssuer
    ) {
        Map<String, Object> claims = jwtClaimsDecoder.decode(authorization)
                .orElseThrow(AppSyncAuth::unauthorized);
        if (claims.get("sub") == null || String.valueOf(claims.get("sub")).isBlank()) {
            throw AppSyncAuth.unauthorized();
        }
        if (!timestampsValid(claims, oidcConfig)) {
            throw AppSyncAuth.unauthorized();
        }
        if (!skipIssuer && !issuerMatches(claims, oidcConfig)) {
            throw AppSyncAuth.unauthorized();
        }
        if (!clientIdMatches(claims, oidcConfig)) {
            throw AppSyncAuth.unauthorized();
        }
        return IdentityBuilder.oidc(claims);
    }

    boolean matchesProvider(Map<String, Object> claims, Map<String, Object> oidcConfig, boolean skipIssuer) {
        return claims.get("sub") != null
                && timestampsValid(claims, oidcConfig)
                && (skipIssuer || issuerMatches(claims, oidcConfig))
                && clientIdMatches(claims, oidcConfig);
    }

    private boolean timestampsValid(Map<String, Object> claims, Map<String, Object> config) {
        long now = clock.instant().getEpochSecond();
        Long exp = CognitoAuthValidator.asLong(claims.get("exp"));
        Long iat = CognitoAuthValidator.asLong(claims.get("iat"));
        if (exp == null || iat == null) {
            return false;
        }
        if (exp <= now || iat > now) {
            return false;
        }
        Long iatTtl = CognitoAuthValidator.asLong(config == null ? null : config.get("iatTTL"));
        if (iatTtl != null && now - iat > iatTtl) {
            return false;
        }
        Long authTtl = CognitoAuthValidator.asLong(config == null ? null : config.get("authTTL"));
        if (authTtl != null) {
            Long authTime = CognitoAuthValidator.asLong(claims.get("auth_time"));
            long start = authTime != null ? authTime : iat;
            if (now - start > authTtl) {
                return false;
            }
        }
        return true;
    }

    private boolean issuerMatches(Map<String, Object> claims, Map<String, Object> config) {
        String iss = CognitoAuthValidator.coerceString(claims.get("iss"), null);
        String expected = config == null ? null : CognitoAuthValidator.coerceString(config.get("issuer"), null);
        return iss != null && expected != null && expected.equals(iss);
    }

    private boolean clientIdMatches(Map<String, Object> claims, Map<String, Object> config) {
        if (config == null) {
            return true;
        }
        String clientId = CognitoAuthValidator.coerceString(config.get("clientId"), null);
        if (clientId == null) {
            return true;
        }
        Pattern pattern = Pattern.compile(clientId);
        return CognitoAuthValidator.clientMatches(claims, value -> pattern.matcher(value).matches())
                || azpMatches(claims, pattern);
    }

    private static boolean azpMatches(Map<String, Object> claims, Pattern pattern) {
        Object azp = claims.get("azp");
        return azp != null && pattern.matcher(String.valueOf(azp)).matches();
    }
}
