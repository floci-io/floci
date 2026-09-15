package io.github.hectorvent.floci.core.common.auth;

import io.github.hectorvent.floci.services.iam.IamService;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Verifies AWS SigV4 presigned-URL auth tokens: a GET request against the target's own
 * hostname, signed with SigV4 query-parameter signing and an empty body, per AWS's
 * presigned-URL scheme. This is the IAM-auth mechanism shared by the ElastiCache and RDS
 * (and, via {@code RdsSigV4Validator}, Redshift) TCP proxies.
 *
 * <p>Extracted from {@code SigV4Validator} (ElastiCache) and {@code RdsSigV4Validator}
 * (RDS), whose only real differences are the identity query parameter name
 * ({@code User} vs {@code DBUser}), whether that parameter must be present at all, and
 * the exact string each signs as the canonical {@code host} header. Callers resolve the
 * token's host/authority themselves (ElastiCache signs only the cluster hostname, RDS
 * signs {@code host:port}) and pass the result in here.
 */
public final class SigV4RequestValidator {

    private static final Logger LOG = Logger.getLogger(SigV4RequestValidator.class);
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Well-known local-dev credential pair used pervasively by default AWS SDK clients
     * (AwsBasicCredentials.create("test", "test")) against this emulator, mirrored from the
     * identical fallback in S3Service/PreSignedUrlFilter. Deliberately not a fallback for any
     * other unregistered access key, only this exact, already-public pair is honored.
     */
    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";

    private final IamService iamService;

    public SigV4RequestValidator(IamService iamService) {
        this.iamService = iamService;
    }

    /**
     * Verifies a SigV4 presigned-URL token's required parameters, expiry, identity, and
     * signature.
     *
     * @param rawQuery the token's raw (still URL-encoded) query string
     * @param canonicalHostHeaderValue the exact string the caller signed as the canonical
     *                                 {@code host} header, e.g. the cluster ID for
     *                                 ElastiCache or {@code host:port} for RDS
     * @param identityParamName the query parameter carrying the caller's identity, e.g.
     *                          {@code User} or {@code DBUser}
     * @param identityRequired whether the token must include {@code identityParamName},
     *                         independent of whether an expected value was supplied
     * @param expectedIdentityValue the identity value to require a match against, or null
     *                              to skip the identity check entirely
     * @param logLabel short label prefixed to this validator's debug log lines, e.g.
     *                {@code "IAM token"} or {@code "RDS IAM token"}
     * @return true if the token is well-formed, not expired, identity matches (when
     *         checked), and the signature is valid
     */
    public boolean validate(String rawQuery, String canonicalHostHeaderValue, String identityParamName,
                             boolean identityRequired, String expectedIdentityValue, String logLabel) {
        try {
            String[] rawPairs = rawQuery.split("&");
            String action = findRawParam(rawPairs, "Action");
            String identity = findRawParam(rawPairs, identityParamName);
            String dateTime = findRawParam(rawPairs, "X-Amz-Date");
            String expires = findRawParam(rawPairs, "X-Amz-Expires");
            String credential = findRawParam(rawPairs, "X-Amz-Credential");
            String signedHeaders = findRawParam(rawPairs, "X-Amz-SignedHeaders");
            String signature = findRawParam(rawPairs, "X-Amz-Signature");

            if (!"connect".equals(action) || (identityRequired && identity == null) || dateTime == null
                    || expires == null || credential == null || signedHeaders == null || signature == null) {
                LOG.debugv("{0} missing required SigV4 parameters", logLabel);
                return false;
            }

            if (expectedIdentityValue != null && !expectedIdentityValue.equals(identity)) {
                LOG.debugv("{0} {1} mismatch: expected={2}, got={3}",
                        logLabel, identityParamName, expectedIdentityValue, identity);
                return false;
            }

            Instant tokenTime = Instant.from(DATETIME_FMT.parse(dateTime));
            int expirySeconds = Integer.parseInt(expires);
            if (Instant.now().isAfter(tokenTime.plusSeconds(expirySeconds))) {
                LOG.debugv("{0} expired", logLabel);
                return false;
            }

            String decodedCredential = urlDecode(credential);
            String[] credParts = decodedCredential.split("/");
            if (credParts.length < 5) {
                return false;
            }
            String accessKeyId = credParts[0];
            String date = credParts[1];
            String region = credParts[2];
            String service = credParts[3];
            String credentialScope = date + "/" + region + "/" + service + "/aws4_request";

            String secretKey;
            if (LEGACY_ACCESS_KEY_ID.equals(accessKeyId)) {
                secretKey = LEGACY_SECRET_KEY;
            } else {
                String sessionToken = findRawParam(rawPairs, "X-Amz-Security-Token");
                Optional<String> registeredSecretKey = iamService.findSecretKey(
                        accessKeyId, sessionToken == null ? null : urlDecode(sessionToken));
                if (registeredSecretKey.isEmpty()) {
                    LOG.debugv("{0} references unregistered access key={1}", logLabel, sanitizeForLog(accessKeyId));
                    return false;
                }
                secretKey = registeredSecretKey.get();
            }

            String canonicalQueryString = Arrays.stream(rawPairs)
                    .filter(p -> !rawParamName(p).equals("X-Amz-Signature"))
                    .sorted((a, b) -> rawParamName(a).compareTo(rawParamName(b)))
                    .collect(Collectors.joining("&"));

            String canonicalRequest = "GET\n/\n"
                    + canonicalQueryString + "\n"
                    + "host:" + canonicalHostHeaderValue + "\n\n"
                    + "host\n"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

            String stringToSign = "AWS4-HMAC-SHA256\n"
                    + dateTime + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest);

            byte[] signingKey = deriveSigningKey(secretKey, date, region, service);
            String expectedSignature = hexEncode(hmacSha256(signingKey, stringToSign));

            boolean valid = MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
            if (!valid) {
                LOG.debugv("{0} signature mismatch for accessKey={1}", logLabel, sanitizeForLog(accessKeyId));
            }
            return valid;

        } catch (Exception e) {
            LOG.debugv("{0} validation error: {1}", logLabel, e.getMessage());
            return false;
        }
    }

    private static String rawParamName(String rawPair) {
        int eq = rawPair.indexOf('=');
        return eq >= 0 ? rawPair.substring(0, eq) : rawPair;
    }

    private static String findRawParam(String[] rawPairs, String name) {
        for (String pair : rawPairs) {
            int eq = pair.indexOf('=');
            if (eq >= 0 && name.equals(pair.substring(0, eq))) {
                return urlDecode(pair.substring(eq + 1));
            }
        }
        return null;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Strips control characters (CR, LF, etc.) from an attacker-controlled value before it is
     * interpolated into a log line, preventing log injection / forged multi-line log entries.
     */
    public static String sanitizeForLog(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "");
    }

    /** SigV4 canonical header value normalization: trim, then collapse whitespace runs to one space. */
    public static String normalizeHeaderValue(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    /**
     * Public so other SigV4 verifiers with a different canonical-request shape (e.g. S3's real
     * REST request signing in {@code PreSignedUrlFilter}) can reuse the crypto primitives below
     * without their own copy, even where they can't reuse {@link #validate}'s query-token flow.
     */
    public static byte[] deriveSigningKey(String secretKey, String date, String region,
                                          String service) throws Exception {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    public static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hexEncode(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256Hex(byte[] input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hexEncode(digest.digest(input));
    }

    public static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
