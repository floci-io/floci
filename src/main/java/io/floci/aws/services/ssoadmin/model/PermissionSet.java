package io.floci.aws.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public record PermissionSet(String arn, String name, String description, String sessionDuration,
                            Map<String, String> managedPolicies,
                            Map<String, CustomerManagedPolicyReference> customerManagedPolicies,
                            String inlinePolicy,
                            PermissionsBoundary permissionsBoundary,
                            Map<String, String> tags,
                            Long createdDateEpochMillis) {
    public PermissionSet {
        managedPolicies = managedPolicies == null ? new LinkedHashMap<>() : managedPolicies;
        customerManagedPolicies = customerManagedPolicies == null ? new LinkedHashMap<>() : customerManagedPolicies;
        tags = tags == null ? new LinkedHashMap<>() : tags;
        // Permission sets persisted before CreatedDate was recorded have none; AWS always
        // returns one, so give them the time they are first loaded. The next write persists it.
        createdDateEpochMillis = createdDateEpochMillis == null ? Instant.now().toEpochMilli() : createdDateEpochMillis;
    }
}
