package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record LambdaNotification(String id, String functionArn, List<String> events, List<FilterRule> filterRules) {

    public LambdaNotification(String id, String functionArn, List<String> events) {
        this(id, functionArn, events, List.of());
    }

    public boolean matchesKey(String key) {
        return filterRules == null || filterRules.isEmpty() || filterRules.stream().allMatch(r -> r.matches(key));
    }
}
