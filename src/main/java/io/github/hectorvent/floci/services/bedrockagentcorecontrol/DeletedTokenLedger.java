package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers the clientTokens of completed deletes so a replayed delete on an
 * already-deleted resource returns the same DELETING marker instead of 404ing,
 * the way AWS's control planes do.
 *
 * <p>AWS does not document an idempotency window for the AgentCore delete
 * operations themselves, so this follows the window AWS documents for a
 * clientToken elsewhere (for example DynamoDB's ImportTable and SageMaker
 * APIs): valid for 8 hours after the first request that used it, after which
 * a repeat is treated as a new request. A token past that window behaves as if
 * it were never recorded, both when it is looked up and, so this does not
 * grow without bound, whenever another token is recorded. Tokens are kept
 * in the order they were first recorded, one entry per token, so purge only
 * visits the expired ones at the front instead of scanning every live token.
 */
final class DeletedTokenLedger {

    static final Duration TTL = Duration.ofHours(8);

    private final LinkedHashMap<String, Instant> recordedAt = new LinkedHashMap<>();
    private final Clock clock;

    DeletedTokenLedger(Clock clock) {
        this.clock = clock;
    }

    /** Records {@code key} as deleted unless it is already live, first purging any tokens past their TTL. */
    synchronized void record(String key) {
        purgeExpired();
        // Keep the first timestamp of a live token: the window runs from the first request that
        // used it. contains drops an expired entry the front purge could not reach, for example
        // after the wall clock stepped back, so reusing it starts a fresh window.
        if (!contains(key)) {
            recordedAt.put(key, Instant.now(clock));
        }
    }

    /** Whether {@code key} was recorded within its TTL. An expired entry is dropped and treated as absent. */
    synchronized boolean contains(String key) {
        Instant at = recordedAt.get(key);
        if (at == null) {
            return false;
        }
        if (isExpired(at)) {
            recordedAt.remove(key);
            return false;
        }
        return true;
    }

    private void purgeExpired() {
        Iterator<Map.Entry<String, Instant>> oldestFirst = recordedAt.entrySet().iterator();
        while (oldestFirst.hasNext() && isExpired(oldestFirst.next().getValue())) {
            oldestFirst.remove();
        }
    }

    private boolean isExpired(Instant at) {
        return at.plus(TTL).isBefore(Instant.now(clock));
    }
}
