package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Validates ElastiCache IAM auth tokens (SigV4 presigned URLs).
 * Extracted from ElastiCacheQueryHandler so it can be shared with the TCP auth proxy.
 */
@ApplicationScoped
public class SigV4Validator {

    private static final Logger LOG = Logger.getLogger(SigV4Validator.class);
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final IamService iamService;

    @Inject
    public SigV4Validator(IamService iamService) {
        this.iamService = iamService;
    }

    /**
     * Validates the given IAM auth token against the expected cluster ID and username.
     * The token is a presigned URL without the scheme, e.g.:
     * {@code clusterId/?Action=connect&User=...&X-Amz-Signature=...}
     *
     * @param token the presigned URL token
     * @param expectedGroupId the replication group ID to match against the token's host
     * @param expectedUsername the Redis username from the AUTH command;
     *                         must match the {@code User} in the token. May be null to skip.
     * @return true if the token is valid, identities match, and the token is not expired
     */
    public boolean validate(String token, String expectedGroupId, String expectedUsername) {
        try {
            URI uri = URI.create("http://" + token);
            String clusterId = uri.getHost();
            String rawQuery = uri.getRawQuery();

            if (clusterId == null || rawQuery == null) {
                LOG.debugv("IAM token missing clusterId or query string");
                return false;
            }

            if (expectedGroupId != null && !expectedGroupId.equalsIgnoreCase(clusterId)) {
                LOG.debugv("IAM token cluster mismatch: expected={0}, got={1}", expectedGroupId, clusterId);
                return false;
            }

            String[] rawPairs = rawQuery.split("&");
            String action = findRawParam(rawPairs, "Action");
            String user = findRawParam(rawPairs, "User");
            String dateTime = findRawParam(rawPairs, "X-Amz-Date");
            String expires = findRawParam(rawPairs, "X-Amz-Expires");
            String credential = findRawParam(rawPairs, "X-Amz-Credential");
            String signedHeaders = findRawParam(rawPairs, "X-Amz-SignedHeaders");
            String signature = findRawParam(rawPairs, "X-Amz-Signature");

            if (!"connect".equals(action) || dateTime == null || expires == null
                    || credential == null || signedHeaders == null || signature == null) {
                LOG.debugv("IAM token missing required SigV4 parameters");
                return false;
            }

            if (expectedUsername != null && user != null && !expectedUsername.equals(user)) {
                LOG.debugv("IAM token user mismatch: expected={0}, got={1}",
                        expectedUsername, user);
                return false;
            }

            Instant tokenTime = Instant.from(DATETIME_FMT.parse(dateTime));
            int expirySeconds = Integer.parseInt(expires);
            if (Instant.now().isAfter(tokenTime.plusSeconds(expirySeconds))) {
                LOG.debugv("IAM token expired");
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

            // Deliberately no fallback for a bare 12-digit account ID here: trusting an
            // unregistered numeric access key paired with the well-known "test" secret would
            // let any client forge an IAM-auth token for an arbitrary account and authenticate
            // as any matching cache user. An unregistered key falls back to itself, unchanged
            // (never a valid secret), so it always fails the signature check below.
            String secretKey = iamService.findSecretKey(accessKeyId).orElse(accessKeyId);

            String canonicalQueryString = Arrays.stream(rawPairs)
                    .filter(p -> !rawParamName(p).equals("X-Amz-Signature"))
                    .sorted((a, b) -> rawParamName(a).compareTo(rawParamName(b)))
                    .collect(Collectors.joining("&"));

            String canonicalRequest = "GET\n/\n"
                    + canonicalQueryString + "\n"
                    + "host:" + clusterId + "\n\n"
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
                LOG.debugv("IAM token signature mismatch for accessKey={0}", accessKeyId);
            }
            return valid;

        } catch (Exception e) {
            LOG.debugv("IAM token validation error: {0}", e.getMessage());
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

    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hexEncode(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
