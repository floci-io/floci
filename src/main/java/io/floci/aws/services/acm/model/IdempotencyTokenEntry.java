package io.floci.aws.services.acm.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Entry for idempotency token cache with lazy expiration.
 *
 * <p>Design decision: Using ConcurrentHashMap with timestamp-based entries
 * instead of Caffeine or scheduled cleanup. This matches moto's approach
 * (see moto/acm/models.py:478-505) where expired entries are removed on
 * lookup. No background thread needed - acceptable for emulator workloads.</p>
 *
 * @param arn The certificate ARN associated with this token
 * @param expires Expiration instant (1 hour after creation)
 * @param requestHash Hash of original request parameters for validation
 * @see <a href="https://github.com/getmoto/moto/blob/main/moto/acm/models.py">moto ACM</a>
 */
public record IdempotencyTokenEntry(
    String arn,
    Instant expires,
    int requestHash
) {
    private static final Duration TTL = Duration.ofHours(1);

    /**
     * Creates a new idempotency token entry with 1-hour TTL from current time.
     *
     * @param arn The certificate ARN
     * @param requestHash Hash of the request parameters
     * @return New entry with expiration set to current time + 1 hour
     */
    public static IdempotencyTokenEntry create(String arn, int requestHash) {
        return new IdempotencyTokenEntry(arn, Instant.now().plus(TTL), requestHash);
    }

    /**
     * Checks if this token entry has expired.
     *
     * @return true if the current time is after the expiration instant
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expires);
    }
}
