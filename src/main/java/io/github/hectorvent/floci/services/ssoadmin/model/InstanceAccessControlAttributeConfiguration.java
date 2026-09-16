package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public record InstanceAccessControlAttributeConfiguration(
        String instanceArn,
        List<AccessControlAttribute> accessControlAttributes,
        String status,
        String statusReason) {
    public InstanceAccessControlAttributeConfiguration {
        accessControlAttributes = accessControlAttributes == null ? new ArrayList<>() : accessControlAttributes;
    }
}
