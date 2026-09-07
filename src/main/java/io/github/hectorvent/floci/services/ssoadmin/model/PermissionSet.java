package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public record PermissionSet(String arn, String name, String description, String sessionDuration,
                            Map<String, String> managedPolicies, String inlinePolicy) {}
