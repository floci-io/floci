package io.github.hectorvent.floci.services.guardduty.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record MemberAccount(String accountId, String email, String relationshipStatus,
                            String administratorId, String detectorId, String invitedAt, String updatedAt) {}
