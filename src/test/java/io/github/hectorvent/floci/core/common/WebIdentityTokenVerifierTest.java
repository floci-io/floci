package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.EksOidcService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebIdentityTokenVerifierTest {

    private static final String ISSUER =
            "https://oidc.eks.us-east-1.amazonaws.com/id/ABCDEF0123456789ABCDEF0123456789";
    private static final String CLUSTER = "irsa-test-cluster";

    private WebIdentityTokenVerifier verifier;
    private EksOidcService oidcService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        verifier = new WebIdentityTokenVerifier(objectMapper);
        oidcService = new EksOidcService(new StorageFactory(null, null) {
            @Override
            public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return new InMemoryStorage<>();
            }
        }, objectMapper);
        oidcService.ensureKey(CLUSTER, ISSUER);
    }

    private RSAPublicKey publicKey() {
        return oidcService.findVerificationKey(ISSUER).orElseThrow();
    }

    private String mint(String namespace, String serviceAccount, String audience, Integer lifetime) {
        return oidcService.mintServiceAccountToken(CLUSTER, ISSUER, namespace, serviceAccount,
                audience, lifetime);
    }

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Signs an arbitrary claim set, so tests can build tokens the production mint path never would. */
    private static String signRs256(PrivateKey privateKey, String claimsJson)
            throws GeneralSecurityException {
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64Url(claimsJson);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update((header + "." + payload).getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    @Test
    void verifiesFlociMintedToken() throws Exception {
        String token = mint("my-namespace", "my-service-account", null, null);

        WebIdentityToken verified = verifier.verify(token, publicKey(), ISSUER,
                EksOidcService.STS_AUDIENCE);

        assertEquals(ISSUER, verified.issuer());
        assertEquals("system:serviceaccount:my-namespace:my-service-account", verified.subject());
        assertTrue(verified.audiences().contains(EksOidcService.STS_AUDIENCE));
    }

    @Test
    void peeksIssuerWithoutVerifying() {
        String token = mint("my-namespace", "my-service-account", null, null);
        assertEquals(ISSUER, verifier.peekIssuer(token).orElseThrow());
    }

    @Test
    void peekIssuerReturnsEmptyForOpaqueToken() {
        assertTrue(verifier.peekIssuer("dummy-token").isEmpty());
        assertTrue(verifier.peekIssuer(null).isEmpty());
        assertTrue(verifier.peekIssuer("").isEmpty());
    }

    @Test
    void peekIssuerSeesTheIssuerOfAnUnsignedToken() {
        // Regression guard: an unsigned "header.payload." token must not read as opaque. If its
        // issuer went unseen, the caller would skip validation for a token naming a Floci issuer
        // and hand out credentials for an unsigned JWT.
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"" + ISSUER + "\","
                + "\"aud\":[\"" + EksOidcService.STS_AUDIENCE + "\"],"
                + "\"sub\":\"system:serviceaccount:my-namespace:my-service-account\","
                + "\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}");

        assertEquals(ISSUER, verifier.peekIssuer(header + "." + payload + ".").orElseThrow());
    }

    @Test
    void rejectsTamperedPayload() {
        String token = mint("my-namespace", "my-service-account", null, null);
        String[] parts = token.split("\\.", -1);
        // The forged payload is otherwise valid — same issuer, audience, and a future expiry — so
        // only the signature check can reject it.
        String forgedPayload = base64Url("{\"iss\":\"" + ISSUER + "\","
                + "\"aud\":[\"" + EksOidcService.STS_AUDIENCE + "\"],"
                + "\"sub\":\"system:serviceaccount:my-namespace:attacker\","
                + "\"nbf\":" + (System.currentTimeMillis() / 1000 - 60) + ","
                + "\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}");
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        WebIdentityTokenVerifier.InvalidTokenException thrown = assertThrows(
                WebIdentityTokenVerifier.InvalidTokenException.class,
                () -> verifier.verify(tampered, publicKey(), ISSUER, EksOidcService.STS_AUDIENCE));
        assertTrue(thrown.getMessage().contains("signature"),
                "expected a signature failure, got: " + thrown.getMessage());
    }

    @Test
    void rejectsWrongIssuer() {
        String token = mint("my-namespace", "my-service-account", null, null);

        assertThrows(WebIdentityTokenVerifier.InvalidTokenException.class,
                () -> verifier.verify(token, publicKey(),
                        "https://oidc.eks.us-east-1.amazonaws.com/id/OTHER",
                        EksOidcService.STS_AUDIENCE));
    }

    @Test
    void rejectsWrongAudience() {
        String token = mint("my-namespace", "my-service-account", "some.other.audience", null);

        assertThrows(WebIdentityTokenVerifier.InvalidTokenException.class,
                () -> verifier.verify(token, publicKey(), ISSUER, EksOidcService.STS_AUDIENCE));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        // Signed with a locally generated key so the payload can carry an arbitrary past expiry;
        // the production mint path always issues a future-dated token.
        KeyPair keyPair = newKeyPair();
        long expired = System.currentTimeMillis() / 1000 - 7200;
        String token = signRs256(keyPair.getPrivate(),
                "{\"iss\":\"" + ISSUER + "\",\"aud\":[\"" + EksOidcService.STS_AUDIENCE + "\"],"
                        + "\"sub\":\"system:serviceaccount:my-namespace:my-service-account\","
                        + "\"exp\":" + expired + "}");

        WebIdentityTokenVerifier.InvalidTokenException thrown = assertThrows(
                WebIdentityTokenVerifier.InvalidTokenException.class,
                () -> verifier.verify(token, (RSAPublicKey) keyPair.getPublic(), ISSUER,
                        EksOidcService.STS_AUDIENCE));
        assertTrue(thrown.getMessage().contains("expired"));
    }

    @Test
    void rejectsTokenNotYetValid() throws Exception {
        KeyPair keyPair = newKeyPair();
        long now = System.currentTimeMillis() / 1000;
        String token = signRs256(keyPair.getPrivate(),
                "{\"iss\":\"" + ISSUER + "\",\"aud\":[\"" + EksOidcService.STS_AUDIENCE + "\"],"
                        + "\"sub\":\"system:serviceaccount:my-namespace:my-service-account\","
                        + "\"nbf\":" + (now + 7200) + ",\"exp\":" + (now + 10800) + "}");

        WebIdentityTokenVerifier.InvalidTokenException thrown = assertThrows(
                WebIdentityTokenVerifier.InvalidTokenException.class,
                () -> verifier.verify(token, (RSAPublicKey) keyPair.getPublic(), ISSUER,
                        EksOidcService.STS_AUDIENCE));
        assertTrue(thrown.getMessage().contains("not yet valid"));
    }

    @Test
    void rejectsKeyFromAnotherCluster() {
        String otherIssuer = "https://oidc.eks.us-east-1.amazonaws.com/id/OTHERCLUSTER0000000000000000";
        oidcService.ensureKey("other-cluster", otherIssuer);
        String token = mint("my-namespace", "my-service-account", null, null);
        RSAPublicKey otherKey = oidcService.findVerificationKey(otherIssuer).orElseThrow();

        assertThrows(WebIdentityTokenVerifier.InvalidTokenException.class,
                () -> verifier.verify(token, otherKey, ISSUER, EksOidcService.STS_AUDIENCE));
    }

    @Test
    void rejectsMalformedToken() {
        assertThrows(WebIdentityTokenVerifier.InvalidTokenException.class,
                () -> verifier.verify("not-a-jwt", publicKey(), ISSUER, EksOidcService.STS_AUDIENCE));
    }

    @Test
    void rejectsUnsignedAlgNoneToken() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"" + ISSUER + "\","
                + "\"aud\":[\"" + EksOidcService.STS_AUDIENCE + "\"],"
                + "\"sub\":\"system:serviceaccount:my-namespace:my-service-account\","
                + "\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}");

        // The empty third segment must be preserved when splitting, so this is rejected by the
        // algorithm check rather than being mistaken for a two-segment malformed token.
        WebIdentityTokenVerifier.InvalidTokenException thrown = assertThrows(
                WebIdentityTokenVerifier.InvalidTokenException.class,
                () -> verifier.verify(header + "." + payload + ".", publicKey(), ISSUER,
                        EksOidcService.STS_AUDIENCE));
        assertTrue(thrown.getMessage().contains("algorithm"),
                "expected an algorithm rejection, got: " + thrown.getMessage());
    }
}
