package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the clientTokens of completed deletes so a replayed delete on an
 * already-deleted resource returns the same DELETING marker instead of 404ing,
 * the way AWS's control planes do.
 *
 * <p>AWS does not document an idempotency window for the AgentCore delete
 * operations themselves, so this follows the window AWS documents for a
 * clientToken elsewhere (for example DynamoDB's ImportTable and SageMaker
 * APIs): valid for 8 hours after the request that used it, after which a
 * repeat is treated as a new request. A token past that window behaves as if
 * it were never recorded, both when it is looked up and, so this does not
 * grow without bound, whenever another token is recorded.
 */
final class DeletedTokenLedger {

    static final Duration TTL = Duration.ofHours(8);

    private final Map<String, Instant> recordedAt = new ConcurrentHashMap<>();
    private final Clock clock;

    DeletedTokenLedger(Clock clock) {
        this.clock = clock;
    }

    /** Records {@code key} as deleted just now, first purging any tokens past their TTL. */
    void record(String key) {
        purgeExpired();
        recordedAt.put(key, Instant.now(clock));
    }

    /** Whether {@code key} was recorded within its TTL. An expired entry is dropped and treated as absent. */
    boolean contains(String key) {
        Instant at = recordedAt.get(key);
        if (at == null) {
            return false;
        }
        if (isExpired(at)) {
            recordedAt.remove(key, at);
            return false;
        }
        return true;
    }

    private void purgeExpired() {
        recordedAt.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    private boolean isExpired(Instant at) {
        return at.plus(TTL).isBefore(Instant.now(clock));
    }
}
