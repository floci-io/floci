package io.floci.aws.services.macie2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record MacieMember(
        String accountId,
        String administratorAccountId,
        String masterAccountId,
        String arn,
        String email,
        String invitedAt,
        String relationshipStatus,
        Map<String, String> tags,
        String updatedAt) {
}
