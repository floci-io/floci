package io.github.hectorvent.floci.services.ses.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * An S/MIME certificate association created by {@code AssociateEmailIdentityCertificate}.
 * {@code fromAddress} keeps the spelling the caller gave; SES matches it without regard to case.
 */
@RegisterForReflection
public record IdentityCertificate(String emailIdentity,
                                  String fromAddress,
                                  String certificateArn,
                                  Instant associatedTimestamp) {
}
