package io.github.hectorvent.floci.services.rds.proxy;

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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Validates RDS IAM auth tokens (SigV4 presigned URLs).
 * RDS tokens sign {@code host:port} in the canonical host header, unlike ElastiCache
 * which signs only the cluster hostname. The token format is:
 * {@code hostname:port/?Action=connect&DBUser=user&X-Amz-*=...}
 */
@ApplicationScoped
public class RdsSigV4Validator {

    private static final Logger LOG = Logger.getLogger(RdsSigV4Validator.class);
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Well-known local-dev credential pair used pervasively by default AWS SDK clients
     * (AwsBasicCredentials.create("test", "test")) against this emulator, mirrored from the
     * identical fallback in S3Service/PreSignedUrlFilter. Deliberately not a fallback for any
     * other unregistered access key -- only this exact, already-public pair is honored.
     */
    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";

    private final IamService iamService;

    @Inject
    public RdsSigV4Validator(IamService iamService) {
        this.iamService = iamService;
    }

    /**
     * Validates an RDS IAM auth token.
     * The token is a presigned URL without the scheme, e.g.:
     * {@code hostname:port/?Action=connect&DBUser=admin&X-Amz-Signature=...}
     *
     * @param token the presigned URL token
     * @param clientUsername the username from the PostgreSQL startup message;
     *                       must match the {@code DBUser} in the token
     * @return true if the token signature is valid, the DBUser matches, and the token is not expired
     */
    public boolean validate(String token, String clientUsername) {
        try {
            URI uri = URI.create("http://" + token);
            String host = uri.getHost();
            int port = uri.getPort();
            String rawQuery = uri.getRawQuery();

            if (host == null || rawQuery == null) {
                LOG.debugv("RDS IAM token missing host or query string");
                return false;
            }

            // RDS tokens sign host:port in the canonical host header
            String authority = (port > 0) ? host + ":" + port : host;

            String[] rawPairs = rawQuery.split("&");
            String action = findRawParam(rawPairs, "Action");
            String dbUser = findRawParam(rawPairs, "DBUser");
            String dateTime = findRawParam(rawPairs, "X-Amz-Date");
            String expires = findRawParam(rawPairs, "X-Amz-Expires");
            String credential = findRawParam(rawPairs, "X-Amz-Credential");
            String signedHeaders = findRawParam(rawPairs, "X-Amz-SignedHeaders");
            String signature = findRawParam(rawPairs, "X-Amz-Signature");

            if (!"connect".equals(action) || dbUser == null || dateTime == null || expires == null
                    || credential == null || signedHeaders == null || signature == null) {
                LOG.debugv("RDS IAM token missing required SigV4 parameters");
                return false;
            }

            if (clientUsername != null && !clientUsername.equals(dbUser)) {
                LOG.debugv("RDS IAM token DBUser mismatch: client={0}, token={1}",
                        clientUsername, dbUser);
                return false;
            }

            Instant tokenTime = Instant.from(DATETIME_FMT.parse(dateTime));
            int expirySeconds = Integer.parseInt(expires);
            if (Instant.now().isAfter(tokenTime.plusSeconds(expirySeconds))) {
                LOG.debugv("RDS IAM token expired");
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
                Optional<String> registeredSecretKey = iamService.findSecretKey(accessKeyId);
                if (registeredSecretKey.isEmpty()) {
                    LOG.debugv("RDS IAM token references unregistered access key={0}", sanitizeForLog(accessKeyId));
                    return false;
                }
                secretKey = registeredSecretKey.get();
            }

            // Canonical query string: sorted pairs, excluding X-Amz-Signature
            String canonicalQueryString = Arrays.stream(rawPairs)
                    .filter(p -> !rawParamName(p).equals("X-Amz-Signature"))
                    .sorted((a, b) -> rawParamName(a).compareTo(rawParamName(b)))
                    .collect(Collectors.joining("&"));

            // Canonical request: RDS uses host:port as the host header value
            String canonicalRequest = "GET\n/\n"
                    + canonicalQueryString + "\n"
                    + "host:" + authority + "\n\n"
                    + "host\n"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // sha256("")

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
                LOG.debugv("RDS IAM token signature mismatch for accessKey={0}", sanitizeForLog(accessKeyId));
            }
            return valid;

        } catch (Exception e) {
            LOG.debugv("RDS IAM token validation error: {0}", e.getMessage());
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
    private static String sanitizeForLog(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "");
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
