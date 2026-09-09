package io.github.hectorvent.floci.testutil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Signs an execute-api data-plane request the way an AWS SDK signer would, so tests can exercise
 * the AWS_IAM enforcement in {@code ExecuteApiSigV4Authorizer} against real signatures rather than
 * against fixtures produced by the same code under test.
 */
public final class ExecuteApiRequestSigner {

    public static final String SIGNING_SERVICE = "execute-api";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    // Not BASIC_ISO_DATE: applied to an Instant it appends the zone offset ("20260831Z"), and a
    // credential scope date is exactly eight digits.
    private static final DateTimeFormatter SCOPE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private ExecuteApiRequestSigner() {
    }

    /**
     * Returns the {@code X-Amz-Date} and {@code Authorization} headers a header-signed request
     * carries. {@code queryParameters} must be the parameters that will actually be sent.
     */
    public static Map<String, String> signedHeaders(String httpMethod, String path,
                                                    Map<String, String> queryParameters,
                                                    String host, byte[] body,
                                                    String accessKeyId, String secretKey,
                                                    String region, Instant signedAt) throws Exception {
        return signedHeaders(httpMethod, path, queryParameters, host, body,
                accessKeyId, secretKey, region, signedAt, true);
    }

    /**
     * Signs a request, optionally leaving {@code host} out of {@code SignedHeaders}. No real AWS
     * signer omits it, so the false case exists only to exercise the verifier's rejection of a
     * signature that is not bound to the endpoint it is presented to.
     */
    public static Map<String, String> signedHeaders(String httpMethod, String path,
                                                    Map<String, String> queryParameters,
                                                    String host, byte[] body,
                                                    String accessKeyId, String secretKey,
                                                    String region, Instant signedAt,
                                                    boolean signHost) throws Exception {
        String amzDate = AMZ_DATE.format(signedAt);
        String scopeDate = SCOPE_DATE.format(signedAt);
        String credentialScope = scopeDate + "/" + region + "/" + SIGNING_SERVICE + "/aws4_request";
        String signedHeaderNames = signHost ? "host;x-amz-date" : "x-amz-date";

        String canonicalRequest = httpMethod + "\n"
                + path + "\n"
                + canonicalQueryString(queryParameters) + "\n"
                + (signHost ? "host:" + host + "\n" : "")
                + "x-amz-date:" + amzDate + "\n"
                + "\n"
                + signedHeaderNames + "\n"
                + sha256Hex(body == null ? new byte[0] : body);

        String signature = sign(secretKey, scopeDate, region, credentialScope, amzDate, canonicalRequest);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Amz-Date", amzDate);
        headers.put("Authorization", "AWS4-HMAC-SHA256 "
                + "Credential=" + accessKeyId + "/" + credentialScope + ", "
                + "SignedHeaders=" + signedHeaderNames + ", "
                + "Signature=" + signature);
        return headers;
    }

    /**
     * Returns the query string of a presigned execute-api request, {@code X-Amz-Signature} included
     * and no leading {@code ?}.
     */
    public static String presignedQueryString(String httpMethod, String path, String host,
                                              String accessKeyId, String secretKey, String region,
                                              Instant signedAt, long expiresSeconds) throws Exception {
        return presignedQueryString(httpMethod, path, host, accessKeyId, secretKey, region,
                signedAt, expiresSeconds, null);
    }

    /**
     * Presigns a request made with temporary credentials, carrying {@code sessionToken} as the
     * {@code X-Amz-Security-Token} query parameter the way an SDK signer does: inside the signed
     * query string, so altering it in transit breaks the signature as well as the token comparison.
     */
    public static String presignedQueryString(String httpMethod, String path, String host,
                                              String accessKeyId, String secretKey, String region,
                                              Instant signedAt, long expiresSeconds,
                                              String sessionToken) throws Exception {
        String amzDate = AMZ_DATE.format(signedAt);
        String scopeDate = SCOPE_DATE.format(signedAt);
        String credentialScope = scopeDate + "/" + region + "/" + SIGNING_SERVICE + "/aws4_request";

        Map<String, String> queryParameters = new LinkedHashMap<>();
        queryParameters.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        queryParameters.put("X-Amz-Credential", accessKeyId + "/" + credentialScope);
        queryParameters.put("X-Amz-Date", amzDate);
        queryParameters.put("X-Amz-Expires", Long.toString(expiresSeconds));
        if (sessionToken != null) {
            queryParameters.put("X-Amz-Security-Token", sessionToken);
        }
        queryParameters.put("X-Amz-SignedHeaders", "host");

        String canonicalQuery = canonicalQueryString(queryParameters);
        String canonicalRequest = httpMethod + "\n"
                + path + "\n"
                + canonicalQuery + "\n"
                + "host:" + host + "\n"
                + "\n"
                + "host\n"
                + "UNSIGNED-PAYLOAD";

        String signature = sign(secretKey, scopeDate, region, credentialScope, amzDate, canonicalRequest);
        return canonicalQuery + "&X-Amz-Signature=" + signature;
    }

    private static String sign(String secretKey, String scopeDate, String region,
                               String credentialScope, String amzDate, String canonicalRequest)
            throws Exception {
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = deriveSigningKey(secretKey, scopeDate, region);
        return hexEncode(hmacSha256(signingKey, stringToSign));
    }

    public static String canonicalQueryString(Map<String, String> queryParameters) {
        if (queryParameters == null || queryParameters.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = new TreeMap<>(queryParameters);
        List<String> pairs = new ArrayList<>();
        sorted.forEach((name, value) -> pairs.add(uriEncode(name) + "=" + uriEncode(value)));
        return String.join("&", pairs);
    }

    public static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xFF;
            char ch = (char) b;
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                encoded.append(ch);
            } else {
                encoded.append('%').append(String.format("%02X", b));
            }
        }
        return encoded.toString();
    }

    private static byte[] deriveSigningKey(String secretKey, String date, String region) throws Exception {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, SIGNING_SERVICE);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] input) throws Exception {
        return hexEncode(MessageDigest.getInstance("SHA-256").digest(input));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
