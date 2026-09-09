package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Verifies CloudFront signed-URL and signed-cookie signatures for private content, per the AWS
 * CloudFront trusted-key-group spec. Pure and stateless: the caller supplies the request details, a
 * resolver from {@code Key-Pair-Id} to the trusted public-key PEM (returning {@code null} when the
 * key does not belong to one of the behavior's trusted key groups), and the current time.
 *
 * <p>Supported: canned and custom policies; signed URLs (query parameters) and signed cookies
 * ({@code CloudFront-*} cookies); SHA-1 and SHA-256 signatures with RSA-2048 and ECDSA P-256 keys;
 * the {@code DateLessThan}, {@code DateGreaterThan} and {@code IpAddress} conditions; and wildcard
 * {@code Resource} matching.
 */
public final class CloudFrontSignatureVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SIGNED_URL_PARAMETERS =
            Set.of("Expires", "Policy", "Signature", "Key-Pair-Id", "Hash-Algorithm");

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
        boolean signedUrl = query.keySet().stream().anyMatch(SIGNED_URL_PARAMETERS::contains);
        String keyPairId = signedUrl
                ? query.get("Key-Pair-Id")
                : cookies.get("CloudFront-Key-Pair-Id");
        String signatureB64 = signedUrl
                ? query.get("Signature")
                : cookies.get("CloudFront-Signature");
        if (keyPairId == null || signatureB64 == null) {
            return Result.deny("Missing CloudFront signature");
        }
        String policyParam = signedUrl
                ? query.get("Policy")
                : cookies.get("CloudFront-Policy");
        String expires = signedUrl
                ? query.get("Expires")
                : cookies.get("CloudFront-Expires");
        String hashAlgorithm = signedUrl
                ? query.get("Hash-Algorithm")
                : cookies.get("CloudFront-Hash-Algorithm");
        if (policyParam != null && expires != null) {
            return Result.deny("Policy and Expires cannot both be supplied");
        }
        if (hashAlgorithm == null) {
            hashAlgorithm = "SHA1";
        } else if (!"SHA1".equals(hashAlgorithm) && !"SHA256".equals(hashAlgorithm)) {
            return Result.deny("Unsupported CloudFront hash algorithm");
        }

        String pem = trustedKeyPem.apply(keyPairId);
        if (pem == null) {
            return Result.deny("Key-Pair-Id " + keyPairId + " is not a trusted signer");
        }
        PublicKey publicKey;
        try {
            publicKey = parseSupportedPublicKey(pem);
        } catch (Exception e) {
            return Result.deny("Unsupported public key for " + keyPairId);
        }

        String policyJson;
        boolean customPolicy = policyParam != null;
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
            String signatureAlgorithm = hashAlgorithm + switch (publicKey.getAlgorithm()) {
                case "RSA" -> "withRSA";
                case "EC" -> "withECDSA";
                default -> throw new GeneralSecurityException("Unsupported public key algorithm");
            };
            Signature signature = Signature.getInstance(signatureAlgorithm);
            signature.initVerify(publicKey);
            signature.update(policyJson.getBytes(StandardCharsets.UTF_8));
            if (!signature.verify(cfBase64Decode(signatureB64))) {
                return Result.deny("Signature does not match the policy");
            }
        } catch (Exception e) {
            return Result.deny("Signature verification failed");
        }

        return enforcePolicy(policyJson, resourceUrl, sourceIp, now, signedUrl, customPolicy);
    }

    /** The exact canned-policy document AWS signs (no whitespace); the signer must produce it byte-for-byte. */
    static String cannedPolicy(String resourceUrl, String expires) {
        return "{\"Statement\":[{\"Resource\":\"" + resourceUrl
                + "\",\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + expires + "}}}]}";
    }

    private static Result enforcePolicy(
            String policyJson, String resourceUrl, String sourceIp, Instant now,
            boolean signedUrl, boolean customPolicy) {
        JsonNode statements;
        try {
            statements = MAPPER.readTree(policyJson).path("Statement");
        } catch (Exception e) {
            return Result.deny("Malformed policy document");
        }
        if (!statements.isArray() || statements.size() != 1) {
            return Result.deny("Policy must contain exactly one statement");
        }
        long epochNow = now.getEpochSecond();
        JsonNode statement = statements.get(0);
        JsonNode resourceNode = statement.get("Resource");
        if (resourceNode != null
                && (!resourceNode.isTextual()
                    || (!signedUrl && !hasExplicitHttpProtocol(resourceNode.asText()))
                    || !(customPolicy
                        ? wildcardMatches(resourceNode.asText(), resourceUrl)
                        : exactResourceMatches(resourceNode.asText(), resourceUrl)))) {
            return Result.deny("The policy resource does not match the requested resource");
        }
        JsonNode condition = statement.path("Condition");
        JsonNode lessThan = condition.path("DateLessThan").path("AWS:EpochTime");
        if (!isEpochTime(lessThan)) {
            return Result.deny("Policy is missing a DateLessThan condition");
        }
        if (epochNow >= lessThan.asLong()) {
            return Result.deny("The signed request has expired");
        }
        JsonNode greaterThanCondition = condition.get("DateGreaterThan");
        if (greaterThanCondition != null) {
            JsonNode greaterThan = greaterThanCondition.path("AWS:EpochTime");
            if (!isEpochTime(greaterThan)) {
                return Result.deny("Policy has an invalid DateGreaterThan condition");
            }
            if (epochNow <= greaterThan.asLong()) {
                return Result.deny("The signed request is not yet valid");
            }
        }
        JsonNode ipCondition = condition.get("IpAddress");
        if (ipCondition != null) {
            JsonNode ip = ipCondition.path("AWS:SourceIp");
            if (!ip.isTextual()) {
                return Result.deny("Policy has an invalid IpAddress condition");
            }
            if (sourceIp == null || !ipInCidr(sourceIp, ip.asText())) {
                return Result.deny("Source IP is not permitted by the policy");
            }
        }
        return Result.allow();
    }

    private static boolean isEpochTime(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong();
    }

    static PublicKey parseSupportedPublicKey(String pem) throws GeneralSecurityException {
        if (pem == null) {
            throw new InvalidKeySpecException("The encoded public key is required");
        }
        String trimmed = pem.trim();
        String begin = "-----BEGIN PUBLIC KEY-----";
        String end = "-----END PUBLIC KEY-----";
        if (!trimmed.startsWith(begin) || !trimmed.endsWith(end)) {
            throw new InvalidKeySpecException("The public key must use X.509 PEM encoding");
        }
        String base64 = trimmed.substring(begin.length(), trimmed.length() - end.length())
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new InvalidKeySpecException("The public key is not valid base64", e);
        }
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        PublicKey publicKey;
        try {
            publicKey = KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (GeneralSecurityException rsaError) {
            try {
                publicKey = KeyFactory.getInstance("EC").generatePublic(spec);
            } catch (GeneralSecurityException ecError) {
                ecError.addSuppressed(rsaError);
                throw ecError;
            }
        }
        if (!isSupportedPublicKey(publicKey)) {
            throw new InvalidKeySpecException(
                    "CloudFront supports only RSA-2048 and ECDSA P-256 public keys");
        }
        return publicKey;
    }

    private static boolean isSupportedPublicKey(PublicKey publicKey)
            throws GeneralSecurityException {
        if (publicKey instanceof RSAPublicKey rsa) {
            return rsa.getModulus().bitLength() == 2048;
        }
        if (publicKey instanceof ECPublicKey ec) {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec p256 =
                    parameters.getParameterSpec(ECParameterSpec.class);
            ECParameterSpec actual = ec.getParams();
            return actual.getCurve().equals(p256.getCurve())
                    && actual.getGenerator().equals(p256.getGenerator())
                    && actual.getOrder().equals(p256.getOrder())
                    && actual.getCofactor() == p256.getCofactor();
        }
        return false;
    }

    /** Reverses CloudFront's URL-safe base64 alphabet (+ - = _ / ~) before decoding. */
    static byte[] cfBase64Decode(String value) {
        String standard = value.replace('-', '+').replace('_', '=').replace('~', '/');
        return Base64.getDecoder().decode(standard);
    }

    /**
     * Matches a custom-policy resource using CloudFront's URL-section wildcard rules. Wildcards stay
     * inside protocol, domain, path, and query sections, except for CloudFront's documented trailing
     * domain/path {@code *} behavior.
     */
    static boolean wildcardMatches(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        ResourceParts actual = parseActualResource(value);
        if (actual == null) {
            return false;
        }
        ResourcePattern expected = parseResourcePattern(pattern);
        if (expected == null
                || !globMatches(
                        expected.protocol().toLowerCase(Locale.ROOT),
                        actual.protocol().toLowerCase(Locale.ROOT))
                || !globMatches(
                        expected.domain().toLowerCase(Locale.ROOT),
                        actual.domain().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (expected.domainStarCoversRemainder()) {
            return true;
        }
        if (!globMatches(expected.path(), actual.path())) {
            return false;
        }
        if (expected.query() != null) {
            return actual.query() != null
                    && globMatches(expected.query(), actual.query());
        }
        return actual.query() == null || expected.pathStarCoversQuery();
    }

    private static boolean hasExplicitHttpProtocol(String resource) {
        return resource.startsWith("http://") || resource.startsWith("https://");
    }

    private static boolean exactResourceMatches(String expected, String actual) {
        return expected.equals(actual) && parseActualResource(actual) != null;
    }

    private static ResourceParts parseActualResource(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return null;
            }
            String path = uri.getRawPath();
            return new ResourceParts(
                    uri.getScheme(),
                    uri.getRawAuthority(),
                    path == null || path.isEmpty() ? "/" : path,
                    uri.getRawQuery());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ResourcePattern parseResourcePattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return null;
        }
        int schemeEnd = pattern.indexOf("://");
        String protocol;
        String remainder;
        if (schemeEnd >= 0) {
            protocol = pattern.substring(0, schemeEnd);
            remainder = pattern.substring(schemeEnd + 3);
        } else if (pattern.startsWith("*")) {
            protocol = "*";
            remainder = pattern;
        } else {
            return null;
        }
        int pathStart = remainder.indexOf('/');
        String domain = pathStart >= 0 ? remainder.substring(0, pathStart) : remainder;
        if (protocol.isEmpty() || domain.isEmpty()) {
            return null;
        }
        if (!"http".equals(protocol)
                && !"https".equals(protocol)
                && !"*".equals(protocol)) {
            return null;
        }
        boolean domainStarCoversRemainder = pathStart < 0 && domain.endsWith("*");
        String pathAndQuery = pathStart >= 0 ? remainder.substring(pathStart) : "/";
        int queryStart = pathAndQuery.indexOf('?');
        String path = queryStart >= 0
                ? pathAndQuery.substring(0, queryStart)
                : pathAndQuery;
        String query = queryStart >= 0
                ? pathAndQuery.substring(queryStart + 1)
                : null;
        boolean pathStarCoversQuery =
                query == null && path.endsWith("*");
        return new ResourcePattern(
                protocol,
                domain,
                path.isEmpty() ? "/" : path,
                query,
                domainStarCoversRemainder,
                pathStarCoversQuery);
    }

    private static boolean globMatches(String pattern, String value) {
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

    /**
     * IPv4 CIDR containment for custom-policy {@code IpAddress}. CloudFront does not support IPv6
     * values in this condition, so IPv6 and malformed values fail closed.
     */
    static boolean ipInCidr(String ip, String cidr) {
        try {
            int slash = cidr.indexOf('/');
            if (slash <= 0
                    || slash == cidr.length() - 1
                    || slash != cidr.lastIndexOf('/')) {
                return false;
            }
            String network = cidr.substring(0, slash);
            byte[] ipBytes = InetAddress.ofLiteral(ip).getAddress();
            byte[] netBytes = InetAddress.ofLiteral(network).getAddress();
            if (ipBytes.length != 4 || netBytes.length != 4) {
                return false;
            }
            String prefix = cidr.substring(slash + 1);
            if (!prefix.chars().allMatch(Character::isDigit)) {
                return false;
            }
            int bits = Integer.parseInt(prefix);
            if (bits < 0 || bits > 32) {
                return false;
            }
            for (int i = 0; i < ipBytes.length; i++) {
                int maskBits = Math.min(8, Math.max(0, bits - i * 8));
                int mask = maskBits == 0 ? 0 : (0xFF << (8 - maskBits)) & 0xFF;
                if ((ipBytes[i] & mask) != (netBytes[i] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private record ResourceParts(
            String protocol, String domain, String path, String query) {
    }

    private record ResourcePattern(
            String protocol,
            String domain,
            String path,
            String query,
            boolean domainStarCoversRemainder,
            boolean pathStarCoversQuery) {
    }
}
