package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies the RS256 signature of a token presented to an HTTP API v2 JWT authorizer, the same
 * way real API Gateway does: fetch {@code {issuer}/.well-known/openid-configuration} to discover
 * {@code jwks_uri}, fetch the JWK Set from there, select the key matching the token's {@code kid},
 * and verify. Nothing here is Floci-specific — this is a generic OIDC-relying-party check against
 * whatever issuer a JWT authorizer is configured with (Cognito, Auth0, Okta, ...), not a lookup
 * against Floci's own emulated Cognito keys.
 *
 * <p>Per-issuer JWKS are cached (see {@link #CACHE_TTL}) so repeat requests to the same route
 * don't refetch the discovery document and key set on every call.
 *
 * <p>Fails closed: a network error, an unreachable issuer, an unsupported algorithm ({@code none}
 * included), or no matching key are all treated as verification failure, not as "unverifiable, so
 * allow." A local emulator that can't reach the real issuer must reject, since silently accepting
 * would reopen exactly the unauthenticated-claims gap this class exists to close.
 */
@ApplicationScoped
public class JwtSignatureVerifier {

    private static final Logger LOG = Logger.getLogger(JwtSignatureVerifier.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, CachedJwks> jwksCache = new ConcurrentHashMap<>();

    @Inject
    public JwtSignatureVerifier(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** Package-private for tests: injects a client pointed at a local fixture server. */
    JwtSignatureVerifier(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public static class JwtVerificationException extends Exception {
        public JwtVerificationException(String message) {
            super(message);
        }

        public JwtVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record CachedJwks(Map<String, RSAPublicKey> keysByKid, Instant fetchedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(fetchedAt.plus(CACHE_TTL));
        }
    }

    /**
     * Verifies {@code token}'s signature against {@code issuer}'s published JWKS.
     *
     * @throws JwtVerificationException if the token is not RS256, the issuer's discovery/JWKS
     *                                   endpoints are unreachable or malformed, no key matches the
     *                                   token's {@code kid}, or the signature does not verify.
     */
    public void verify(String token, String issuer) throws JwtVerificationException {
        if (issuer == null || issuer.isBlank()) {
            throw new JwtVerificationException("JWT authorizer has no configured issuer");
        }

        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3) {
            throw new JwtVerificationException("Token is not a well-formed JWT");
        }

        JsonNode header = decodeJson(parts[0])
                .orElseThrow(() -> new JwtVerificationException("Token header is not valid JSON"));
        String alg = header.path("alg").asText("");
        if (!"RS256".equals(alg)) {
            throw new JwtVerificationException(
                    "Unsupported token signing algorithm: " + (alg.isEmpty() ? "none" : alg));
        }
        String kid = header.path("kid").asText(null);
        if (kid == null || kid.isBlank()) {
            throw new JwtVerificationException("Token header has no kid");
        }

        RSAPublicKey key = resolveKey(issuer, kid);
        if (key == null) {
            throw new JwtVerificationException(
                    "No JWKS key matches kid '" + kid + "' for issuer " + issuer);
        }

        if (!signatureValid(parts[0] + "." + parts[1], parts[2], key)) {
            throw new JwtVerificationException("Token signature is invalid");
        }
    }

    private RSAPublicKey resolveKey(String issuer, String kid) throws JwtVerificationException {
        CachedJwks cached = jwksCache.get(issuer);
        if (cached != null && !cached.isExpired()) {
            RSAPublicKey key = cached.keysByKid().get(kid);
            if (key != null) {
                return key;
            }
            // A cached-but-stale key set (e.g. the issuer rotated keys) is worth one refetch
            // before giving up, rather than waiting out the full TTL.
        }

        Map<String, RSAPublicKey> fetched = fetchJwks(issuer);
        jwksCache.put(issuer, new CachedJwks(fetched, Instant.now()));
        return fetched.get(kid);
    }

    private Map<String, RSAPublicKey> fetchJwks(String issuer) throws JwtVerificationException {
        String jwksUri = fetchJwksUri(issuer);
        JsonNode jwks = httpGetJson(jwksUri, "JWKS document");

        JsonNode keys = jwks.path("keys");
        if (!keys.isArray()) {
            throw new JwtVerificationException("JWKS document at " + jwksUri + " has no keys array");
        }

        Map<String, RSAPublicKey> result = new ConcurrentHashMap<>();
        for (JsonNode jwk : keys) {
            String kty = jwk.path("kty").asText("");
            String kid = jwk.path("kid").asText(null);
            if (!"RSA".equals(kty) || kid == null || kid.isBlank()) {
                continue; // non-RSA or unidentifiable keys can't back an RS256 check
            }
            String n = jwk.path("n").asText(null);
            String e = jwk.path("e").asText(null);
            if (n == null || e == null) {
                continue;
            }
            try {
                result.put(kid, toRsaPublicKey(n, e));
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                LOG.debugv("Skipping unparsable JWKS entry for issuer {0}, kid {1}: {2}",
                        issuer, kid, ex.getMessage());
            }
        }
        return result;
    }

    private String fetchJwksUri(String issuer) throws JwtVerificationException {
        String discoveryUrl = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
        JsonNode discovery = httpGetJson(discoveryUrl, "OIDC discovery document");
        String jwksUri = discovery.path("jwks_uri").asText(null);
        if (jwksUri == null || jwksUri.isBlank()) {
            throw new JwtVerificationException(
                    "OIDC discovery document at " + discoveryUrl + " has no jwks_uri");
        }
        return jwksUri;
    }

    private JsonNode httpGetJson(String url, String description) throws JwtVerificationException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new JwtVerificationException("Invalid " + description + " URL: " + url, e);
        }

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new JwtVerificationException(
                        "Fetching " + description + " from " + url + " returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (JwtVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtVerificationException("Failed to fetch " + description + " from " + url, e);
        }
    }

    private RSAPublicKey toRsaPublicKey(String n, String e) throws GeneralSecurityException {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(padBase64(n)));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(padBase64(e)));
        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private boolean signatureValid(String signingInput, String encodedSignature, RSAPublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getUrlDecoder().decode(padBase64(encodedSignature)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            LOG.debugv("JWT signature check failed: {0}", e.getMessage());
            return false;
        }
    }

    private java.util.Optional<JsonNode> decodeJson(String base64UrlSegment) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(base64UrlSegment));
            JsonNode node = objectMapper.readTree(decoded);
            return node != null && node.isObject() ? java.util.Optional.of(node) : java.util.Optional.empty();
        } catch (IllegalArgumentException | java.io.IOException e) {
            return java.util.Optional.empty();
        }
    }

    private static String padBase64(String base64) {
        return switch (base64.length() % 4) {
            case 2 -> base64 + "==";
            case 3 -> base64 + "=";
            default -> base64;
        };
    }
}
