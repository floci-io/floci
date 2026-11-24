package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The members of UpdateClusterConfig that floci applies. The REST controller refuses any other member.
 * {@code computeConfig}, {@code storageConfig} and {@code kubernetesNetworkConfig} turn EKS Auto Mode on or off
 * together, and of {@code kubernetesNetworkConfig} only {@code elasticLoadBalancing} is applied.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateClusterConfigRequest(
        ResourcesVpcConfig resourcesVpcConfig,
        Logging logging,
        AccessConfig accessConfig,
        UpgradePolicy upgradePolicy,
        String clientRequestToken,
        ComputeConfig computeConfig,
        StorageConfig storageConfig,
        KubernetesNetworkConfig kubernetesNetworkConfig
) {

    /** As above, with no EKS Auto Mode change. */
    public UpdateClusterConfigRequest(ResourcesVpcConfig resourcesVpcConfig, Logging logging,
                                      AccessConfig accessConfig, UpgradePolicy upgradePolicy,
                                      String clientRequestToken) {
        this(resourcesVpcConfig, logging, accessConfig, upgradePolicy, clientRequestToken, null, null, null);
    }

    /** Whether the request names any of the EKS Auto Mode members. */
    public boolean changesAutoMode() {
        return computeConfig != null || storageConfig != null || kubernetesNetworkConfig != null;
    }
}
