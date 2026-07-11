package io.github.hectorvent.floci.services.cloudfront;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CloudFrontSignatureVerifier}: signs requests with a real in-test RSA key pair
 * and asserts the AWS accept/reject behavior for canned and custom policies, signed URLs and signed
 * cookies, expiry, resource binding, and trust.
 */
class CloudFrontSignatureVerifierTest {

    private static final String KEY_ID = "APKAEXAMPLEKEYID";
    private static final String RESOURCE = "https://d123.cloudfront.net/private/file.jpg";
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private KeyPair keyPair;
    private String pem;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    /** Resolver that trusts KEY_ID only (mimics a public key that is a member of a trusted key group). */
    private Function<String, String> trusted() {
        return id -> KEY_ID.equals(id) ? pem : null;
    }

    private String signCfBase64(String data, String algorithm) throws Exception {
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(keyPair.getPrivate());
        signature.update(data.getBytes(StandardCharsets.UTF_8));
        return cfBase64(signature.sign());
    }

    private static String cfBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('=', '_').replace('/', '~');
    }

    private long soon() {
        return now.getEpochSecond() + 3600;
    }

    @Test
    void cannedPolicySignedUrlIsAccepted() throws Exception {
        String policy = CloudFrontSignatureVerifier.cannedPolicy(RESOURCE, Long.toString(soon()));
        Map<String, String> query = Map.of(
                "Expires", Long.toString(soon()),
                "Signature", signCfBase64(policy, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID);

        var result = CloudFrontSignatureVerifier.verify(RESOURCE, query, Map.of(), null, trusted(), now);
        assertTrue(result.allowed(), result.reason());
    }

    @Test
    void sha256CannedPolicyIsAccepted() throws Exception {
        String policy = CloudFrontSignatureVerifier.cannedPolicy(RESOURCE, Long.toString(soon()));
        Map<String, String> query = Map.of(
                "Expires", Long.toString(soon()),
                "Signature", signCfBase64(policy, "SHA256withRSA"),
                "Key-Pair-Id", KEY_ID,
                "Hash-Algorithm", "SHA256");

        assertTrue(CloudFrontSignatureVerifier.verify(RESOURCE, query, Map.of(), null, trusted(), now).allowed());
    }

    @Test
    void expiredSignedUrlIsRejected() throws Exception {
        long expired = now.getEpochSecond() - 1;
        String policy = CloudFrontSignatureVerifier.cannedPolicy(RESOURCE, Long.toString(expired));
        Map<String, String> query = Map.of(
                "Expires", Long.toString(expired),
                "Signature", signCfBase64(policy, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID);

        var result = CloudFrontSignatureVerifier.verify(RESOURCE, query, Map.of(), null, trusted(), now);
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("expired"), result.reason());
    }

    @Test
    void signatureForADifferentResourceIsRejected() throws Exception {
        // Sign for RESOURCE but present the signature against a different requested URL.
        String policy = CloudFrontSignatureVerifier.cannedPolicy(RESOURCE, Long.toString(soon()));
        Map<String, String> query = Map.of(
                "Expires", Long.toString(soon()),
                "Signature", signCfBase64(policy, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID);

        var result = CloudFrontSignatureVerifier.verify(
                "https://d123.cloudfront.net/private/OTHER.jpg", query, Map.of(), null, trusted(), now);
        assertFalse(result.allowed());
    }

    @Test
    void untrustedKeyPairIdIsRejected() throws Exception {
        String policy = CloudFrontSignatureVerifier.cannedPolicy(RESOURCE, Long.toString(soon()));
        Map<String, String> query = Map.of(
                "Expires", Long.toString(soon()),
                "Signature", signCfBase64(policy, "SHA1withRSA"),
                "Key-Pair-Id", "SOME-OTHER-KEY");

        var result = CloudFrontSignatureVerifier.verify(RESOURCE, query, Map.of(), null, trusted(), now);
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("trusted signer"), result.reason());
    }

    @Test
    void missingSignatureIsRejected() {
        var result = CloudFrontSignatureVerifier.verify(RESOURCE, Map.of(), Map.of(), null, trusted(), now);
        assertFalse(result.allowed());
        assertEquals("Missing CloudFront signature", result.reason());
    }

    @Test
    void malformedPolicyBase64IsDeniedNotThrown() {
        // A Policy value that is not valid CloudFront base64 is a bad request: verify() must deny
        // it (CloudFront answers 403) rather than let the base64 decode throw and become a 500.
        Map<String, String> query = Map.of(
                "Policy", "@@@not-valid-base64@@@",
                "Signature", "AAAA",
                "Key-Pair-Id", KEY_ID);

        var result = CloudFrontSignatureVerifier.verify(RESOURCE, query, Map.of(), null, trusted(), now);
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("base64"), result.reason());
    }

    @Test
    void customPolicyWithWildcardResourceIsAccepted() throws Exception {
        String policyJson = "{\"Statement\":[{\"Resource\":\"https://d123.cloudfront.net/private/*\","
                + "\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + soon() + "}}}]}";
        Map<String, String> query = Map.of(
                "Policy", cfBase64(policyJson.getBytes(StandardCharsets.UTF_8)),
                "Signature", signCfBase64(policyJson, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID);

        assertTrue(CloudFrontSignatureVerifier.verify(RESOURCE, query, Map.of(), null, trusted(), now).allowed());
    }

    @Test
    void customPolicyIpAddressConditionIsEnforced() throws Exception {
        String policyJson = "{\"Statement\":[{\"Resource\":\"https://d123.cloudfront.net/private/*\","
                + "\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + soon() + "},"
                + "\"IpAddress\":{\"AWS:SourceIp\":\"203.0.113.0/24\"}}}]}";
        Map<String, String> query = Map.of(
                "Policy", cfBase64(policyJson.getBytes(StandardCharsets.UTF_8)),
                "Signature", signCfBase64(policyJson, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID);

        assertTrue(CloudFrontSignatureVerifier.verify(RESOURCE, query, Map.of(), "203.0.113.5", trusted(), now).allowed());
        assertFalse(CloudFrontSignatureVerifier.verify(RESOURCE, query, Map.of(), "198.51.100.5", trusted(), now).allowed());
    }

    @Test
    void signedCookiesAreAccepted() throws Exception {
        String policy = CloudFrontSignatureVerifier.cannedPolicy(RESOURCE, Long.toString(soon()));
        Map<String, String> cookies = Map.of(
                "CloudFront-Expires", Long.toString(soon()),
                "CloudFront-Signature", signCfBase64(policy, "SHA1withRSA"),
                "CloudFront-Key-Pair-Id", KEY_ID);

        assertTrue(CloudFrontSignatureVerifier.verify(RESOURCE, Map.of(), cookies, null, trusted(), now).allowed());
    }
}
