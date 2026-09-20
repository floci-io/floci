package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PodIdentityAssociation(
        String clusterName,
        String namespace,
        String serviceAccount,
        String roleArn,
        String associationArn,
        String associationId,
        Map<String, String> tags,
        double createdAt,
        double modifiedAt,
        String ownerArn,
        String targetRoleArn,
        Boolean disableSessionTags,
        String externalId,
        String policy
) {}
