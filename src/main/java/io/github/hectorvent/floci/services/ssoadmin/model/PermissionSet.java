package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public record PermissionSet(String arn, String name, String description, String sessionDuration,
                            Map<String, String> managedPolicies,
                            Map<String, CustomerManagedPolicyReference> customerManagedPolicies,
                            String inlinePolicy,
                            PermissionsBoundary permissionsBoundary,
                            Map<String, String> tags) {
    public PermissionSet {
        managedPolicies = managedPolicies == null ? new java.util.LinkedHashMap<>() : managedPolicies;
        customerManagedPolicies = customerManagedPolicies == null ? new java.util.LinkedHashMap<>() : customerManagedPolicies;
        tags = tags == null ? new java.util.LinkedHashMap<>() : tags;
    }
}
