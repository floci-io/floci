package io.floci.aws.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

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
        // createdDateEpochMillis is null for permission sets persisted before it was recorded; see
        // SsoAdminService#createdDateEpochMillis for the date they report.
    }
}
