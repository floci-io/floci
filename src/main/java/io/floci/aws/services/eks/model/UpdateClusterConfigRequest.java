package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The members of UpdateClusterConfig that floci applies. The REST controller refuses any other member; the service's
 * other callers build this record directly.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateClusterConfigRequest(
        ResourcesVpcConfig resourcesVpcConfig,
        Logging logging,
        AccessConfig accessConfig,
        String clientRequestToken
) {}
