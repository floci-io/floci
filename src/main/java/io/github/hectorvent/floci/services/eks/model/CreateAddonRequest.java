package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateAddonRequest(
        String addonName,
        String addonVersion,
        String serviceAccountRoleArn,
        String resolveConflicts,
        String clientRequestToken,
        String configurationValues,
        Map<String, String> tags,
        List<AddonPodIdentityAssociation> podIdentityAssociations
) {}
