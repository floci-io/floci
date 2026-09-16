package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public record SsoApplication(
        String applicationAccount,
        String applicationArn,
        String applicationProviderArn,
        long createdDateEpochMillis,
        String createdFrom,
        String description,
        String identityStoreArn,
        String instanceArn,
        String name,
        ApplicationPortalOptions portalOptions,
        String status,
        Map<String, String> tags) {
    public SsoApplication {
        tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
