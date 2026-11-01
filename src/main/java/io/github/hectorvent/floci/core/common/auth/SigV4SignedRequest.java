package io.github.hectorvent.floci.core.common.auth;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * A SigV4 signature as a request carries it, in the {@code Authorization} header or in a presigned
 * query, and the limits AWS puts on its age. Shared by {@code ExecuteApiSigV4Authorizer} (API Gateway)
 * and {@code LambdaWebIamAuth} (Lambda Web Functions). Each caller decides which placement wins when a
 * request carries both, and where it reads the session token.
 */
public record SigV4SignedRequest(String credential, String signedHeaders, String signature, String amzDate,
                                 Long expiresSeconds, boolean presigned) {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    /** AWS refuses a signing time more than five minutes from its own clock. */
    public static final long MAX_CLOCK_SKEW_SECONDS = 300;
    /** The longest {@code X-Amz-Expires} AWS accepts, seven days. */
    public static final long MAX_PRESIGNED_EXPIRY_SECONDS = 604800;
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** How the signing time compares with the limits. */
    public enum Freshness {
        FRESH,
        /** A presigned {@code X-Amz-Expires} that is missing, not a number, or out of range. */
        BAD_EXPIRES,
        EXPIRED,
        /** Signed more than five minutes ahead of the clock. */
        NOT_YET_VALID,
        MALFORMED_DATE
    }

    /** The signature in an {@code Authorization} header, or null when it is not a complete SigV4 one. */
    public static SigV4SignedRequest fromHeader(String authorization, String amzDate) {
        return fromHeader(authorization, amzDate, ALGORITHM);
    }

    /** The signature in an {@code Authorization} header of {@code algorithm}, or null when it is not complete. */
    public static SigV4SignedRequest fromHeader(String authorization, String amzDate, String algorithm) {
        SigV4AuthorizationHeader parsed = SigV4AuthorizationHeader.parse(authorization, algorithm);
        if (parsed == null || isBlank(parsed.credential()) || isBlank(parsed.signedHeaders())
                || isBlank(parsed.signature()) || isBlank(amzDate)) {
            return null;
        }
        return new SigV4SignedRequest(parsed.credential(), parsed.signedHeaders().toLowerCase(Locale.ROOT),
                parsed.signature(), amzDate, null, false);
    }

    /**
     * The presigned signature in a query, read through {@code parameter} as decoded values, or null
     * when it is not complete. It does not check {@code X-Amz-Algorithm}.
     */
    public static SigV4SignedRequest fromQuery(UnaryOperator<String> parameter) {
        String credential = parameter.apply("X-Amz-Credential");
        String signedHeaders = parameter.apply("X-Amz-SignedHeaders");
        String signature = parameter.apply("X-Amz-Signature");
        String amzDate = parameter.apply("X-Amz-Date");
        if (isBlank(credential) || isBlank(signedHeaders) || isBlank(signature) || isBlank(amzDate)) {
            return null;
        }
        return new SigV4SignedRequest(credential, signedHeaders.toLowerCase(Locale.ROOT), signature, amzDate,
                expiresSeconds(parameter.apply("X-Amz-Expires")), true);
    }

    private static Long expiresSeconds(String expires) {
        if (expires == null) {
            return null;
        }
        try {
            return Long.valueOf(expires.trim());
        } catch (NumberFormatException expected) {
            // A value that is not a number is reported as BAD_EXPIRES.
            return null;
        }
    }

    /** The signing time, or null when {@code amzDate} is not in the SigV4 form. */
    public Instant signedAt() {
        try {
            return Instant.from(AMZ_DATE.parse(amzDate));
        } catch (DateTimeException expected) {
            // Reported as MALFORMED_DATE.
            return null;
        }
    }

    /** Whether the signature is within the time AWS allows at {@code now}. */
    public Freshness freshness(Instant now) {
        Instant signedAt = signedAt();
        if (signedAt == null) {
            return Freshness.MALFORMED_DATE;
        }
        if (presigned) {
            if (expiresSeconds == null || expiresSeconds < 1 || MAX_PRESIGNED_EXPIRY_SECONDS < expiresSeconds) {
                return Freshness.BAD_EXPIRES;
            }
            if (now.isAfter(signedAt.plusSeconds(expiresSeconds))) {
                return Freshness.EXPIRED;
            }
            return signedAt.isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS)) ? Freshness.NOT_YET_VALID : Freshness.FRESH;
        }
        if (Math.abs(now.getEpochSecond() - signedAt.getEpochSecond()) > MAX_CLOCK_SKEW_SECONDS) {
            return signedAt.isAfter(now) ? Freshness.NOT_YET_VALID : Freshness.EXPIRED;
        }
        return Freshness.FRESH;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
