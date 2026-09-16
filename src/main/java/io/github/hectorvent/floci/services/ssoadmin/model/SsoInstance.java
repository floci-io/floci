package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public record SsoInstance(
        String instanceArn,
        String identityStoreId,
        String name,
        String ownerAccountId,
        String primaryRegion,
        long createdDateEpochMillis,
        String status,
        String statusReason,
        boolean accountInstance,
        Map<String, String> tags) {
    public SsoInstance {
        tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
