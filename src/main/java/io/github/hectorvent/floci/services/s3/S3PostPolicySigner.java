package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
     * Verifies that {@code signatureHex} is the SigV4 signature of {@code policyBase64},
     * derived from {@code secretKey} using the date/region/service in {@code credential}
     * (an {@code accessKeyId/date/region/service/aws4_request} credential scope).
     */
    static boolean verifySignature(String policyBase64, String credential, String signatureHex, String secretKey) {
        try {
            String[] parts = credential.split("/");
            if (parts.length < 5) {
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
