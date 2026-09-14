package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Verifies a Cognito user pool bearer token the same way real AppSync does: a valid RS256
 * signature from the pool's own signing key, a {@code kid} that names that key, and the pool's
 * real issuer, on top of the existing expiry/audience checks.
 *
 * <p>The signing key is read directly off the {@link UserPool} model rather than fetched over
 * HTTP from the emulator's own {@code .well-known/jwks.json}: Floci's Cognito emulator and AppSync
 * run in the same process, so there is a real key to read in memory, and looping back over HTTP to
 * ourselves would only add a network dependency a unit test would otherwise need to fake.
 */
@ApplicationScoped
public class CognitoAuthValidator {

    private final JwtClaimsDecoder jwtClaimsDecoder;
    private final CognitoService cognitoService;
    private final Clock clock;

    @Inject
    public CognitoAuthValidator(JwtClaimsDecoder jwtClaimsDecoder, CognitoService cognitoService, Clock clock) {
        this.jwtClaimsDecoder = jwtClaimsDecoder;
        this.cognitoService = cognitoService;
        this.clock = clock;
    }

    public Map<String, Object> validate(String authorization, Map<String, Object> userPoolConfig, List<String> sourceIp) {
        Map<String, Object> claims = jwtClaimsDecoder.decode(authorization)
                .orElseThrow(AppSyncAuth::unauthorized);
        verifySignature(authorization, userPoolConfig);
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

    /**
     * Fails closed: a missing/blank pool id, a pool this emulator never issued
     * ({@code AwsException} from {@code describeUserPool}), an unsupported {@code alg}, a {@code kid}
     * that does not name the pool's current signing key, or a signature that does not verify are all
     * treated as verification failure, never as "unverifiable, so allow."
     */
    private void verifySignature(String authorization, Map<String, Object> userPoolConfig) {
        String poolId = userPoolConfig == null ? null : coerceString(userPoolConfig.get("userPoolId"), null);
        if (poolId == null) {
            throw AppSyncAuth.unauthorized();
        }
        UserPool pool;
        try {
            pool = cognitoService.describeUserPool(poolId);
        } catch (AwsException e) {
            throw AppSyncAuth.unauthorized();
        }
        Map<String, Object> header = jwtClaimsDecoder.decodeHeader(authorization)
                .orElseThrow(AppSyncAuth::unauthorized);
        if (!"RS256".equals(header.get("alg"))) {
            throw AppSyncAuth.unauthorized();
        }
        String kid = coerceString(header.get("kid"), null);
        if (kid == null || !kid.equals(pool.getSigningKeyId())) {
            throw AppSyncAuth.unauthorized();
        }
        String token = jwtClaimsDecoder.rawToken(authorization).orElseThrow(AppSyncAuth::unauthorized);
        int lastDot = token.lastIndexOf('.');
        String signingInput = token.substring(0, lastDot);
        byte[] signature = Base64.getUrlDecoder().decode(pad(token.substring(lastDot + 1)));
        if (!signatureValid(signingInput, signature, pool)) {
            throw AppSyncAuth.unauthorized();
        }
    }

    private boolean signatureValid(String signingInput, byte[] signature, UserPool pool) {
        try {
            byte[] encodedKey = Base64.getDecoder().decode(pool.getSigningPublicKey());
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encodedKey));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private static String pad(String value) {
        int rem = value.length() % 4;
        return rem == 0 ? value : value + "=".repeat(4 - rem);
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

    /**
     * The real emulator issuer for the configured pool ({@code cognitoService.getIssuer(poolId)}),
     * unless the config carries an explicit override. There is no AWS-style
     * {@code https://cognito-idp.<region>.amazonaws.com/<poolId>} guess here any more: Floci's own
     * Cognito emulator never signs a token with that issuer, so comparing against it could never
     * match a genuine emulator-issued token.
     */
    private String expectedIssuer(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        String issuer = coerceString(config.get("issuer"), null);
        if (issuer != null) {
            return issuer;
        }
        String poolId = coerceString(config.get("userPoolId"), null);
        return poolId == null ? null : cognitoService.getIssuer(poolId);
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
