package io.floci.aws.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record QueueNotification(String id, String queueArn, List<String> events, List<FilterRule> filterRules) {

    public QueueNotification(String id, String queueArn, List<String> events) {
        this(id, queueArn, events, List.of());
    }

    public boolean matchesKey(String key) {
        return filterRules == null || filterRules.isEmpty() || filterRules.stream().allMatch(r -> r.matches(key));
    }
}
