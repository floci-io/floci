package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateAddonRequest(
        String addonVersion,
        String serviceAccountRoleArn,
        String resolveConflicts,
        String clientRequestToken,
        String configurationValues,
        List<AddonPodIdentityAssociation> podIdentityAssociations
) {}
