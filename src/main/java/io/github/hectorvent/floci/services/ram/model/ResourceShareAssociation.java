package io.github.hectorvent.floci.services.ram.model;

import java.time.Instant;

/** An active resource or principal association in an owned RAM resource share. */
public record ResourceShareAssociation(String resourceShareArn, String resourceShareName,
                                       String associatedEntity, String associationType,
                                       Instant creationTime, Instant lastUpdatedTime) {
}
