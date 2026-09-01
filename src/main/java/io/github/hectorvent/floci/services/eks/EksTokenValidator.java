package io.github.hectorvent.floci.services.eks;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Validates the SigV4-presigned STS request embedded in an EKS IAM bearer token.
 */
@ApplicationScoped
class EksTokenValidator {

    private static final Logger LOG = Logger.getLogger(EksTokenValidator.class);
    private static final String TOKEN_PREFIX = "k8s-aws-v1.";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String EMPTY_PAYLOAD_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String REQUIRED_SIGNED_HEADERS = "host;x-k8s-aws-id";
    private static final int MAX_PRESIGN_EXPIRY_SECONDS = 60;
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final DateTimeFormatter DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final IamService iamService;
    private final Clock clock;

    @Inject
    EksTokenValidator(IamService iamService) {
        this(iamService, Clock.systemUTC());
    }

    EksTokenValidator(IamService iamService, Clock clock) {
        this.iamService = iamService;
        this.clock = clock;
    }

    boolean validate(String token, String clusterName) {
        if (token == null || clusterName == null || clusterName.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return false;
        }

        try {
            URI request = parseToken(token);
            if (request == null) {
                return false;
            }

            Map<String, String> parameters = parseQuery(request.getRawQuery());
            if (!hasExpectedRequestShape(request, parameters)) {
                return false;
            }

            CredentialScope scope = parseCredentialScope(parameters.get("X-Amz-Credential"));
            Instant signedAt = Instant.from(DATETIME_FORMAT.parse(parameters.get("X-Amz-Date")));
            if (!isCurrent(signedAt, parameters.get("X-Amz-Expires"))
                    || !scope.date().equals(parameters.get("X-Amz-Date").substring(0, 8))) {
                return false;
            }

            String secretKey = secretKey(scope.accessKeyId(), parameters.get("X-Amz-Security-Token"));
            if (secretKey == null) {
                return false;
            }

            String canonicalRequest = canonicalRequest(request, parameters, clusterName);
            String stringToSign = ALGORITHM + "\n"
                    + parameters.get("X-Amz-Date") + "\n"
                    + scope.value() + "\n"
                    + sha256Hex(canonicalRequest);
            String expectedSignature = hexEncode(hmacSha256(
                    deriveSigningKey(secretKey, scope.date(), scope.region(), scope.service()), stringToSign));
            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    parameters.get("X-Amz-Signature").getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            LOG.debugv("EKS IAM token validation rejected a malformed token: {0}", exception.getMessage());
            return false;
        }
    }

    private URI parseToken(String token) {
        if (!token.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        String encodedRequest = token.substring(TOKEN_PREFIX.length());
        if (encodedRequest.isBlank()) {
            return null;
        }
        String decodedRequest = new String(Base64.getUrlDecoder().decode(encodedRequest), StandardCharsets.UTF_8);
        URI request = URI.create(decodedRequest);
        if (!"http".equals(request.getScheme()) && !"https".equals(request.getScheme())) {
            return null;
        }
        if (request.getHost() == null || request.getRawAuthority() == null || request.getUserInfo() != null
                || request.getRawFragment() != null || !"/".equals(request.getRawPath())) {
            return null;
        }
        return request;
    }

    private static boolean hasExpectedRequestShape(URI request, Map<String, String> parameters) {
        return request.getRawQuery() != null
                && "GetCallerIdentity".equals(parameters.get("Action"))
                && "2011-06-15".equals(parameters.get("Version"))
                && ALGORITHM.equals(parameters.get("X-Amz-Algorithm"))
                && REQUIRED_SIGNED_HEADERS.equals(parameters.get("X-Amz-SignedHeaders"))
                && parameters.containsKey("X-Amz-Credential")
                && parameters.containsKey("X-Amz-Date")
                && parameters.containsKey("X-Amz-Expires")
                && parameters.containsKey("X-Amz-Signature");
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            int delimiter = pair.indexOf('=');
            if (delimiter <= 0) {
                throw new IllegalArgumentException("query parameter has no value");
            }
            String name = decode(pair.substring(0, delimiter));
            String value = decode(pair.substring(delimiter + 1));
            if (parameters.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("query parameter appears more than once");
            }
        }
        return parameters;
    }

    private boolean isCurrent(Instant signedAt, String expiryText) {
        int expirySeconds = Integer.parseInt(expiryText);
        if (expirySeconds <= 0 || expirySeconds > MAX_PRESIGN_EXPIRY_SECONDS) {
            return false;
        }
        Instant now = clock.instant();
        return !signedAt.isAfter(now.plus(MAX_FUTURE_SKEW)) && !now.isAfter(signedAt.plusSeconds(expirySeconds));
    }

    private String secretKey(String accessKeyId, String sessionToken) {
        if (iamService.isSeededDeployerAccessKey(accessKeyId)) {
            return null;
        }
        Optional<String> secretKey = iamService.findSecretKey(accessKeyId, sessionToken);
        return secretKey.orElse(null);
    }

    private static CredentialScope parseCredentialScope(String encodedCredential) {
        String[] parts = encodedCredential.split("/", -1);
        if (parts.length != 5 || parts[0].isBlank() || !parts[1].matches("[0-9]{8}")
                || parts[2].isBlank() || !"sts".equals(parts[3]) || !"aws4_request".equals(parts[4])) {
            throw new IllegalArgumentException("invalid credential scope");
        }
        return new CredentialScope(parts[0], parts[1], parts[2], parts[3]);
    }

    private static String canonicalRequest(URI request, Map<String, String> parameters, String clusterName) {
        String canonicalQuery = parameters.entrySet().stream()
                .filter(parameter -> !"X-Amz-Signature".equals(parameter.getKey()))
                .map(parameter -> awsEncode(parameter.getKey()) + "=" + awsEncode(parameter.getValue()))
                .sorted(Comparator.naturalOrder())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        String authority = request.getRawAuthority().toLowerCase(Locale.ROOT);
        return "GET\n/\n"
                + canonicalQuery + "\n"
                + "host:" + authority + "\n"
                + "x-k8s-aws-id:" + clusterName + "\n\n"
                + REQUIRED_SIGNED_HEADERS + "\n"
                + EMPTY_PAYLOAD_SHA256;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String awsEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = Byte.toUnsignedInt(valueByte);
            if ((unsigned >= 'A' && unsigned <= 'Z') || (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-' || unsigned == '_'
                    || unsigned == '.' || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(String.format("%02X", unsigned));
            }
        }
        return encoded.toString();
    }

    private static byte[] deriveSigningKey(String secretKey, String date, String region, String service)
            throws Exception {
        byte[] dateKey = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] regionKey = hmacSha256(dateKey, region);
        byte[] serviceKey = hmacSha256(regionKey, service);
        return hmacSha256(serviceKey, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) throws Exception {
        return hexEncode(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder encoded = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            encoded.append(String.format("%02x", value));
        }
        return encoded.toString();
    }

    private record CredentialScope(String accessKeyId, String date, String region, String service) {
        private String value() {
            return date + "/" + region + "/" + service + "/aws4_request";
        }
    }
}
