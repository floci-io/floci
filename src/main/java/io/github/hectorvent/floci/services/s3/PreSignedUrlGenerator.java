package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class PreSignedUrlGenerator {

    private static final int MAX_PRESIGN_EXPIRY_SECONDS = 604800;
    private static final char[] ACCESS_KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final DateTimeFormatter AMZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String secret;
    private final int defaultExpiry;
    private final boolean validateSignatures;
    private final String defaultRegion;
    private final String defaultAccountId;
    private final IamService iamService;
    private final Instance<RequestContext> requestContextInstance;
    private final SecureRandom random;
    private final ConcurrentMap<CredentialKey, TemporaryCredential> temporaryCredentials =
            new ConcurrentHashMap<>();

    @Inject
    public PreSignedUrlGenerator(EmulatorConfig config, IamService iamService,
                                 Instance<RequestContext> requestContextInstance) {
        this(config.auth().presignSecret(),
             config.services().s3().defaultPresignExpirySeconds(),
             config.auth().validateSignatures(),
             config.defaultRegion(),
             config.defaultAccountId(),
             iamService,
             requestContextInstance,
             new SecureRandom());
    }

    /** Package-private constructor for testing. */
    PreSignedUrlGenerator(String secret, int defaultExpiry) {
        this(secret, defaultExpiry, false, "us-east-1", "000000000000", null, null, new SecureRandom());
    }

    PreSignedUrlGenerator(String secret, int defaultExpiry, boolean validateSignatures) {
        this(secret, defaultExpiry, validateSignatures, "us-east-1", "000000000000",
                null, null, new SecureRandom());
    }

    PreSignedUrlGenerator(String secret, int defaultExpiry, boolean validateSignatures, String defaultRegion) {
        this(secret, defaultExpiry, validateSignatures, defaultRegion, "000000000000",
                null, null, new SecureRandom());
    }

    PreSignedUrlGenerator(String secret, int defaultExpiry, boolean validateSignatures, String defaultRegion,
                          String defaultAccountId) {
        this(secret, defaultExpiry, validateSignatures, defaultRegion, defaultAccountId,
                null, null, new SecureRandom());
    }

    PreSignedUrlGenerator(String secret, int defaultExpiry, boolean validateSignatures, String defaultRegion,
                          String defaultAccountId, IamService iamService,
                          Instance<RequestContext> requestContextInstance, SecureRandom random) {
        this.secret = secret;
        this.defaultExpiry = defaultExpiry;
        this.validateSignatures = validateSignatures;
        this.defaultRegion = defaultRegion;
        this.defaultAccountId = defaultAccountId;
        this.iamService = iamService;
        this.requestContextInstance = requestContextInstance;
        this.random = random;
    }

    private SigningIdentity resolveSigningIdentity() {
        String accountId = defaultAccountId;
        String region = defaultRegion;
        if (requestContextInstance != null) {
            try {
                RequestContext requestContext = requestContextInstance.get();
                if (requestContext.getAccountId() != null && !requestContext.getAccountId().isBlank()) {
                    accountId = requestContext.getAccountId();
                }
                if (requestContext.getRegion() != null && !requestContext.getRegion().isBlank()) {
                    region = requestContext.getRegion();
                }
            } catch (ContextNotActiveException ignored) {
                // Direct callers outside HTTP request scope use the configured defaults.
            }
        }
        return new SigningIdentity(accountId, region);
    }

    public boolean shouldValidateSignatures() {
        return validateSignatures;
    }

    public String generatePresignedUrl(String baseUrl, String bucket, String key,
                                         String method, int expiresSeconds) {
        int expiry = expiresSeconds > 0 ? expiresSeconds : defaultExpiry;
        Instant signedAt = Instant.now();
        String amzDate = AMZ_DATE_FORMAT.format(signedAt);

        if (iamService == null) {
            return generateLegacyPresignedUrl(baseUrl, bucket, key, method, expiry, amzDate);
        }

        SigningIdentity identity = resolveSigningIdentity();
        TemporaryCredential temporaryCredential = temporaryCredential(identity, signedAt, expiry);
        String date = amzDate.substring(0, 8);
        String credentialScope = date + "/" + identity.region() + "/s3/aws4_request";
        String credential = temporaryCredential.accessKeyId() + "/" + credentialScope;

        URI baseUri = URI.create(baseUrl);
        String authority = baseUri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            throw new IllegalArgumentException("Pre-signed URL base must include an authority: " + baseUrl);
        }

        String path = "/" + PreSignedUrlFilter.awsUriEncode(bucket) + "/" + awsUriEncodePath(key);
        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        queryParams.put("X-Amz-Credential", credential);
        queryParams.put("X-Amz-Date", amzDate);
        queryParams.put("X-Amz-Expires", Integer.toString(expiry));
        queryParams.put("X-Amz-Security-Token", temporaryCredential.sessionToken());
        queryParams.put("X-Amz-SignedHeaders", "host");
        String canonicalQuery = queryParams.entrySet().stream()
                .map(entry -> PreSignedUrlFilter.awsUriEncode(entry.getKey())
                        + "=" + PreSignedUrlFilter.awsUriEncode(entry.getValue()))
                .sorted()
                .collect(Collectors.joining("&"));

        String canonicalRequest = method + "\n"
                + path + "\n"
                + canonicalQuery + "\n"
                + "host:" + authority + "\n\n"
                + "host\n"
                + "UNSIGNED-PAYLOAD";
        String signature;
        try {
            String stringToSign = "AWS4-HMAC-SHA256\n"
                    + amzDate + "\n"
                    + credentialScope + "\n"
                    + SigV4RequestValidator.sha256Hex(canonicalRequest);
            byte[] signingKey = SigV4RequestValidator.deriveSigningKey(
                    temporaryCredential.secretAccessKey(), date, identity.region(), "s3");
            signature = SigV4RequestValidator.hexEncode(
                    SigV4RequestValidator.hmacSha256(signingKey, stringToSign));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute S3 SigV4 pre-signed URL", e);
        }

        String normalizedBaseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return normalizedBaseUrl + path + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
    }

    private String generateLegacyPresignedUrl(String baseUrl, String bucket, String key,
                                                String method, int expiry, String amzDate) {
        String credential = defaultAccountId + "/" + amzDate.substring(0, 8)
                + "/" + defaultRegion + "/s3/aws4_request";

        String signature = computeLegacySignature(method, bucket, key, amzDate, expiry);

        return baseUrl + "/" + bucket + "/" + key
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=" + PreSignedUrlFilter.awsUriEncode(credential)
                + "&X-Amz-Date=" + amzDate
                + "&X-Amz-Expires=" + expiry
                + "&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=" + signature;
    }

    private TemporaryCredential temporaryCredential(SigningIdentity identity, Instant signedAt, int expiry) {
        Instant requiredExpiration = signedAt.plusSeconds(expiry);
        CredentialKey cacheKey = new CredentialKey(identity.accountId(), identity.region());
        return temporaryCredentials.compute(cacheKey, (ignored, existing) -> {
            if (existing != null && !existing.expiration().isBefore(requiredExpiration)) {
                return existing;
            }
            Instant expiration = signedAt.plusSeconds(Math.max(expiry, MAX_PRESIGN_EXPIRY_SECONDS));
            TemporaryCredential created = new TemporaryCredential(
                    randomAccessKeyId(), randomUrlSafeValue(30), randomUrlSafeValue(48), expiration);
            iamService.registerSessionForAccount(
                    identity.accountId(),
                    created.accessKeyId(),
                    created.secretAccessKey(),
                    created.sessionToken(),
                    null,
                    created.expiration(),
                    null);
            return created;
        });
    }

    private String randomAccessKeyId() {
        StringBuilder value = new StringBuilder("ASIA");
        for (int index = 0; index < 16; index++) {
            value.append(ACCESS_KEY_ALPHABET[random.nextInt(ACCESS_KEY_ALPHABET.length)]);
        }
        return value.toString();
    }

    private String randomUrlSafeValue(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String awsUriEncodePath(String value) {
        return Arrays.stream(value.split("/", -1))
                .map(PreSignedUrlFilter::awsUriEncode)
                .collect(Collectors.joining("/"));
    }

    public boolean isExpired(String amzDate, int expiresSeconds) {
        try {
            Instant signedAt = Instant.from(AMZ_DATE_FORMAT.parse(amzDate));
            return Instant.now().isAfter(signedAt.plusSeconds(expiresSeconds));
        } catch (Exception e) {
            return true;
        }
    }

    public boolean verifySignature(String method, String bucket, String key,
                                     String amzDate, int expiresSeconds, String signature) {
        String expected = computeLegacySignature(method, bucket, key, amzDate, expiresSeconds);
        return expected.equals(signature);
    }

    private String computeLegacySignature(String method, String bucket, String key,
                                           String amzDate, int expiresSeconds) {
        String stringToSign = method + "\n" + bucket + "/" + key + "\n" + amzDate + "\n" + expiresSeconds;
        return hmacSha256(secret, stringToSign);
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    private record SigningIdentity(String accountId, String region) {
    }

    private record CredentialKey(String accountId, String region) {
    }

    private record TemporaryCredential(String accessKeyId, String secretAccessKey,
                                       String sessionToken, Instant expiration) {
    }
}
