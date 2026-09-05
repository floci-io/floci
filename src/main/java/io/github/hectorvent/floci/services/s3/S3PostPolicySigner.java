package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * SigV4 verification for presigned POST policy documents. Mirrors the credential/secret-key
 * resolution and signing-key derivation in {@link PreSignedUrlFilter}'s query-string presigned
 * URL verification, but signs the raw base64 policy document directly rather than a canonical
 * request hash, matching how the AWS SDKs sign a presigned POST policy.
 */
final class S3PostPolicySigner {

    static final String LEGACY_ACCESS_KEY_ID = "test";
    static final String LEGACY_SECRET_KEY = "test";

    private S3PostPolicySigner() {
    }

    static Optional<String> resolveSecretKey(IamService iamService, String accessKeyId) {
        if (LEGACY_ACCESS_KEY_ID.equals(accessKeyId)) {
            return Optional.of(LEGACY_SECRET_KEY);
        }
        if (iamService != null) {
            return iamService.findSecretKey(accessKeyId);
        }
        return Optional.empty();
    }

    /**
     * Verifies that {@code credential} is a well-formed S3 SigV4 credential scope:
     * exactly {@code accessKeyId/date/region/s3/aws4_request}, with the service and
     * terminator fixed, matching what a real S3 presigned POST requires.
     */
    static boolean isValidS3CredentialScope(String credential) {
        String[] parts = credential.split("/");
        return parts.length == 5 && "s3".equals(parts[3]) && "aws4_request".equals(parts[4]);
    }

    private static final DateTimeFormatter AMZ_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Verifies that {@code amzDate} (the form's {@code x-amz-date} field) is a genuine calendar
     * timestamp in ISO8601-basic form ({@code yyyyMMdd'T'HHmmss'Z'}) whose date matches the date
     * embedded in {@code credential}'s scope. Real S3 rejects a presigned POST whose form date
     * doesn't agree with the credential scope, even though only the credential's date drives
     * signing-key derivation. Strict parsing (rather than a digit-shape regex) rejects impossible
     * values such as month 13 or hour 99 that a lenient/smart resolver would otherwise accept.
     */
    static boolean isConsistentAmzDate(String amzDate, String credential) {
        try {
            AMZ_DATE_TIME_FORMAT.parse(amzDate);
        } catch (DateTimeParseException e) {
            return false;
        }
        String[] parts = credential.split("/");
        return parts.length == 5 && amzDate.substring(0, 8).equals(parts[1]);
    }

    /**
     * Verifies that {@code signatureHex} is the SigV4 signature of {@code policyBase64},
     * derived from {@code secretKey} using the date/region in {@code credential}
     * (an {@code accessKeyId/date/region/s3/aws4_request} credential scope; validate with
     * {@link #isValidS3CredentialScope(String)} before calling this).
     */
    static boolean verifySignature(String policyBase64, String credential, String signatureHex, String secretKey) {
        try {
            String[] parts = credential.split("/");
            if (!isValidS3CredentialScope(credential)) {
                return false;
            }
            String date = parts[1];
            String region = parts[2];
            String service = parts[3];
            byte[] signingKey = deriveSigningKey(secretKey, date, region, service);
            String expected = hexEncode(hmacSha256(signingKey, policyBase64));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHex.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] deriveSigningKey(String secretKey, String date, String region,
                                           String service) throws Exception {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
