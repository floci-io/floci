package io.github.hectorvent.floci.services.ses.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * The SES v2 {@code StatusRecord} of a reputation entity: a {@code SendingStatus} (ENABLED /
 * REINSTATED / DISABLED), its cause and when it last changed.
 */
@RegisterForReflection
public record ReputationStatusRecord(String status, String cause, Instant lastUpdatedTimestamp) {
}
