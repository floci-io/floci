package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.CustomResourceLiveness;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the futures that custom-resource ResponseURL callbacks complete.
 *
 * <p>The wait budget is an <em>idle</em> timeout, not a total one. A CDK provider-framework waiter
 * drives {@code framework.isComplete} on a fixed cadence and PUTs only once the work is done, so the
 * total time is a property of the work rather than of the emulator — {@code
 * Custom::CreateOrganizationAccounts} creates one account per poll, meaning a 15-account org takes
 * five times as long as a 3-account one. Budgeting total time therefore needs re-tuning per config
 * set, and overshooting guillotines a Lambda that was succeeding. Budgeting idleness needs no
 * knowledge of the work at all: each poll is liveness, so progress buys unbounded wall-clock while a
 * resource that has genuinely stopped still fails on schedule.
 */
@ApplicationScoped
public class CustomResourceResponseStore implements CustomResourceLiveness {
    private static final Logger LOG = Logger.getLogger(CustomResourceResponseStore.class);
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    /** A waiting caller: the future its callback completes, and when it last saw progress. */
    private static final class Pending {
        private final CompletableFuture<JsonNode> future = new CompletableFuture<>();
        // nanoTime, not wall-clock: this measures an elapsed interval and must not jump with NTP.
        private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
    }

    /** Registers a fresh token and returns it. Call before invoking the Lambda. */
    public String register() {
        String token = UUID.randomUUID().toString();
        pending.put(token, new Pending());
        return token;
    }

    /** Completes the future for {@code token} with the PUT body. No-op for unknown/expired tokens. */
    public void complete(String token, JsonNode response) {
        Pending entry = pending.get(token);
        if (entry != null) {
            entry.future.complete(response);
        } else {
            LOG.debugv("Received custom-resource response for unknown token {0}", token);
        }
    }

    /**
     * Records liveness for {@code token}, resetting its idle budget.
     *
     * <p>Unknown tokens are ignored by design: polls routinely outlive the await that registered
     * them (after a timeout or a rollback), and that tail is normal rather than an error.
     */
    @Override
    public void touch(String token) {
        Pending entry = pending.get(token);
        if (entry != null) {
            entry.lastActivity.set(System.nanoTime());
        }
    }

    /**
     * Waits for the Lambda's response, then discards the token.
     *
     * @param idleTimeout how long to tolerate <em>no</em> progress; each {@link #touch} restarts it,
     *                    so a resource that keeps polling is never cut off
     * @throws TimeoutException if no response and no liveness arrive within {@code idleTimeout}
     */
    public JsonNode await(String token, Duration idleTimeout) throws TimeoutException {
        Pending entry = pending.get(token);
        if (entry == null) {
            throw new IllegalStateException("No pending custom-resource token: " + token);
        }
        long idleNanos = idleTimeout.toNanos();
        try {
            while (true) {
                long remaining = idleNanos - (System.nanoTime() - entry.lastActivity.get());
                if (remaining <= 0) {
                    throw new TimeoutException("No custom-resource activity for " + idleTimeout);
                }
                try {
                    return entry.future.get(remaining, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    // This slice elapsed. A concurrent touch may have pushed lastActivity forward,
                    // in which case the next slice is positive again and the wait continues; if
                    // nothing touched, the guard above fails on the very next pass.
                }
            }
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Interrupted awaiting custom-resource response", e);
        } finally {
            pending.remove(token);
        }
    }
}
