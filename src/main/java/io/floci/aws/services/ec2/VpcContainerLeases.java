package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.net.Cidr4;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The VPC addresses one service's containers hold on their VPC's Docker network. A container joins the network of a
 * subnet's VPC at an address EC2 leases from that subnet, the way an AWS service places a network interface in a
 * subnet it was given, so it reaches what the VPC holds by private address, and gives the address back once Docker no
 * longer has the container. The service answers its {@link VpcDependencyProvider} questions from
 * {@link #holdsSubnet} and {@link #holdsVpc}, as AWS refuses to delete a subnet or VPC a network interface is in.
 *
 * <p>Leases live in memory: a container kept running across a restart takes its address back with
 * {@link #reclaim}. A container Docker could not be shown to be gone keeps its address, and is looked at again
 * whenever the service is asked whether it holds a subnet or VPC. Without Docker-backed VPC networks, or for a subnet
 * EC2 does not know, a container keeps the network it was created on.
 */
public final class VpcContainerLeases {

    private static final Logger LOG = Logger.getLogger(VpcContainerLeases.class);

    private final Ec2Service ec2;
    private final String owner;
    private final Supplier<DockerClient> docker;
    /** The address each container holds, by container id. */
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    /** Containers whose removal Docker did not confirm, whose addresses wait for another look. */
    private final Set<String> unconfirmed = ConcurrentHashMap.newKeySet();

    /**
     * @param ec2    EC2, which leases the addresses; null leaves every container on its own network
     * @param owner  what the containers belong to, for the log, such as {@code "CodeBuild build"}
     * @param docker the Docker client that says whether a container is gone
     */
    public VpcContainerLeases(Ec2Service ec2, String owner, Supplier<DockerClient> docker) {
        this.ec2 = ec2;
        this.owner = owner;
        this.docker = docker;
    }

    /** An address a container holds in a subnet of a VPC, for the account and Region that own them. */
    public record Lease(String accountId, String region, String vpcId, String subnetId, String address) {
    }

    /**
     * Puts the container on the VPC network of {@code subnetId}'s VPC at an address leased from the subnet. The
     * subnet is checked and the lease recorded while EC2 holds its subnet references, so a DeleteSubnet cannot pass
     * its dependency check between the two. Runs as the account and Region named, since a service often launches
     * outside the request that asked for it. Empty when EC2 does not know the subnet, the subnet is not in
     * {@code expectedVpcId} (when one is given), EC2 has no address to lease, or the network refuses the address,
     * which is then given back, as it is when attaching fails.
     *
     * @param expectedVpcId the VPC the resource names beside its subnets, or null when it names none
     * @param name          the resource the container serves, for the log
     */
    public Optional<Lease> join(String accountId, String region, String expectedVpcId, String subnetId,
                                String containerId, String name) {
        if (ec2 == null || subnetId == null) {
            return Optional.empty();
        }
        AtomicReference<Lease> joined = new AtomicReference<>();
        RequestScopes.runAs(accountId, region, () -> ec2.runWithSubnetReferencesHeld(region, () -> {
            String vpcId = vpcOf(region, subnetId);
            if (vpcId == null) {
                return;
            }
            if (expectedVpcId != null && !expectedVpcId.equals(vpcId)) {
                LOG.warnv("{0} {1} names VPC {2}, but its subnet {3} is in {4}; it stays off both", owner, name,
                        expectedVpcId, subnetId, vpcId);
                return;
            }
            Optional<String> address = ec2.allocateVpcPrivateIp(region, subnetId);
            if (address.isEmpty()) {
                return;
            }
            Optional<String> attached;
            try {
                attached = ec2.attachContainerToVpc(region, vpcId, subnetId, containerId, address.get());
            } catch (RuntimeException e) {
                ec2.releaseVpcPrivateIp(region, subnetId, address.get());
                throw e;
            }
            if (attached.isPresent()) {
                Lease lease = new Lease(accountId, region, vpcId, subnetId, address.get());
                leases.put(containerId, lease);
                joined.set(lease);
                LOG.infov("{0} {1} joined VPC {2} at {3}", owner, name, vpcId, address.get());
            } else {
                ec2.releaseVpcPrivateIp(region, subnetId, address.get());
            }
        }));
        return Optional.ofNullable(joined.get());
    }

    /**
     * Re-claims the address a container still holds in {@code subnetId} after Floci restarted with the container
     * kept running: leases live in memory, so without this EC2 could hand the same address to another resource and
     * Docker would refuse it. {@code addresses} are the container's addresses on its networks; the first inside the
     * subnet is taken back.
     *
     * @param name the resource the container serves, for the log
     */
    public Optional<Lease> reclaim(String accountId, String region, String subnetId, String containerId,
                                   Collection<String> addresses, String name) {
        if (ec2 == null || subnetId == null) {
            return Optional.empty();
        }
        return RequestScopes.callAs(accountId, region, () -> {
            List<Subnet> subnets = ec2.describeSubnets(region, List.of(subnetId), Map.of());
            Optional<Cidr4> cidr = subnets.size() == 1 ? Cidr4.parse(subnets.getFirst().getCidrBlock())
                    : Optional.empty();
            if (cidr.isEmpty()) {
                return Optional.empty();
            }
            for (String address : addresses) {
                Optional<Cidr4> host = address == null ? Optional.empty() : Cidr4.parse(address + "/32");
                if (host.isPresent() && cidr.get().containsAddress(host.get().network())
                        && ec2.reserveVpcPrivateIp(region, subnetId, address)) {
                    Lease lease = new Lease(accountId, region, subnets.getFirst().getVpcId(), subnetId, address);
                    leases.put(containerId, lease);
                    LOG.infov("{0} {1} re-claimed its VPC address {2}", owner, name, address);
                    return Optional.of(lease);
                }
            }
            return Optional.empty();
        });
    }

    /**
     * Gives the container's address back once Docker no longer has the container. A removal that failed (it logs and
     * carries on), or a Docker that cannot answer, leaves the address possibly held, so the lease is kept rather than
     * offered to someone else, and looked at again when the service is next asked about the subnet or VPC.
     */
    public void releaseIfGone(String containerId) {
        Lease lease = containerId == null ? null : leases.get(containerId);
        if (lease == null) {
            if (containerId != null) {
                unconfirmed.remove(containerId);
            }
            return;
        }
        try {
            docker.get().inspectContainerCmd(containerId).exec();
            unconfirmed.add(containerId);
            LOG.warnv("{0} container {1} is still present after removal; it keeps VPC address {2}", owner,
                    containerId, lease.address());
        } catch (NotFoundException expected) {
            // Gone, so its address is free.
            unconfirmed.remove(containerId);
            if (leases.remove(containerId, lease)) {
                ec2.releaseVpcPrivateIp(lease.region(), lease.subnetId(), lease.address());
            }
        } catch (RuntimeException e) {
            unconfirmed.add(containerId);
            LOG.warnv("{0} container {1}: Docker could not confirm it is gone ({2}); it keeps VPC address {3}",
                    owner, containerId, e.getMessage(), lease.address());
        }
    }

    /** The address the container holds, if it joined a VPC network. */
    public Optional<Lease> lease(String containerId) {
        return Optional.ofNullable(containerId == null ? null : leases.get(containerId));
    }

    /** Whether a live container of the account holds an address in the subnet. */
    public boolean holdsSubnet(String accountId, String region, String subnetId) {
        recheckUnconfirmed();
        return leases.values().stream().anyMatch(lease -> lease.accountId().equals(accountId)
                && lease.region().equals(region) && lease.subnetId().equals(subnetId));
    }

    /** Whether a live container of the account is on the VPC's network. */
    public boolean holdsVpc(String accountId, String region, String vpcId) {
        recheckUnconfirmed();
        return leases.values().stream().anyMatch(lease -> lease.accountId().equals(accountId)
                && lease.region().equals(region) && lease.vpcId().equals(vpcId));
    }

    /** Gives back the addresses of containers whose removal was not confirmed and that Docker no longer has. */
    private void recheckUnconfirmed() {
        for (String containerId : List.copyOf(unconfirmed)) {
            releaseIfGone(containerId);
        }
    }

    private String vpcOf(String region, String subnetId) {
        List<Subnet> subnets = ec2.describeSubnets(region, List.of(subnetId), Map.of());
        return subnets.size() == 1 ? subnets.getFirst().getVpcId() : null;
    }
}
