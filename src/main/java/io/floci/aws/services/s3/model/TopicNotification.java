package io.floci.aws.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record TopicNotification(String id, String topicArn, List<String> events, List<FilterRule> filterRules) {

    public TopicNotification(String id, String topicArn, List<String> events) {
        this(id, topicArn, events, List.of());
    }

    public boolean matchesKey(String key) {
        return filterRules == null || filterRules.isEmpty() || filterRules.stream().allMatch(r -> r.matches(key));
    }
}
