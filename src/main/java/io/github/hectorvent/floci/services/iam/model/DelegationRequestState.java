package io.github.hectorvent.floci.services.iam.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Duration;

/**
 * The states a delegation request moves through, the {@code delegationRequestStateType} enum.
 *
 * <p>The names are the wire values, so a stored request round-trips through JSON as the string the
 * API answers with.
 *
 * <p>Each state carries the window AWS gives it: a request nobody has claimed expires after a day,
 * everything else after a week. {@code FINALIZED} and {@code REJECTED} are terminal for the
 * lifecycle operations and still expire, which is why they have a window rather than none.
 */
@RegisterForReflection
public enum DelegationRequestState {

    UNASSIGNED(Duration.ofDays(1)),
    ASSIGNED(Duration.ofDays(7)),
    PENDING_APPROVAL(Duration.ofDays(7)),
    ACCEPTED(Duration.ofDays(7)),
    FINALIZED(Duration.ofDays(7)),
    REJECTED(Duration.ofDays(7)),
    EXPIRED(null);

    private final Duration window;

    DelegationRequestState(Duration window) {
        this.window = window;
    }

    /**
     * How long a request sits in this state before it expires, or null for {@code EXPIRED}, which
     * is where the clock stops.
     */
    public Duration window() {
        return window;
    }
}
