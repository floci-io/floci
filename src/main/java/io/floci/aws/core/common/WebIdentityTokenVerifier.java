package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies RS256-signed web-identity JWTs against a Floci-known OIDC issuer.
 *
 * <p>Deliberately hand-rolled rather than pulling in a JOSE library: Floci already signs Cognito
 * JWTs with plain {@code java.security} primitives, and this only needs the one algorithm EKS IRSA
 * uses.
 *
 * <p>Two-stage design lets the caller select a trusted key before verification. {@link #peekIssuer}
 * reads the {@code iss} claim <em>without</em> verifying anything. The caller must reject tokens
 * when the issuer does not resolve to a trusted key; {@link #verify} then rejects any verification
 * failure for a known issuer.
 */
@ApplicationScoped
public class WebIdentityTokenVerifier {

    private static final Logger LOG = Logger.getLogger(WebIdentityTokenVerifier.class);

    /** Tolerance for clock drift between Floci and a token minted elsewhere. */
    private static final long CLOCK_SKEW_SECONDS = 60;
    /** The namespace AWS publishes a token's session tags under, whichever format they take. */
    private static final String TAGS_CLAIM = "https://aws.amazon.com/tags";
    private static final String FLATTENED_TAG_CLAIM = TAGS_CLAIM + "/principal_tags/";
    /** The nested member, and the flattened claim, naming the keys to set as transitive. */
    private static final String TRANSITIVE_KEYS = "transitive_tag_keys";
    private static final String FLATTENED_TRANSITIVE_CLAIM = TAGS_CLAIM + "/" + TRANSITIVE_KEYS;
    /** What a transitive tag key claim of any other shape is refused with, Floci's own wording. */
    static final String TRANSITIVE_KEYS_MALFORMED =
            "The web identity token's transitive tag keys do not read as an array of strings";

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
     * Thrown when a token is past its {@code exp} claim. STS reports this as
     * {@code ExpiredTokenException} rather than {@code InvalidIdentityToken}, so callers can tell
     * a stale token apart from one that can never verify.
     */
    public static class ExpiredTokenException extends InvalidTokenException {
        public ExpiredTokenException(String message) {
            super(message);
        }
    }

    /**
     * Reads the {@code iss} claim without verifying the signature. Returns empty when {@code token}
     * is not a parseable JWT at all — the caller must reject it.
     */
    public Optional<String> peekIssuer(String token) {
        return parseClaims(token).map(claims -> claims.path("iss").asText(null))
                .filter(iss -> iss != null && !iss.isBlank());
    }

    /**
     * Reads the {@code kid} header without verifying the signature: the id of the issuer's key the
     * token names as its signer. Empty when {@code token} is not a JWT or names none.
     */
    public Optional<String> peekKeyId(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        return decodeJson(parts[0]).map(header -> header.path("kid").asText(null))
                .filter(kid -> kid != null && !kid.isBlank());
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
        return verify(token, publicKey, expectedIssuer, List.of(requiredAudience));
    }

    /**
     * As {@link #verify(String, RSAPublicKey, String, String)}, with {@code aud} holding at least one
     * of {@code acceptedAudiences}, the client IDs an IAM OIDC provider lists.
     */
    public WebIdentityToken verify(String token, RSAPublicKey publicKey, String expectedIssuer,
                                   List<String> acceptedAudiences) throws InvalidTokenException {
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
        if (audiences.stream().noneMatch(acceptedAudiences::contains)) {
            throw new InvalidTokenException("The web identity token audience does not include "
                    + String.join(" or ", acceptedAudiences));
        }

        verifyValidityWindow(claims);

        String subject = claims.path("sub").asText(null);
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("The web identity token has no subject claim");
        }

        return new WebIdentityToken(issuer, subject, audiences, readPrincipalTags(claims),
                readTransitiveTagKeys(claims));
    }

    private void verifyValidityWindow(JsonNode claims) throws InvalidTokenException {
        long now = Instant.now().getEpochSecond();

        JsonNode exp = claims.get("exp");
        if (exp == null || !exp.isNumber()) {
            throw new InvalidTokenException("The web identity token has no expiration claim");
        }
        if (now > exp.asLong() + CLOCK_SKEW_SECONDS) {
            throw new ExpiredTokenException("The web identity token has expired");
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

    /**
     * The session tags the token passes, in either format AWS reads them in. The nested format
     * puts them "under the principal_tags key" inside the {@code https://aws.amazon.com/tags}
     * namespace; the flattened one, "compatible with identity providers that don't support nested
     * objects in JWT claims", carries each as its own claim "with the prefix
     * https://aws.amazon.com/tags/principal_tags/". Both "achieve the same result", so both are
     * read, the flattened claims last for a token that somehow carries both.
     *
     * <p>A tag's value is one string: "each principal tag is a single string value", while the
     * published nested example writes each as an array holding that one value, so a single-element
     * array is read as the value it holds. Anything else is a multi-valued session tag, which "AWS
     * STS does not support", and the token is refused rather than verified with that tag dropped:
     * a tag a policy would have been evaluated with cannot go missing quietly.
     */
    private Map<String, String> readPrincipalTags(JsonNode claims) throws InvalidTokenException {
        Map<String, String> tags = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> tag : claims.path(TAGS_CLAIM).path("principal_tags").properties()) {
            tags.put(tag.getKey(), singleValue(tag.getKey(), tag.getValue()));
        }
        for (Map.Entry<String, JsonNode> claim : claims.properties()) {
            if (!claim.getKey().startsWith(FLATTENED_TAG_CLAIM)) {
                continue;
            }
            String key = claim.getKey().substring(FLATTENED_TAG_CLAIM.length());
            if (key.isEmpty()) {
                throw new InvalidTokenException("The web identity token carries a session tag with no key");
            }
            tags.put(key, singleValue(key, claim.getValue()));
        }
        return tags;
    }

    /**
     * The keys the token asks to set as transitive, in the order the claim lists them, read in
     * both formats as the tags are: nested, where "transitive tag keys are represented in an array
     * under the transitive_tag_keys key" of the same namespace, and flattened, "in a single claim
     * as an array of strings with the prefix https://aws.amazon.com/tags/transitive_tag_keys".
     *
     * <p>Both published examples write an array, and a lone string is read as the one key it names
     * rather than refused, which is the same reading {@link #singleValue} gives a tag written
     * without its array. A claim of any other shape, or one holding something that is not a
     * string, is refused rather than read past: a key dropped quietly would leave a chained
     * session short of a tag whose absence a policy can turn on. Which keys select a tag is the
     * session's business, not this one's.
     */
    private static List<String> readTransitiveTagKeys(JsonNode claims) throws InvalidTokenException {
        List<String> keys = new ArrayList<>();
        readTransitiveKeysInto(claims.path(TAGS_CLAIM).path(TRANSITIVE_KEYS), keys);
        readTransitiveKeysInto(claims.path(FLATTENED_TRANSITIVE_CLAIM), keys);
        return keys;
    }

    private static void readTransitiveKeysInto(JsonNode claim, List<String> keys)
            throws InvalidTokenException {
        if (claim.isMissingNode() || claim.isNull()) {
            return;
        }
        if (claim.isTextual()) {
            keys.add(claim.asText());
            return;
        }
        if (!claim.isArray()) {
            throw new InvalidTokenException(TRANSITIVE_KEYS_MALFORMED);
        }
        for (JsonNode key : claim) {
            if (!key.isTextual()) {
                throw new InvalidTokenException(TRANSITIVE_KEYS_MALFORMED);
            }
            keys.add(key.asText());
        }
    }

    /** The one value a tag claim carries, written as a string or as an array holding it alone. */
    private static String singleValue(String key, JsonNode value) throws InvalidTokenException {
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isArray() && value.size() == 1 && value.get(0).isTextual()) {
            return value.get(0).asText();
        }
        throw new InvalidTokenException("The web identity token's session tag " + key
                + " does not carry a single value");
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
        // splits into two parts, so its issuer would go unseen and validation could be skipped.
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
