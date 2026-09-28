package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@ApplicationScoped
public class PreSignedUrlGenerator {

    private static final int MAX_PRESIGN_EXPIRY_SECONDS = 604800;
    private static final ObjectMapper POLICY_MAPPER = new ObjectMapper();
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
    private final Clock clock;
    private final ConcurrentMap<CredentialKey, TemporaryCredential> temporaryCredentials =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RetiredCredential> retiredCredentials = new ConcurrentHashMap<>();
    private final AtomicBoolean expiredSessionsSwept = new AtomicBoolean();

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
        this(secret, defaultExpiry, false, "us-east-1", "000000000000", null, null, new SecureRandom()); // partition-literal: test-shaped constructor default
    }

    PreSignedUrlGenerator(String secret, int defaultExpiry, boolean validateSignatures) {
        this(secret, defaultExpiry, validateSignatures, "us-east-1", "000000000000", // partition-literal: test-shaped constructor default
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
        this(secret, defaultExpiry, validateSignatures, defaultRegion, defaultAccountId,
                iamService, requestContextInstance, random, Clock.systemUTC());
    }

    PreSignedUrlGenerator(String secret, int defaultExpiry, boolean validateSignatures, String defaultRegion,
                          String defaultAccountId, IamService iamService,
                          Instance<RequestContext> requestContextInstance, SecureRandom random, Clock clock) {
        this.secret = secret;
        this.defaultExpiry = defaultExpiry;
        this.validateSignatures = validateSignatures;
        this.defaultRegion = defaultRegion;
        this.defaultAccountId = defaultAccountId;
        this.iamService = iamService;
        this.requestContextInstance = requestContextInstance;
        this.random = random;
        this.clock = clock;
    }

    private SigningIdentity resolveSigningIdentity(String region) {
        String accountId = defaultAccountId;
        if (requestContextInstance != null) {
            try {
                RequestContext requestContext = requestContextInstance.get();
                if (requestContext.getAccountId() != null && !requestContext.getAccountId().isBlank()) {
                    accountId = requestContext.getAccountId();
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
        return generatePresignedUrl(baseUrl, bucket, key, method, expiresSeconds, defaultRegion);
    }

    /**
     * Signs with {@code region} as the credential-scope region: the request's own, once one
     * process serves several partitions.
     */
    public String generatePresignedUrl(String baseUrl, String bucket, String key,
                                         String method, int expiresSeconds, String region) {
        int expiry = expiresSeconds > 0 ? expiresSeconds : defaultExpiry;
        if (expiry < 1 || expiry > MAX_PRESIGN_EXPIRY_SECONDS) {
            throw new IllegalArgumentException("Presigned URL expiry must be between 1 and 604800 seconds");
        }
        Instant signedAt = clock.instant();
        String amzDate = AMZ_DATE_FORMAT.format(signedAt);

        if (iamService == null) {
            return generateLegacyPresignedUrl(baseUrl, bucket, key, method, expiry, amzDate, region);
        }

        SigningIdentity identity = resolveSigningIdentity(region);
        TemporaryCredential temporaryCredential = temporaryCredential(identity, bucket, key, method, signedAt, expiry);
        String date = amzDate.substring(0, 8);
        String credentialScope = date + "/" + identity.region() + "/s3/aws4_request";
        String credential = temporaryCredential.accessKeyId() + "/" + credentialScope;

        URI baseUri = URI.create(baseUrl);
        String authority = baseUri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            throw new IllegalArgumentException("Pre-signed URL base must include an authority: " + baseUrl);
        }

        String path = "/" + PreSignedUrlFilter.awsUriEncode(bucket) + "/" + awsUriEncodePath(key);
        MultivaluedHashMap<String, String> queryParams = new MultivaluedHashMap<>();
        queryParams.putSingle("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        queryParams.putSingle("X-Amz-Credential", credential);
        queryParams.putSingle("X-Amz-Date", amzDate);
        queryParams.putSingle("X-Amz-Expires", Integer.toString(expiry));
        queryParams.putSingle("X-Amz-Security-Token", temporaryCredential.sessionToken());
        queryParams.putSingle("X-Amz-SignedHeaders", "host");
        String canonicalQuery = PreSignedUrlFilter.buildCanonicalQueryString(queryParams);
        String canonicalRequest = S3PresignedCanonicalRequest.build(
                method, path, canonicalQuery, "host", header -> authority, "UNSIGNED-PAYLOAD");
        String signature;
        try {
            String stringToSign = S3PresignedCanonicalRequest.stringToSign(
                    amzDate, credentialScope, canonicalRequest);
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
                                                String method, int expiry, String amzDate, String region) {
        String accountId = resolveSigningIdentity(region).accountId();
        String credential = accountId + "/" + amzDate.substring(0, 8)
                + "/" + region + "/s3/aws4_request";

        String signature = computeLegacySignature(method, bucket, key, amzDate, expiry);

        return baseUrl + "/" + bucket + "/" + key
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=" + PreSignedUrlFilter.awsUriEncode(credential)
                + "&X-Amz-Date=" + amzDate
                + "&X-Amz-Expires=" + expiry
                + "&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=" + signature;
    }

    private TemporaryCredential temporaryCredential(SigningIdentity identity, String bucket, String key,
                                                    String method, Instant signedAt, int expiry) {
        // The in-memory retirement list cannot survive a restart. Sweep persisted expired
        // sessions once on first use, then retire this process's replacements as they expire.
        if (expiredSessionsSwept.compareAndSet(false, true)) {
            try {
                iamService.sweepExpiredSessions(signedAt);
            } catch (RuntimeException failure) {
                expiredSessionsSwept.set(false);
                throw failure;
            }
        }
        // Previously issued URLs can still use a replaced credential. Sweep it only after expiry.
        retiredCredentials.forEach((accessKeyId, retired) -> {
            if (retired.credential().expiration().isBefore(signedAt)
                    && retiredCredentials.remove(accessKeyId, retired)) {
                iamService.unregisterSession(retired.accountId(), accessKeyId);
            }
        });
        temporaryCredentials.forEach((keyToRemove, credential) -> {
            if (credential.expiration().isBefore(signedAt)
                    && temporaryCredentials.remove(keyToRemove, credential)) {
                iamService.unregisterSession(keyToRemove.accountId(), credential.accessKeyId());
            }
        });
        // The signed timestamp is second-granular. Keep the credential until the URL's
        // final second, but never for the old seven-day floor.
        Instant requiredExpiration = signedAt.truncatedTo(ChronoUnit.SECONDS).plusSeconds(expiry + 1L);
        CredentialKey cacheKey = new CredentialKey(identity.accountId(), identity.region(), method, bucket, key);
        return temporaryCredentials.compute(cacheKey, (ignored, existing) -> {
            if (existing != null && !existing.expiration().isBefore(requiredExpiration)
                    && iamService.findSecretKey(existing.accessKeyId(), existing.sessionToken())
                            .filter(existing.secretAccessKey()::equals).isPresent()) {
                return existing;
            }
            TemporaryCredential created = new TemporaryCredential(
                    randomAccessKeyId(), randomUrlSafeValue(30), randomUrlSafeValue(48), requiredExpiration);
            String action = presignedAction(method);
            String resource = S3PublicAccessEvaluator.objectArn(
                    AwsRegions.partitionFor(identity.region()), bucket, key);
            iamService.registerPresignedUrlSession(
                    identity.accountId(),
                    created.accessKeyId(),
                    created.secretAccessKey(),
                    created.sessionToken(),
                    created.expiration(),
                    scopedPolicy(method, action, resource), action, resource);
            if (existing != null) {
                if (existing.expiration().isBefore(signedAt)) {
                    iamService.unregisterSession(identity.accountId(), existing.accessKeyId());
                } else {
                    retiredCredentials.put(existing.accessKeyId(),
                            new RetiredCredential(identity.accountId(), existing));
                }
            }
            return created;
        });
    }

    private static String presignedAction(String method) {
        return switch (method) {
            case "GET", "HEAD" -> "s3:GetObject";
            case "PUT" -> "s3:PutObject";
            case "DELETE" -> "s3:DeleteObject";
            default -> throw new IllegalArgumentException("Unsupported S3 presigned URL method: " + method);
        };
    }

    private static String scopedPolicy(String method, String action, String resource) {
        try {
            // S3 also checks GetObject for a conditional PutObject with If-Match. The
            // presigned scope still restricts the credential to the URL's PUT operation.
            Object actions = "PUT".equals(method)
                    ? List.of(action, "s3:GetObject") : action;
            return POLICY_MAPPER.writeValueAsString(Map.of(
                    "Version", "2012-10-17",
                    "Statement", List.of(Map.of("Effect", "Allow", "Action", actions, "Resource", resource))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode S3 presigned session policy", e);
        }
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

    private record CredentialKey(String accountId, String region, String method, String bucket, String key) {
    }

    private record TemporaryCredential(String accessKeyId, String secretAccessKey,
                                       String sessionToken, Instant expiration) {
    }

    private record RetiredCredential(String accountId, TemporaryCredential credential) {
    }
}
