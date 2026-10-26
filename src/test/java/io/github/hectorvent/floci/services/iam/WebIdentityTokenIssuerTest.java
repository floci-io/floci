package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.iam.model.WebIdentityTokenKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the wire test cannot check: that a token verifies against the key the issuer stored. Nothing
 * publishes that key yet, that being the other half of the feature, so this reaches it through the
 * store the issuer is built with.
 *
 * <p>The JCA names are spelled out here rather than read off
 * {@link WebIdentityTokenIssuer.Algorithm}, which is the point of the exercise: a signature that
 * stopped being the raw {@code R || S} pair JWS defines would fail this verifier rather than
 * follow the change into it.
 */
class WebIdentityTokenIssuerTest {

    private static final String ISSUER = "https://sts.us-east-1.amazonaws.com/000000000000";
    private static final String SUBJECT = "arn:aws:iam::000000000000:user/token-holder";
    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final WebIdentityTokenIssuer.CustomClaims CUSTOM_CLAIMS =
            new WebIdentityTokenIssuer.CustomClaims("000000000000", "us-east-1", Map.of());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StorageBackend<String, WebIdentityTokenKey> keyStore;
    private WebIdentityTokenIssuer issuer;

    @BeforeEach
    void setUp() {
        keyStore = new InMemoryStorage<>();
        issuer = new WebIdentityTokenIssuer(keyStore, MAPPER);
    }

    private String mint(WebIdentityTokenIssuer.Algorithm algorithm, long seconds) {
        return issuer.mint(algorithm, ISSUER, SUBJECT, List.of("app"), CUSTOM_CLAIMS,
                ISSUED_AT, ISSUED_AT.plusSeconds(seconds));
    }

    private static String segment(String jwt, int index) {
        return jwt.split("\\.", -1)[index];
    }

    private static String signingInput(String jwt) {
        return jwt.substring(0, jwt.lastIndexOf('.'));
    }

    private static byte[] signature(String jwt) {
        return Base64.getUrlDecoder().decode(segment(jwt, 2));
    }

    private static String keyId(String jwt) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(segment(jwt, 0)))
                .path("kid").asText();
    }

    private static String keyAlgorithm(WebIdentityTokenIssuer.Algorithm algorithm) {
        return algorithm == WebIdentityTokenIssuer.Algorithm.RS256 ? "RSA" : "EC";
    }

    private static String signatureAlgorithm(WebIdentityTokenIssuer.Algorithm algorithm) {
        return algorithm == WebIdentityTokenIssuer.Algorithm.RS256
                ? "SHA256withRSA"
                : "SHA384withECDSAinP1363Format";
    }

    /** Verifies a signature over the bytes given, against the stored public half of the key. */
    private boolean verifies(WebIdentityTokenIssuer.Algorithm algorithm, String signingInput,
                             byte[] signature) throws Exception {
        WebIdentityTokenKey key = keyStore.get(algorithm.name()).orElseThrow();
        PublicKey publicKey = KeyFactory.getInstance(keyAlgorithm(algorithm)).generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(key.getPublicKey())));
        Signature verifier = Signature.getInstance(signatureAlgorithm(algorithm));
        verifier.initVerify(publicKey);
        verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(signature);
    }

    /** The token is signed over its own header and claims by the key stored for its algorithm. */
    @ParameterizedTest
    @EnumSource(WebIdentityTokenIssuer.Algorithm.class)
    void aTokenVerifiesAgainstTheKeyTheIssuerStored(WebIdentityTokenIssuer.Algorithm algorithm)
            throws Exception {
        String jwt = mint(algorithm, 300);

        assertTrue(verifies(algorithm, signingInput(jwt), signature(jwt)),
                algorithm + " must verify against the key the issuer stored");
    }

    /**
     * The signature covers the claims it was made over, so one token's signature does not carry
     * another's claim set. A signature of the right length but over the wrong bytes passes a
     * length check and fails here.
     */
    @ParameterizedTest
    @EnumSource(WebIdentityTokenIssuer.Algorithm.class)
    void aSignatureDoesNotCoverAnotherTokensClaims(WebIdentityTokenIssuer.Algorithm algorithm)
            throws Exception {
        String jwt = mint(algorithm, 300);
        String longer = mint(algorithm, 3600);
        assertNotEquals(segment(jwt, 1), segment(longer, 1));

        String swapped = segment(jwt, 0) + "." + segment(longer, 1);

        assertFalse(verifies(algorithm, swapped, signature(jwt)),
                algorithm + " must not verify a claim set it did not sign");
    }

    /**
     * One key per algorithm, minted on first use and stored rather than held in memory, so a
     * restart does not invalidate a token already handed out.
     */
    @Test
    void theKeyIsMintedOncePerAlgorithmAndStored() throws Exception {
        String first = mint(WebIdentityTokenIssuer.Algorithm.RS256, 300);
        String second = mint(WebIdentityTokenIssuer.Algorithm.RS256, 300);
        String other = mint(WebIdentityTokenIssuer.Algorithm.ES384, 300);

        assertEquals(keyId(first), keyId(second));
        assertNotEquals(keyId(first), keyId(other));
        assertEquals(keyId(first), keyStore.get("RS256").orElseThrow().getKeyId());
        assertEquals(keyId(other), keyStore.get("ES384").orElseThrow().getKeyId());
    }
}
