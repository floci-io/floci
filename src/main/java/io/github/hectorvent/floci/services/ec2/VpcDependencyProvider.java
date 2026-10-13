package io.github.hectorvent.floci.services.ec2;

/**
 * Implemented by services whose resources reference EC2 subnets or VPCs, so DeleteSubnet and
 * DeleteVpc refuse to remove one that is still in use. Answers come from the owning service's
 * current, account-scoped state, as plain reads that take no lock of that service.
 *
 * <p>A service records a reference inside {@link Ec2Service#supplyWithSubnetReferencesHeld}, which
 * holds the lock EC2 holds while it asks these questions and deletes, so a delete cannot pass its
 * check while a reference is being published.
 */
public interface VpcDependencyProvider {
    boolean hasSubnetDependency(String region, String subnetId);

    boolean hasVpcDependency(String region, String vpcId);
}
