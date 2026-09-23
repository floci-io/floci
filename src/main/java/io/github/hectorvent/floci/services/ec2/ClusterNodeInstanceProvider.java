package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;

import java.util.List;
import java.util.Optional;

/**
 * Supplies EC2 instances synthesized by external cluster management services (such as EKS cluster nodes).
 *
 * <p>Implemented by cluster management services; consumed lazily by {@code Ec2Service}
 * via CDI {@code Instance<ClusterNodeInstanceProvider>} so EC2 never depends directly
 * on EKS or other cluster management packages.</p>
 */
public interface ClusterNodeInstanceProvider {

    /**
     * Finds an external cluster node instance by region and instance ID.
     *
     * @param region the AWS region (or {@code null} to match any region)
     * @param instanceId the EC2 instance ID
     * @return the matching instance, or empty if not found
     */
    Optional<Instance> findInstance(String region, String instanceId);

    /**
     * Lists all external cluster node instances for the given region.
     *
     * @param region the AWS region (or {@code null} for all regions)
     * @return list of cluster node instances
     */
    List<Instance> listInstances(String region);
}
