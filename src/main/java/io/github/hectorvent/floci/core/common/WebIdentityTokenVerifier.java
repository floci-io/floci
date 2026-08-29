package io.github.hectorvent.floci.core.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Verifies RS256-signed web-identity JWTs against a Floci-known OIDC issuer.
 *
 * <p>Deliberately hand-rolled rather than pulling in a JOSE library: Floci already signs Cognito
 * JWTs with plain {@code java.security} primitives, and this only needs the one algorithm EKS IRSA
 * uses.
 *
 * <p>Two-stage design, which is what keeps existing permissive behaviour intact. {@link #peekIssuer}
 * reads the {@code iss} claim <em>without</em> verifying anything, so a caller can ask "is this a
 * token Floci issued?" before deciding to enforce. Only when the issuer resolves to a known key does
 * {@link #verify} run and reject on any failure.
 */
@ApplicationScoped
public class WebIdentityTokenVerifier {

    private static final Logger LOG = Logger.getLogger(WebIdentityTokenVerifier.class);

    /** Tolerance for clock drift between Floci and a token minted elsewhere. */
    private static final long CLOCK_SKEW_SECONDS = 60;

    private final ObjectMapper objectMapper;

    @Inject
    public WebIdentityTokenVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Thrown when a token claiming a Floci-known issuer fails verification. */
    public static class InvalidTokenException extends Exception {
        public InvalidTokenException(String message) {
            super(message);
        }
    }

    /**
     * Reads the {@code iss} claim without verifying the signature. Returns empty when {@code token}
     * is not a parseable JWT at all — the caller then treats it as an opaque third-party token.
     */
    public Optional<String> peekIssuer(String token) {
        return parseClaims(token).map(claims -> claims.path("iss").asText(null))
                .filter(iss -> iss != null && !iss.isBlank());
    }

    /**
     * Fully verifies {@code token}: RS256 signature against {@code publicKey}, {@code iss} equal to
     * {@code expectedIssuer}, {@code aud} containing {@code requiredAudience}, and {@code exp}/
     * {@code nbf} within {@link #CLOCK_SKEW_SECONDS}.
     *
     * <p>Claim comparisons are exact and case-sensitive, matching AWS treatment of OIDC claims.
     */
    public WebIdentityToken verify(String token, RSAPublicKey publicKey, String expectedIssuer,
                                   String requiredAudience) throws InvalidTokenException {
        // Limit -1 keeps trailing empty segments, so an unsigned "header.payload." token is seen as
        // three parts and rejected by the algorithm check below rather than as malformed.
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException("The web identity token is not a well-formed JWT");
        }

        JsonNode header = decodeJson(parts[0])
                .orElseThrow(() -> new InvalidTokenException("The web identity token header is not valid JSON"));
        String alg = header.path("alg").asText("");
        if (!"RS256".equals(alg)) {
            throw new InvalidTokenException("Unsupported web identity token algorithm: "
                    + (alg.isEmpty() ? "none" : alg));
        }

        if (!signatureValid(parts[0] + "." + parts[1], parts[2], publicKey)) {
            throw new InvalidTokenException("The web identity token signature is invalid");
        }

        JsonNode claims = decodeJson(parts[1])
                .orElseThrow(() -> new InvalidTokenException("The web identity token payload is not valid JSON"));

        String issuer = claims.path("iss").asText(null);
        if (issuer == null || !issuer.equals(expectedIssuer)) {
            throw new InvalidTokenException("The web identity token issuer does not match the "
                    + "OIDC provider: " + issuer);
        }

        List<String> audiences = readAudiences(claims);
        if (!audiences.contains(requiredAudience)) {
            throw new InvalidTokenException("The web identity token audience does not include "
                    + requiredAudience);
        }

        verifyValidityWindow(claims);

        String subject = claims.path("sub").asText(null);
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("The web identity token has no subject claim");
        }

        return new WebIdentityToken(issuer, subject, audiences);
    }

    private void verifyValidityWindow(JsonNode claims) throws InvalidTokenException {
        long now = Instant.now().getEpochSecond();

        JsonNode exp = claims.get("exp");
        if (exp == null || !exp.isNumber()) {
            throw new InvalidTokenException("The web identity token has no expiration claim");
        }
        if (now > exp.asLong() + CLOCK_SKEW_SECONDS) {
            throw new InvalidTokenException("The web identity token has expired");
        }

        JsonNode nbf = claims.get("nbf");
        if (nbf != null && nbf.isNumber() && now + CLOCK_SKEW_SECONDS < nbf.asLong()) {
            throw new InvalidTokenException("The web identity token is not yet valid");
        }
    }

    private boolean signatureValid(String signingInput, String encodedSignature, RSAPublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getUrlDecoder().decode(encodedSignature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A malformed signature segment or a key/algorithm mismatch both mean "not verifiable",
            // which is a rejection rather than a server fault.
            LOG.debugv("Web identity token signature check failed: {0}", e.getMessage());
            return false;
        }
    }

    private List<String> readAudiences(JsonNode claims) {
        List<String> audiences = new ArrayList<>();
        JsonNode aud = claims.get("aud");
        if (aud == null) {
            return audiences;
        }
        if (aud.isTextual()) {
            audiences.add(aud.asText());
        } else if (aud.isArray()) {
            for (JsonNode entry : aud) {
                if (entry.isTextual()) {
                    audiences.add(entry.asText());
                }
            }
        }
        return audiences;
    }

    private Optional<JsonNode> parseClaims(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        // Limit -1 for the same reason as verify(): without it an unsigned "header.payload." token
        // splits into two parts, so its issuer would go unseen and the caller would treat a token
        // that names a Floci issuer as opaque, skipping validation entirely.
        String[] parts = token.split("\\.", -1);
        return parts.length == 3 ? decodeJson(parts[1]) : Optional.empty();
    }

    private Optional<JsonNode> decodeJson(String base64UrlSegment) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(base64UrlSegment);
            JsonNode node = objectMapper.readTree(decoded);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (IllegalArgumentException | IOException e) {
            // Not base64url, or not JSON. Callers treat an empty result as "unparseable segment",
            // which is a normal outcome when the token came from something other than Floci.
            LOG.debugv("Could not decode web identity token segment: {0}", e.getMessage());
            return Optional.empty();
        }
    }
}
