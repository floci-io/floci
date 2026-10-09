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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
            List<QueryParameter> parameters = decodeQuery(rawQuery);
            String action = findParam(parameters, "Action");
            String identity = findParam(parameters, identityParamName);
            String dateTime = findParam(parameters, "X-Amz-Date");
            String expires = findParam(parameters, "X-Amz-Expires");
            String credential = findParam(parameters, "X-Amz-Credential");
            String signedHeaders = findParam(parameters, "X-Amz-SignedHeaders");
            String signature = findParam(parameters, "X-Amz-Signature");

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

            String[] credParts = credential.split("/");
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
                Optional<String> registeredSecretKey = iamService.findSecretKey(
                        accessKeyId, findParam(parameters, "X-Amz-Security-Token"));
                if (registeredSecretKey.isEmpty()) {
                    LOG.debugv("{0} references unregistered access key={1}", logLabel, sanitizeForLog(accessKeyId));
                    return false;
                }
                secretKey = registeredSecretKey.get();
            }

            String canonicalRequest = "GET\n/\n"
                    + canonicalQueryString(parameters) + "\n"
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

    private record QueryParameter(String name, String value) {}

    /**
     * The value of {@code name} in a token's query string, read as {@link #validate} reads it: the
     * first parameter whose percent-decoded name matches, its value decoded once. A caller that acts
     * on a parameter after validating the token reads it here, so it acts on what was verified: a
     * name spelled with a percent-encoded character, or a parameter given twice, cannot make it read
     * a different credential than the one the signature was checked with.
     */
    public static String queryParameter(String rawQuery, String name) {
        return findParam(decodeQuery(rawQuery), name);
    }

    /**
     * Every parameter of the token's query string, its name and value percent-decoded exactly once.
     * A session token is drawn from an alphabet that includes {@code +}, {@code /} and {@code =}, and
     * the presigner writes it percent-encoded once; decoding it again would turn every {@code +}
     * into a space, and a literal {@code %2B} into a {@code +}.
     */
    private static List<QueryParameter> decodeQuery(String rawQuery) {
        List<QueryParameter> parameters = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            parameters.add(new QueryParameter(
                    decodeQueryComponent(eq >= 0 ? pair.substring(0, eq) : pair),
                    decodeQueryComponent(eq >= 0 ? pair.substring(eq + 1) : "")));
        }
        return parameters;
    }

    private static String findParam(List<QueryParameter> parameters, String name) {
        for (QueryParameter parameter : parameters) {
            if (parameter.name().equals(name)) {
                return parameter.value();
            }
        }
        return null;
    }

    /**
     * SigV4's canonical query string: every parameter but {@code X-Amz-Signature}, its name and
     * value each URI-encoded, sorted by encoded name and then by encoded value. Built from the
     * decoded parameters rather than the bytes on the wire, so it is the string the signer hashed
     * whichever valid percent-encoding the token travelled in.
     */
    private static String canonicalQueryString(List<QueryParameter> parameters) {
        return parameters.stream()
                .filter(parameter -> !"X-Amz-Signature".equals(parameter.name()))
                .map(parameter -> new QueryParameter(uriEncode(parameter.name()), uriEncode(parameter.value())))
                .sorted(Comparator.comparing(QueryParameter::name).thenComparing(QueryParameter::value))
                .map(parameter -> parameter.name() + "=" + parameter.value())
                .collect(Collectors.joining("&"));
    }

    /**
     * Percent-decodes a query-string component without {@code URLDecoder}'s form-encoding rule that
     * a literal {@code +} means a space: SigV4 signers escape a space as {@code %20}, so a raw
     * {@code +} on the wire is a plus sign.
     */
    private static String decodeQueryComponent(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * SigV4's UriEncode: every byte of the UTF-8 form except {@code A-Z a-z 0-9 - . _ ~} becomes
     * {@code %XY} with uppercase hex, a space included ({@code %20}, never {@code +}) and {@code /}
     * included.
     */
    private static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = Byte.toUnsignedInt(raw);
            if ((unsigned >= 'A' && unsigned <= 'Z') || (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-' || unsigned == '.'
                    || unsigned == '_' || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(String.format("%02X", unsigned));
            }
        }
        return encoded.toString();
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

    /** Whether {@code name} appears among the semicolon-separated headers of {@code SignedHeaders}. */
    public static boolean containsHeader(String signedHeaders, String name) {
        for (String header : signedHeaders.split(";")) {
            if (name.equals(header)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code value} looks like a SHA-256 hex digest: 64 hex characters. */
    public static boolean isSha256Hex(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
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
