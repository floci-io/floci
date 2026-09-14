package io.github.hectorvent.floci.testutil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signs an AppSync GraphQL data-plane request the way an AWS SDK signer would, so tests can
 * exercise the {@code AWS_IAM} enforcement in {@code IamAuthValidator} against real signatures
 * rather than against fixtures produced by the same code under test.
 *
 * <p>Header-signed only: real AppSync SDKs never presign this endpoint, and the fixed path/no
 * query-string shape of {@code /v1/apis/{apiId}/graphql} needs nothing else.
 */
public final class AppSyncRequestSigner {

    public static final String SIGNING_SERVICE = "appsync";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SCOPE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AppSyncRequestSigner() {
    }

    /**
     * Returns the {@code Authorization}, {@code X-Amz-Date} and {@code Host} headers a header-signed
     * {@code POST /v1/apis/{apiId}/graphql} request carries.
     */
    public static Map<String, String> signedHeaders(String apiId, String host, String body,
                                                     String accessKeyId, String secretKey,
                                                     String region, Instant signedAt) throws Exception {
        String amzDate = AMZ_DATE.format(signedAt);
        String scopeDate = SCOPE_DATE.format(signedAt);
        String credentialScope = scopeDate + "/" + region + "/" + SIGNING_SERVICE + "/aws4_request";
        String signedHeaderNames = "host;x-amz-date";
        String canonicalUri = "/v1/apis/" + apiId + "/graphql";

        String canonicalRequest = "POST\n"
                + canonicalUri + "\n"
                + "\n"
                + "host:" + host + "\n"
                + "x-amz-date:" + amzDate + "\n"
                + "\n"
                + signedHeaderNames + "\n"
                + sha256Hex(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));

        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = deriveSigningKey(secretKey, scopeDate, region);
        String signature = hexEncode(hmacSha256(signingKey, stringToSign));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", host);
        headers.put("X-Amz-Date", amzDate);
        headers.put("Authorization", "AWS4-HMAC-SHA256 "
                + "Credential=" + accessKeyId + "/" + credentialScope + ", "
                + "SignedHeaders=" + signedHeaderNames + ", "
                + "Signature=" + signature);
        return headers;
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
