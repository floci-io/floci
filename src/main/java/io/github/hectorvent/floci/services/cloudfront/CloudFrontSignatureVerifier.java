package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

/**
 * Verifies CloudFront signed-URL and signed-cookie signatures for private content, per the AWS
 * CloudFront trusted-key-group spec. Pure and stateless: the caller supplies the request details, a
 * resolver from {@code Key-Pair-Id} to the trusted public-key PEM (returning {@code null} when the
 * key does not belong to one of the behavior's trusted key groups), and the current time.
 *
 * <p>Supported: canned and custom policies; signed URLs (query parameters) and signed cookies
 * ({@code CloudFront-*} cookies); {@code SHA1withRSA} (default) and {@code SHA256withRSA} (via the
 * {@code Hash-Algorithm} parameter); the {@code DateLessThan}, {@code DateGreaterThan} and
 * {@code IpAddress} conditions and wildcard {@code Resource} matching. ECDSA keys are out of scope.
 */
public final class CloudFrontSignatureVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudFrontSignatureVerifier() {
    }

    /** The outcome of verifying a request against a behavior's trusted key groups. */
    public record Result(boolean allowed, String reason) {
        static Result allow() {
            return new Result(true, "ok");
        }

        static Result deny(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * @param resourceUrl   the request URL as the signer would have signed it
     *                      ({@code scheme://host/path[?app-query]}, excluding the CloudFront parameters)
     * @param query         request query parameters (may hold {@code Expires}/{@code Signature}/
     *                      {@code Key-Pair-Id}/{@code Policy}/{@code Hash-Algorithm})
     * @param cookies       request cookies (may hold the {@code CloudFront-*} signed-cookie names)
     * @param sourceIp      the request source IP for the {@code IpAddress} condition, or {@code null}
     * @param trustedKeyPem resolves a {@code Key-Pair-Id} to its PEM public key, or {@code null} when
     *                      the key is not a trusted signer for the matched behavior
     * @param now           the current time (expiration is checked against this)
     */
    public static Result verify(String resourceUrl, Map<String, String> query, Map<String, String> cookies,
                                String sourceIp, Function<String, String> trustedKeyPem, Instant now) {
        String keyPairId = firstNonNull(query.get("Key-Pair-Id"), cookies.get("CloudFront-Key-Pair-Id"));
        String signatureB64 = firstNonNull(query.get("Signature"), cookies.get("CloudFront-Signature"));
        if (keyPairId == null || signatureB64 == null) {
            return Result.deny("Missing CloudFront signature");
        }
        String policyParam = firstNonNull(query.get("Policy"), cookies.get("CloudFront-Policy"));
        String expires = firstNonNull(query.get("Expires"), cookies.get("CloudFront-Expires"));
        String hashAlgorithm = query.get("Hash-Algorithm");

        String pem = trustedKeyPem.apply(keyPairId);
        if (pem == null) {
            return Result.deny("Key-Pair-Id " + keyPairId + " is not a trusted signer");
        }
        PublicKey publicKey;
        try {
            publicKey = parsePublicKey(pem);
        } catch (Exception e) {
            return Result.deny("Unparseable public key for " + keyPairId);
        }

        String policyJson;
        if (policyParam != null) {
            try {
                policyJson = new String(cfBase64Decode(policyParam), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // A malformed Policy value is a bad request, not a server fault: CloudFront denies
                // it (403) rather than surfacing a decode error. cfBase64Decode delegates to
                // Base64.getDecoder().decode(), which throws IllegalArgumentException on invalid input.
                return Result.deny("Policy parameter is not valid CloudFront base64");
            }
        } else if (expires != null) {
            policyJson = cannedPolicy(resourceUrl, expires);
        } else {
            return Result.deny("Neither a Policy nor an Expires value was supplied");
        }

        try {
            Signature signature = Signature.getInstance(
                    "SHA256".equalsIgnoreCase(hashAlgorithm) ? "SHA256withRSA" : "SHA1withRSA");
            signature.initVerify(publicKey);
            signature.update(policyJson.getBytes(StandardCharsets.UTF_8));
            if (!signature.verify(cfBase64Decode(signatureB64))) {
                return Result.deny("Signature does not match the policy");
            }
        } catch (Exception e) {
            return Result.deny("Signature verification failed");
        }

        return enforcePolicy(policyJson, resourceUrl, sourceIp, now);
    }

    /** The exact canned-policy document AWS signs (no whitespace); the signer must produce it byte-for-byte. */
    static String cannedPolicy(String resourceUrl, String expires) {
        return "{\"Statement\":[{\"Resource\":\"" + resourceUrl
                + "\",\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + expires + "}}}]}";
    }

    private static Result enforcePolicy(String policyJson, String resourceUrl, String sourceIp, Instant now) {
        JsonNode statements;
        try {
            statements = MAPPER.readTree(policyJson).path("Statement");
        } catch (Exception e) {
            return Result.deny("Malformed policy document");
        }
        if (!statements.isArray() || statements.isEmpty()) {
            return Result.deny("Policy has no statements");
        }
        long epochNow = now.getEpochSecond();
        for (JsonNode statement : statements) {
            String resource = statement.path("Resource").asText(null);
            if (resource == null || !wildcardMatches(resource, resourceUrl)) {
                continue;
            }
            JsonNode condition = statement.path("Condition");
            JsonNode lessThan = condition.path("DateLessThan").path("AWS:EpochTime");
            if (!lessThan.canConvertToLong()) {
                return Result.deny("Policy is missing a DateLessThan condition");
            }
            if (epochNow >= lessThan.asLong()) {
                return Result.deny("The signed request has expired");
            }
            JsonNode greaterThan = condition.path("DateGreaterThan").path("AWS:EpochTime");
            if (greaterThan.canConvertToLong() && epochNow < greaterThan.asLong()) {
                return Result.deny("The signed request is not yet valid");
            }
            JsonNode ip = condition.path("IpAddress").path("AWS:SourceIp");
            if (ip.isTextual() && sourceIp != null && !ipInCidr(sourceIp, ip.asText())) {
                return Result.deny("Source IP is not permitted by the policy");
            }
            return Result.allow();
        }
        return Result.deny("No policy statement covers the requested resource");
    }

    private static PublicKey parsePublicKey(String pem) throws Exception {
        String base64 = pem.replaceAll("-----BEGIN (RSA )?PUBLIC KEY-----", "")
                .replaceAll("-----END (RSA )?PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** Reverses CloudFront's URL-safe base64 alphabet (+ - = _ / ~) before decoding. */
    static byte[] cfBase64Decode(String value) {
        String standard = value.replace('-', '+').replace('_', '=').replace('~', '/');
        return Base64.getDecoder().decode(standard);
    }

    /** CloudFront resource wildcards: {@code *} matches any run of characters, {@code ?} matches one. */
    static boolean wildcardMatches(String pattern, String value) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        return value != null && value.matches(regex.toString());
    }

    /** Basic IPv4 CIDR containment (e.g. {@code 0.0.0.0/0}, {@code 192.0.2.0/24}); non-IPv4 is permissive. */
    static boolean ipInCidr(String ip, String cidr) {
        try {
            int slash = cidr.indexOf('/');
            String network = slash >= 0 ? cidr.substring(0, slash) : cidr;
            int bits = slash >= 0 ? Integer.parseInt(cidr.substring(slash + 1)) : 32;
            long ipVal = ipv4ToLong(ip);
            long netVal = ipv4ToLong(network);
            if (ipVal < 0 || netVal < 0) {
                return true;
            }
            if (bits <= 0) {
                return true;
            }
            long mask = bits >= 32 ? 0xFFFFFFFFL : ~((1L << (32 - bits)) - 1) & 0xFFFFFFFFL;
            return (ipVal & mask) == (netVal & mask);
        } catch (Exception e) {
            return true;
        }
    }

    private static long ipv4ToLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long value = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) {
                return -1;
            }
            value = (value << 8) | octet;
        }
        return value;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
