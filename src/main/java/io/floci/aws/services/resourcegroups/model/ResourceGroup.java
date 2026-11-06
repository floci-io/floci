package io.floci.aws.services.resourcegroups.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public record ResourceGroup(String name, String arn, String description, Map<String, String> tags,
                            List<Map<String, Object>> configuration, List<String> members, String generation) {
    public ResourceGroup withMembers(List<String> updated) {
        return new ResourceGroup(name, arn, description, tags, configuration, List.copyOf(updated), generation);
    }
}
