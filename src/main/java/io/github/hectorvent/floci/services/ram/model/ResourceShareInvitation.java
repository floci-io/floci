package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * One invitation sent to an AWS account principal directly associated with a resource share
 * (not an organization/OU principal, which never needs one: see {@link
 * io.github.hectorvent.floci.services.ram.RamService}). {@code status} is one of
 * {@code PENDING}, {@code ACCEPTED}, {@code REJECTED}; floci does not model time-based
 * {@code EXPIRED} transitions.
 */
@RegisterForReflection
public record ResourceShareInvitation(
        String resourceShareInvitationArn,
        String resourceShareArn,
        String resourceShareName,
        String senderAccountId,
        String receiverAccountId,
        Instant invitationTimestamp,
        String status) {

    public ResourceShareInvitation withStatus(String newStatus) {
        return new ResourceShareInvitation(resourceShareInvitationArn, resourceShareArn, resourceShareName,
                senderAccountId, receiverAccountId, invitationTimestamp, newStatus);
    }
}
