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
     * Finds an external cluster node instance by account, region, and instance ID.
     *
     * @param accountId the AWS account ID (must not be {@code null})
     * @param region the AWS region (or {@code null} to match any region)
     * @param instanceId the EC2 instance ID
     * @return the matching instance, or empty if not found
     */
    Optional<Instance> findInstance(String accountId, String region, String instanceId);

    /**
     * Lists external cluster node instances for the given account and region.
     *
     * @param accountId the AWS account ID (must not be {@code null})
     * @param region the AWS region (or {@code null} for all regions)
     * @return list of cluster node instances
     */
    List<Instance> listInstances(String accountId, String region);
}
