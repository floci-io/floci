package io.github.hectorvent.floci.services.cloudfront;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
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
        pem = pem(keyPair.getPublic());
    }

    /** Resolver that trusts KEY_ID only (mimics a public key that is a member of a trusted key group). */
    private Function<String, String> trusted() {
        return id -> KEY_ID.equals(id) ? pem : null;
    }

    private String signCfBase64(String data, String algorithm) throws Exception {
        return signCfBase64(keyPair, data, algorithm);
    }

    private static String signCfBase64(
            KeyPair signingKeyPair, String data, String algorithm)
            throws Exception {
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(signingKeyPair.getPrivate());
        signature.update(data.getBytes(StandardCharsets.UTF_8));
        return cfBase64(signature.sign());
    }

    private static String pem(PublicKey publicKey) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(publicKey.getEncoded())
                + "\n-----END PUBLIC KEY-----";
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
    void cannedPolicyWithBareApplicationQueryIsAccepted() throws Exception {
        String resource = RESOURCE + "?flag";
        String policy = CloudFrontSignatureVerifier.cannedPolicy(
                resource, Long.toString(soon()));
        Map<String, String> query = Map.of(
                "Expires", Long.toString(soon()),
                "Signature", signCfBase64(policy, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID);

        var result = CloudFrontSignatureVerifier.verify(
                resource, query, Map.of(), null, trusted(), now);
        assertTrue(result.allowed(), result.reason());
    }

    @Test
    void exactCustomPolicyWithApplicationQueryIsAccepted() throws Exception {
        String resource = RESOURCE + "?license=paid";
        String policyJson = "{\"Statement\":[{\"Resource\":\"" + resource + "\","
                + "\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + soon() + "}}}]}";
        Map<String, String> query = Map.of(
                "Policy", cfBase64(policyJson.getBytes(StandardCharsets.UTF_8)),
                "Signature", signCfBase64(policyJson, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID);

        var result = CloudFrontSignatureVerifier.verify(
                resource, query, Map.of(), null, trusted(), now);
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
    void sha256SignedCookieIsAccepted() throws Exception {
        String policy =
                CloudFrontSignatureVerifier.cannedPolicy(
                        RESOURCE, Long.toString(soon()));
        Map<String, String> cookies = Map.of(
                "CloudFront-Expires", Long.toString(soon()),
                "CloudFront-Signature",
                        signCfBase64(policy, "SHA256withRSA"),
                "CloudFront-Key-Pair-Id", KEY_ID,
                "CloudFront-Hash-Algorithm", "SHA256");

        assertTrue(CloudFrontSignatureVerifier.verify(
                RESOURCE, Map.of(), cookies, null, trusted(), now).allowed());
    }

    @Test
    void unsupportedHashAlgorithmIsRejected() throws Exception {
        String policy =
                CloudFrontSignatureVerifier.cannedPolicy(
                        RESOURCE, Long.toString(soon()));
        Map<String, String> query = Map.of(
                "Expires", Long.toString(soon()),
                "Signature", signCfBase64(policy, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID,
                "Hash-Algorithm", "SHA512");

        assertFalse(CloudFrontSignatureVerifier.verify(
                RESOURCE, query, Map.of(), null, trusted(), now).allowed());
    }

    @Test
    void anySignedUrlParameterMakesCloudFrontIgnoreSignedCookies()
            throws Exception {
        String policy =
                CloudFrontSignatureVerifier.cannedPolicy(
                        RESOURCE, Long.toString(soon()));
        Map<String, String> cookies = Map.of(
                "CloudFront-Expires", Long.toString(soon()),
                "CloudFront-Signature",
                        signCfBase64(policy, "SHA1withRSA"),
                "CloudFront-Key-Pair-Id", KEY_ID);

        var result = CloudFrontSignatureVerifier.verify(
                RESOURCE,
                Map.of("Expires", Long.toString(soon())),
                cookies,
                null,
                trusted(),
                now);

        assertFalse(result.allowed());
        assertEquals("Missing CloudFront signature", result.reason());
    }

    @Test
    void ecdsaP256Sha256SignatureIsAccepted() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ecKeyPair = generator.generateKeyPair();
        String policy =
                CloudFrontSignatureVerifier.cannedPolicy(
                        RESOURCE, Long.toString(soon()));
        Map<String, String> query = Map.of(
                "Expires", Long.toString(soon()),
                "Signature",
                        signCfBase64(ecKeyPair, policy, "SHA256withECDSA"),
                "Key-Pair-Id", KEY_ID,
                "Hash-Algorithm", "SHA256");

        assertTrue(CloudFrontSignatureVerifier.verify(
                RESOURCE,
                query,
                Map.of(),
                null,
                id -> KEY_ID.equals(id) ? pem(ecKeyPair.getPublic()) : null,
                now).allowed());
    }

    @Test
    void unsupportedRsaKeySizeIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair shortKeyPair = generator.generateKeyPair();
        String policy =
                CloudFrontSignatureVerifier.cannedPolicy(
                        RESOURCE, Long.toString(soon()));
        Map<String, String> query = Map.of(
                "Expires", Long.toString(soon()),
                "Signature",
                        signCfBase64(shortKeyPair, policy, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID);

        assertFalse(CloudFrontSignatureVerifier.verify(
                RESOURCE,
                query,
                Map.of(),
                null,
                id -> KEY_ID.equals(id) ? pem(shortKeyPair.getPublic()) : null,
                now).allowed());
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
    void questionMarkAfterQueryDelimiterActsAsWildcard() throws Exception {
        String resourcePattern =
                "https://d123.cloudfront.net/private/?ile.jpg?license=*";
        String policyJson = "{\"Statement\":[{\"Resource\":\"" + resourcePattern + "\","
                + "\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + soon() + "}}}]}";
        Map<String, String> query = Map.of(
                "Policy", cfBase64(policyJson.getBytes(StandardCharsets.UTF_8)),
                "Signature", signCfBase64(policyJson, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID);

        assertFalse(CloudFrontSignatureVerifier.verify(
                "https://d123.cloudfront.net/private/file.jpg?license=paid",
                query, Map.of(), null, trusted(), now).allowed());
        assertTrue(CloudFrontSignatureVerifier.verify(
                "https://d123.cloudfront.net/private/?ile.jpgXlicense=paid",
                query, Map.of(), null, trusted(), now).allowed());
        assertTrue(CloudFrontSignatureVerifier.verify(
                resourcePattern,
                query, Map.of(), null, trusted(), now).allowed());
    }

    @Test
    void signedCookieCustomPolicyRequiresAnExplicitHttpProtocol()
            throws Exception {
        String policyJson = "{\"Statement\":[{\"Resource\":\"*\","
                + "\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":"
                + soon() + "}}}]}";
        Map<String, String> cookies = customPolicyCookies(policyJson);

        assertFalse(CloudFrontSignatureVerifier.verify(
                RESOURCE, Map.of(), cookies, null, trusted(), now).allowed());
    }

    @Test
    void signedCookieCustomPolicyAcceptsHttpsWildcardResource()
            throws Exception {
        String policyJson = "{\"Statement\":[{\"Resource\":\"https://*\","
                + "\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":"
                + soon() + "}}}]}";
        Map<String, String> cookies = customPolicyCookies(policyJson);

        assertTrue(CloudFrontSignatureVerifier.verify(
                RESOURCE, Map.of(), cookies, null, trusted(), now).allowed());
    }

    @Test
    void signedCookieCustomPolicyMayOmitResource()
            throws Exception {
        String policyJson = "{\"Statement\":[{\"Condition\":{\"DateLessThan\":"
                + "{\"AWS:EpochTime\":" + soon() + "}}}]}";
        Map<String, String> cookies = customPolicyCookies(policyJson);

        assertTrue(CloudFrontSignatureVerifier.verify(
                RESOURCE, Map.of(), cookies, null, trusted(), now).allowed());
    }

    @Test
    void customPolicyMayOmitResource() throws Exception {
        String policyJson = "{\"Statement\":[{\"Condition\":{\"DateLessThan\":"
                + "{\"AWS:EpochTime\":" + soon() + "}}}]}";
        Map<String, String> query = customPolicyQuery(policyJson);

        assertTrue(CloudFrontSignatureVerifier.verify(
                RESOURCE, query, Map.of(), null, trusted(), now).allowed());
    }

    @Test
    void customPolicyMustContainExactlyOneStatement() throws Exception {
        String statement = "{\"Resource\":\"*\",\"Condition\":{\"DateLessThan\":"
                + "{\"AWS:EpochTime\":" + soon() + "}}}";
        String policyJson =
                "{\"Statement\":[" + statement + "," + statement + "]}";

        assertFalse(CloudFrontSignatureVerifier.verify(
                RESOURCE,
                customPolicyQuery(policyJson),
                Map.of(),
                null,
                trusted(),
                now).allowed());
    }

    @Test
    void customPolicyRequiresIntegralEpochTimes() throws Exception {
        String policyJson = "{\"Statement\":[{\"Resource\":\"*\","
                + "\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":"
                + soon() + ".5}}}]}";

        assertFalse(CloudFrontSignatureVerifier.verify(
                RESOURCE,
                customPolicyQuery(policyJson),
                Map.of(),
                null,
                trusted(),
                now).allowed());
    }

    @Test
    void dateGreaterThanRejectsTheBoundarySecond() throws Exception {
        String policyJson = "{\"Statement\":[{\"Resource\":\"*\","
                + "\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":"
                + soon() + "},\"DateGreaterThan\":{\"AWS:EpochTime\":"
                + now.getEpochSecond() + "}}}]}";

        assertFalse(CloudFrontSignatureVerifier.verify(
                RESOURCE,
                customPolicyQuery(policyJson),
                Map.of(),
                null,
                trusted(),
                now).allowed());
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
        assertFalse(CloudFrontSignatureVerifier.verify(RESOURCE, query, Map.of(), null, trusted(), now).allowed());
        // An IPv6 client must not bypass an IPv4-only allow-list (containment fails closed).
        assertFalse(CloudFrontSignatureVerifier.verify(RESOURCE, query, Map.of(), "2001:db8::1", trusted(), now).allowed());
    }

    private Map<String, String> customPolicyCookies(String policyJson)
            throws Exception {
        return Map.of(
                "CloudFront-Policy",
                        cfBase64(policyJson.getBytes(StandardCharsets.UTF_8)),
                "CloudFront-Signature",
                        signCfBase64(policyJson, "SHA1withRSA"),
                "CloudFront-Key-Pair-Id",
                        KEY_ID);
    }

    @Test
    void resourceWildcardsRespectUrlSectionBoundaries() {
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/hello*world",
                "https://d123.cloudfront.net/hello-world"));
        assertFalse(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/hello*world",
                "https://d123.cloudfront.net/hello?world"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/file.jpg?license=?",
                "https://d123.cloudfront.net/file.jpg?license=x"));
        assertFalse(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/private/?ile.jpg",
                "https://d123.cloudfront.net/private/file.jpg"));
        assertFalse(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/private/?ile.jpg?license=*",
                "https://d123.cloudfront.net/private/file.jpg?license=paid"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/private/?ile.jpg?license=*",
                "https://d123.cloudfront.net/private/?ile.jpgXlicense=paid"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/file?flag",
                "https://d123.cloudfront.net/file?flag"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/file?lic?nse=*",
                "https://d123.cloudfront.net/file?license=value"));
        assertFalse(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/file?lic?nse=*",
                "https://d123.cloudfront.net/fileXlic?nse=value"));
        assertFalse(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/private/?ile.jpg=download?license=*",
                "https://d123.cloudfront.net/private/file.jpg=download?license=paid"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/private/?ile.jpg=download?license=*",
                "https://d123.cloudfront.net/private/?ile.jpg=downloadXlicense=paid"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/private/?ile.jpg?license=*",
                "https://d123.cloudfront.net/private/?ile.jpg?license=*"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/private/?ile=download*",
                "https://d123.cloudfront.net/private/?ile=download-secret"));
        assertFalse(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/file.jpg?license=*",
                "https://d123.cloudfront.net/file.jpgXlicense=paid"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/file.jpg?license=*",
                "https://d123.cloudfront.net/file.jpg?license=paid"));
    }

    @Test
    void trailingWildcardsUseCloudFrontPropagationRules() {
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net/private/*",
                "https://d123.cloudfront.net/private/file.jpg?license=yes"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "https://d123.cloudfront.net*",
                "https://d123.cloudfront.net/private/file.jpg?license=yes"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "*d123.cloudfront.net",
                "https://media.d123.cloudfront.net/"));
        assertFalse(CloudFrontSignatureVerifier.wildcardMatches(
                "*d123.cloudfront.net",
                "https://media.d123.cloudfront.net/private/file.jpg"));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "*", "https://any.example.test/file"));
    }

    @Test
    void resourceProtocolMustUseAnAwsSupportedForm() {
        assertFalse(CloudFrontSignatureVerifier.wildcardMatches(
                "h*://d123.cloudfront.net/private/*", RESOURCE));
        assertTrue(CloudFrontSignatureVerifier.wildcardMatches(
                "*://d123.cloudfront.net/private/*", RESOURCE));
    }

    @Test
    void ipInCidrSupportsOnlyExplicitIpv4Cidr() {
        assertFalse(CloudFrontSignatureVerifier.ipInCidr(
                "2001:db8::5", "2001:db8::/32"));
        assertFalse(CloudFrontSignatureVerifier.ipInCidr("2001:dead::5", "2001:db8::/32"));
        assertFalse(CloudFrontSignatureVerifier.ipInCidr("2001:db8::1", "192.0.2.0/24"));
        assertFalse(CloudFrontSignatureVerifier.ipInCidr("not-an-ip", "192.0.2.0/24"));
        assertFalse(CloudFrontSignatureVerifier.ipInCidr("203.0.113.5", "203.0.113.5"));
        assertTrue(CloudFrontSignatureVerifier.ipInCidr("203.0.113.5", "203.0.113.5/32"));
        assertTrue(CloudFrontSignatureVerifier.ipInCidr("10.0.0.1", "0.0.0.0/0"));
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

    @Test
    void signingParametersAreRemovedFromTheOriginQuery() {
        String rawQuery = "color=blue&Policy=opaque&Signature=sig&Key-Pair-Id=key"
                + "&Hash-Algorithm=SHA256&encoded=a%2Fb&%45xpires=123";

        assertEquals("color=blue&encoded=a%2Fb",
                CloudFrontServingController.stripCloudFrontSigningParams(rawQuery));
    }

    @Test
    void emptyApplicationQueryComponentsArePreserved() {
        assertEquals(
                "&color=blue",
                CloudFrontServingController.stripCloudFrontSigningParams(
                        "&color=blue&Signature=sig"));
        assertEquals(
                "color=blue&&",
                CloudFrontServingController.stripCloudFrontSigningParams(
                        "color=blue&&Signature=sig&"));
    }

    @Test
    void malformedQueryParameterNameIsPreservedWithoutThrowing() {
        String rawQuery = "%ZZ=value&Signature=sig&color=blue";

        assertEquals("%ZZ=value&color=blue",
                CloudFrontServingController.stripCloudFrontSigningParams(rawQuery));
    }

    private Map<String, String> customPolicyQuery(String policyJson)
            throws Exception {
        return Map.of(
                "Policy", cfBase64(policyJson.getBytes(StandardCharsets.UTF_8)),
                "Signature", signCfBase64(policyJson, "SHA1withRSA"),
                "Key-Pair-Id", KEY_ID);
    }
}
