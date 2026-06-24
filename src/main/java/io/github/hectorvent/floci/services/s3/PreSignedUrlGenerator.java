package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@ApplicationScoped
public class PreSignedUrlGenerator {

    private static final DateTimeFormatter AMZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String secret;
    private final int defaultExpiry;
    private final boolean validateSignatures;
    private final String defaultRegion;
    private final String defaultAccountId;

    // Field-injected so the package-private test constructors remain valid
    @Inject
    Instance<RequestContext> requestContextInstance;

    @Inject
    public PreSignedUrlGenerator(EmulatorConfig config) {
        this(config.auth().presignSecret(),
             config.services().s3().defaultPresignExpirySeconds(),
             config.auth().validateSignatures(),
             config.defaultRegion(),
             config.defaultAccountId());
    }

    /** Package-private constructor for testing. */
    PreSignedUrlGenerator(String secret, int defaultExpiry) {
        this(secret, defaultExpiry, false, "us-east-1", "000000000000");
    }

    PreSignedUrlGenerator(String secret, int defaultExpiry, boolean validateSignatures) {
        this(secret, defaultExpiry, validateSignatures, "us-east-1", "000000000000");
    }

    PreSignedUrlGenerator(String secret, int defaultExpiry, boolean validateSignatures, String defaultRegion) {
        this(secret, defaultExpiry, validateSignatures, defaultRegion, "000000000000");
    }

    PreSignedUrlGenerator(String secret, int defaultExpiry, boolean validateSignatures, String defaultRegion, String defaultAccountId) {
        this.secret = secret;
        this.defaultExpiry = defaultExpiry;
        this.validateSignatures = validateSignatures;
        this.defaultRegion = defaultRegion;
        this.defaultAccountId = defaultAccountId;
    }

    private String resolveAccessKeyId() {
        if (requestContextInstance != null) {
            try {
                String accountId = requestContextInstance.get().getAccountId();
                if (accountId != null) {
                    return accountId;
                }
            } catch (ContextNotActiveException ignored) {
                // outside request scope — fall through to default
            }
        }
        return defaultAccountId;
    }

    public boolean shouldValidateSignatures() {
        return validateSignatures;
    }

    public String generatePresignedUrl(String baseUrl, String bucket, String key,
                                         String method, int expiresSeconds) {
        int expiry = expiresSeconds > 0 ? expiresSeconds : defaultExpiry;
        String amzDate = AMZ_DATE_FORMAT.format(Instant.now());
        String credential = resolveAccessKeyId() + "/" + amzDate.substring(0, 8) + "/" + defaultRegion + "/s3/aws4_request";

        String signature = computeSignature(method, bucket, key, amzDate, expiry);

        return baseUrl + "/" + bucket + "/" + key
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=" + URLEncoder.encode(credential, StandardCharsets.UTF_8)
                + "&X-Amz-Date=" + amzDate
                + "&X-Amz-Expires=" + expiry
                + "&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=" + signature;
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
        String expected = computeSignature(method, bucket, key, amzDate, expiresSeconds);
        return expected.equals(signature);
    }

    private String computeSignature(String method, String bucket, String key,
                                      String amzDate, int expiresSeconds) {
        String stringToSign = method + "\n" + bucket + "/" + key + "\n" + amzDate + "\n" + expiresSeconds;
        return hmacSha256(secret, stringToSign);
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }
}
