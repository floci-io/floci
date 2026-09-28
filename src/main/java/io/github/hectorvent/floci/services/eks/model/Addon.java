package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Addon(
        String addonArn,
        String addonName,
        String addonVersion,
        String clusterName,
        String status,
        AddonHealth health,
        double createdAt,
        double modifiedAt,
        String serviceAccountRoleArn,
        String configurationValues,
        Map<String, String> tags,
        List<String> podIdentityAssociations,
        String owner,
        String publisher
) {}
