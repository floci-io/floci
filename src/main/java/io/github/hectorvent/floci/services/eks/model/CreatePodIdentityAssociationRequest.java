package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreatePodIdentityAssociationRequest(
        String clusterName,
        String namespace,
        String serviceAccount,
        String roleArn,
        String clientRequestToken,
        Map<String, String> tags,
        String targetRoleArn,
        Boolean disableSessionTags,
        String policy
) {}
