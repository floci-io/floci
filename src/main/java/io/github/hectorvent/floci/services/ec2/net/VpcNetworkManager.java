package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Network;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

/**
 * Gives every VPC a real Docker network, so an EC2 instance's private address is an address
 * something can actually connect to rather than a number invented to look plausible.
 *
 * <h2>One network per VPC, not per subnet</h2>
 *
 * <p>A Docker network is an isolation domain: containers on one can reach each other, and
 * cannot reach containers on another. That is exactly a VPC. It is <em>not</em> a subnet:
 * subnets inside a VPC route to each other by default in AWS, so giving each subnet its own
 * Docker network would manufacture a partition that AWS does not have, and would break the
 * very common two-tier "app subnet talks to database subnet" topology in this corpus.
 *
 * <p>The per-subnet addressing that AWS gets from separate subnets is preserved anyway: the
 * network is created with the whole VPC CIDR as its IPAM pool, and each subnet allocates
 * static addresses from its own slice of that pool. A subnet CIDR is by definition inside its
 * VPC CIDR, so every such address is valid on the network. The result is one bridge per VPC
 * instead of one per subnet: the correct semantics and, incidentally, a third of the Linux
 * bridges.
 *
 * <h2>Declared CIDRs are used where they can be, substituted loudly where they cannot</h2>
 *
 * <p>The declared CIDR is used verbatim whenever it is usable, which for this corpus is
 * almost always. It is not usable when it is absent, malformed, outside RFC 1918, or already
 * claimed by another Docker network, including one of Floci's own, which is what a second
 * VPC declaring the same CIDR looks like (legal in AWS, impossible on one Docker daemon).
 * Then, and only then, an equivalent block is allocated out of a configured private pool and
 * the substitution is logged at WARN. It is never silent: a reported private IP that does not
 * mean what the caller declared is exactly the class of quiet lie this whole change exists to
 * remove, so it is either true or it is in the log.
 *
 * <h2>What this does not do</h2>
 *
 * <p>Security groups and network ACLs are untouched. Docker networks isolate between
 * networks; within one, every container can reach every other on every port. Nothing here
 * makes an assertion that traffic is <em>blocked</em> meaningful.
 *
 * <p>Between-VPC isolation is likewise only as good as the daemon underneath. It comes from
 * Docker's own {@code DOCKER-ISOLATION-STAGE} rules, which drop forwarded traffic between
 * bridge networks: Floci asks for separate networks and gets whatever the daemon does with
 * them. <strong>OrbStack does not do that.</strong> Measured on OrbStack 29.4.0, 2026-08-22:
 * two containers each attached to exactly one Docker network, on different subnets, created
 * with nothing but {@code docker network create} and {@code docker run --network}, reach each
 * other's ports in both directions. Marking the networks {@code --internal} does not change
 * it. That is a property of the host's Docker implementation and there is nothing this class
 * can do about it, so on such a host the VPC boundary is an addressing boundary only. Do not
 * read a passing "different VPCs cannot reach each other" test as evidence without checking
 * which daemon it ran on.
 *
 * <h2>Hosts where container IPs are not routable</h2>
 *
 * <p>On Docker Desktop for macOS and Windows the containers live behind a VM and none of their
 * addresses answer from the host. The private address stays correct and reachable
 * container-to-container, and the guest really holds it, but a host-side client (Terratest,
 * a shell) cannot dial it. That is the same constraint {@code ContainerNetworkReachability}
 * detects for the public address on the ec2-public-address-reachability branch, and it is why
 * SSH keeps its published host port here.
 */
@ApplicationScoped
public class VpcNetworkManager {

    private static final Logger LOG = Logger.getLogger(VpcNetworkManager.class);

    /** Marks a Docker network as a Floci VPC network, for discovery and orphan reconciliation. */
    public static final String LABEL_COMPONENT = "floci_component";
    public static final String COMPONENT_VALUE = "ec2-vpc";
    public static final String LABEL_VPC_ID = "floci_vpc_id";
    public static final String LABEL_VPC_REGION = "floci_vpc_region";
    /**
     * The API port of the Floci process that created the network. Several Floci instances
     * routinely share one Docker daemon, and reconciliation deletes things; scoping it by
     * owner is what stops one instance's startup from tearing down another's live networks.
     */
    public static final String LABEL_OWNER_PORT = "floci_vpc_owner_port";

    /**
     * Instance addresses start here inside each subnet slice. AWS reserves the first four
     * addresses of a subnet and Docker takes the first as the gateway, so starting at .10
     * clears both without arithmetic that has to stay in sync with either.
     *
     * <p>Ten addresses of headroom is cheap in a /24 and impossible in a /29, which holds eight
     * addresses in total. {@link #firstHostOffset} scales the offset down for such blocks rather
     * than leaving them with nothing at all to hand out.
     */
    static final int FIRST_HOST_OFFSET = 10;

    /**
     * The smallest offset any block can start allocating at: past the network address, and past
     * the address Docker takes for the gateway.
     */
    static final int MIN_HOST_OFFSET = 2;

    /**
     * The longest prefix this manager will ever allocate a block at. A /28 is the smallest block
     * AWS itself accepts as a subnet, and about the smallest that is worth handing to a VPC:
     * past it the network, gateway and broadcast addresses are most of the block.
     */
    static final int MAX_ALLOCATABLE_PREFIX = 28;

    private final EmulatorConfig config;
    private final DockerClient dockerClient;

    /** region::vpcId -> binding. Rebuilt from persisted VPCs at startup. */
    private final Map<String, VpcBinding> bindings = new ConcurrentHashMap<>();
    /** region::subnetId -> region::vpcId, so subnet-keyed calls need no VPC lookup. */
    private final Map<String, String> subnetOwner = new ConcurrentHashMap<>();

    private final Object planLock = new Object();

    /** So a misconfigured fallback prefix is reported once, not once per VPC. */
    private final java.util.concurrent.atomic.AtomicBoolean warnedPrefixClamp =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ec2-vpc-network-teardown");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public VpcNetworkManager(EmulatorConfig config, DockerClient dockerClient) {
        this.config = config;
        this.dockerClient = dockerClient;
    }

    @PreDestroy
    void shutdown() {
        retries.shutdownNow();
    }

    public boolean enabled() {
        return config.services().ec2().vpcNetworks().enabled() && !config.services().ec2().mock();
    }

    // ─── Declaration: CreateVpc / CreateSubnet ────────────────────────────────

    /**
     * Records the address plan for a VPC. Called from CreateVpc and from default-resource
     * seeding; the Docker network itself is only created when the first instance needs it
     * (see {@link #attach}), because most roots in this corpus create VPCs they never launch
     * anything into and a Linux bridge per unused VPC is pure daemon churn.
     */
    public void declareVpc(String region, String vpcId, String declaredCidr) {
        if (!enabled() || region == null || vpcId == null) {
            return;
        }
        synchronized (planLock) {
            // Deliberately not computeIfAbsent: planVpc reads the whole binding map to detect
            // collisions, and ConcurrentHashMap forbids that from inside a mapping function.
            String vpcKey = key(region, vpcId);
            if (!bindings.containsKey(vpcKey)) {
                bindings.put(vpcKey, planVpc(region, vpcId, declaredCidr));
            }
        }
    }

    /** Records the address plan for a subnet inside an already-declared VPC. */
    public void declareSubnet(String region, String vpcId, String subnetId, String declaredCidr) {
        if (!enabled() || region == null || vpcId == null || subnetId == null) {
            return;
        }
        synchronized (planLock) {
            VpcBinding vpc = bindings.get(key(region, vpcId));
            if (vpc == null) {
                return;
            }
            if (!vpc.subnets.containsKey(subnetId)) {
                vpc.subnets.put(subnetId, planSubnet(vpc, subnetId, declaredCidr));
            }
            subnetOwner.put(key(region, subnetId), key(region, vpcId));
        }
    }

    private VpcBinding planVpc(String region, String vpcId, String declaredCidr) {
        Optional<Cidr4> declared = Cidr4.parse(declaredCidr);
        String rejection = rejectionReason(vpcId, declared, declaredCidr, List.of());
        Cidr4 effective;
        if (rejection == null) {
            effective = declared.orElseThrow();
        }
        else {
            effective = allocateFromFallbackPool(vpcId, declared.map(Cidr4::prefix).orElse(null));
            if (effective == null) {
                LOG.warnv("VPC {0} in {1}: declared CIDR {2} is unusable ({3}) and the fallback pool {4} "
                                + "is exhausted. This VPC gets no Docker network; its instances keep "
                                + "synthesised private addresses that nothing can connect to.",
                        vpcId, region, String.valueOf(declaredCidr), rejection,
                        config.services().ec2().vpcNetworks().fallbackPool());
            }
            else {
                LOG.warnv("VPC {0} in {1}: declared CIDR {2} is unusable ({3}). Substituting {4} from the "
                                + "fallback pool. Reported private IPs for this VPC will NOT match the "
                                + "declared CIDR.",
                        vpcId, region, String.valueOf(declaredCidr), rejection, effective);
            }
        }
        return new VpcBinding(region, vpcId, declared.orElse(null), effective, rejection != null,
                networkName(region, vpcId));
    }

    /**
     * @return null when the block can be used as declared, otherwise a human-readable reason
     *         it cannot, which goes verbatim into the substitution WARN.
     */
    private String rejectionReason(String vpcId, Optional<Cidr4> declared, String declaredText,
                                   Collection<Cidr4> extraTaken) {
        if (declared.isEmpty()) {
            return declaredText == null || declaredText.isBlank()
                    ? "no CIDR was declared" : "not a parseable IPv4 CIDR";
        }
        Cidr4 cidr = declared.get();
        if (!cidr.isRfc1918()) {
            return "outside RFC 1918 private space (10/8, 172.16/12, 192.168/16)";
        }
        Optional<String> clash = firstClash(vpcId, cidr, extraTaken);
        return clash.orElse(null);
    }

    private Optional<String> firstClash(String vpcId, Cidr4 candidate, Collection<Cidr4> extraTaken) {
        for (Cidr4 taken : extraTaken) {
            if (candidate.overlaps(taken)) {
                return Optional.of("overlaps an address range already in use (" + taken + ")");
            }
        }
        for (VpcBinding other : bindings.values()) {
            if (other.effective != null && candidate.overlaps(other.effective)) {
                return Optional.of("already used by VPC " + other.vpcId + " (" + other.effective
                        + "): two VPCs may declare the same CIDR in AWS, but one Docker daemon "
                        + "cannot route two identical ranges");
            }
        }
        for (Cidr4 taken : dockerNetworkSubnets(vpcId)) {
            if (candidate.overlaps(taken)) {
                return Optional.of("overlaps an existing Docker network (" + taken + ")");
            }
        }
        return Optional.empty();
    }

    private SubnetBinding planSubnet(VpcBinding vpc, String subnetId, String declaredCidr) {
        Optional<Cidr4> declared = Cidr4.parse(declaredCidr);
        List<Cidr4> siblings = vpc.subnets.values().stream().map(s -> s.effective).filter(c -> c != null).toList();

        if (vpc.effective == null) {
            return new SubnetBinding(subnetId, declared.orElse(null), null, true);
        }
        // The common, quiet path: the VPC kept its declared CIDR and the subnet sits inside it.
        if (!vpc.substituted && declared.isPresent()
                && vpc.effective.contains(declared.get())
                && siblings.stream().noneMatch(s -> s.overlaps(declared.get()))) {
            return bindSubnet(vpc, subnetId, declared.get(), declared.get(), false);
        }

        int desiredPrefix = declared.map(Cidr4::prefix).orElse(24);
        Cidr4 slice = allocateSubBlock(vpc.effective, desiredPrefix, siblings);
        if (slice == null) {
            LOG.warnv("Subnet {0} in VPC {1}: no free /{2} slice remains inside {3}; instances in this "
                            + "subnet fall back to synthesised private addresses.",
                    subnetId, vpc.vpcId, String.valueOf(desiredPrefix), vpc.effective);
            return new SubnetBinding(subnetId, declared.orElse(null), null, true);
        }
        LOG.warnv("Subnet {0} in VPC {1}: declared CIDR {2} cannot be used ({3}). Substituting {4}: "
                        + "reported private IPs for this subnet will NOT match the declared CIDR.",
                subnetId, vpc.vpcId, String.valueOf(declaredCidr),
                vpc.substituted ? "its VPC's CIDR was itself substituted for " + vpc.effective
                        : "it does not sit inside the VPC range " + vpc.effective + " or collides with a sibling subnet",
                slice);
        return bindSubnet(vpc, subnetId, declared.orElse(null), slice, true);
    }

    /**
     * Binds a subnet to a range it can actually allocate out of.
     *
     * <p>A block too small to hold one host address is refused here rather than accepted and then
     * quietly allocating nothing: a subnet with no Docker-backed range is an ordinary, handled
     * state (its instances keep synthesised addresses), whereas a subnet that claims a range and
     * then returns empty from every {@link #allocatePrivateIp} looks like exhaustion and is not.
     */
    private SubnetBinding bindSubnet(VpcBinding vpc, String subnetId, Cidr4 declared,
                                     Cidr4 effective, boolean substituted) {
        if (effective != null && usableAddressCount(effective) == 0) {
            LOG.warnv("Subnet {0} in VPC {1}: {2} is too small to hold a usable address ({3} address(es) "
                            + "in total, of which the network address, the Docker gateway and the "
                            + "broadcast address are already spoken for). Instances in this subnet fall "
                            + "back to synthesised private addresses.",
                    subnetId, vpc.vpcId, effective, String.valueOf(effective.size()));
            return new SubnetBinding(subnetId, declared, null, true);
        }
        return new SubnetBinding(subnetId, declared, effective, substituted);
    }

    /**
     * Where allocation starts inside {@code block}: the usual {@link #FIRST_HOST_OFFSET} of
     * headroom when the block can spare it, and {@link #MIN_HOST_OFFSET} when it cannot.
     */
    static long firstHostOffset(Cidr4 block) {
        return block.size() >= FIRST_HOST_OFFSET + 2 ? FIRST_HOST_OFFSET : MIN_HOST_OFFSET;
    }

    /** How many addresses {@code block} can hand to instances, once reserved ones are removed. */
    static long usableAddressCount(Cidr4 block) {
        return Math.max(0, block.size() - 1 - firstHostOffset(block));
    }

    /** First block of {@code desiredPrefix} inside {@code parent} that no member of {@code taken} overlaps. */
    static Cidr4 allocateSubBlock(Cidr4 parent, int desiredPrefix, Collection<Cidr4> taken) {
        int prefix = Math.max(desiredPrefix, parent.prefix());
        Cidr4 candidate = new Cidr4(parent.network(), prefix);
        long blocks = 1L << (prefix - parent.prefix());
        for (long i = 0; i < blocks; i++) {
            Cidr4 block = candidate.shifted(i);
            boolean clash = taken.stream().anyMatch(block::overlaps);
            if (!clash) {
                return block;
            }
        }
        return null;
    }

    private Cidr4 allocateFromFallbackPool(String vpcId, Integer declaredPrefix) {
        EmulatorConfig.VpcNetworksConfig cfg = config.services().ec2().vpcNetworks();
        Optional<Cidr4> pool = Cidr4.parse(cfg.fallbackPool());
        if (pool.isEmpty() || !pool.get().isRfc1918()) {
            LOG.errorv("floci.services.ec2.vpc-networks.fallback-pool ({0}) is not a valid RFC 1918 CIDR; "
                    + "no substitution is possible.", cfg.fallbackPool());
            return null;
        }
        int prefix = clampAllocatablePrefix(cfg.fallbackPrefixLength(), pool.get());
        if (declaredPrefix != null && declaredPrefix > prefix) {
            // A VPC that declared a /24 gets a /24: the substitute should be the size that was
            // asked for, not a /16 that wastes fifteen sixteenths of the pool per VPC.
            prefix = Math.min(declaredPrefix, MAX_ALLOCATABLE_PREFIX);
        }
        List<Cidr4> taken = new ArrayList<>(dockerNetworkSubnets(vpcId));
        bindings.values().stream().map(b -> b.effective).filter(c -> c != null).forEach(taken::add);
        return allocateSubBlock(pool.get(), prefix, taken);
    }

    /**
     * {@code floci.services.ec2.vpc-networks.fallback-prefix-length} is a plain int with no bounds
     * declared on it, and both ends of its range are useless in different ways: shorter than the
     * pool's own prefix asks for a block the pool does not contain, and longer than
     * {@link #MAX_ALLOCATABLE_PREFIX} asks for a block with no room for a host, which used to mean
     * every substituted VPC allocated exactly nothing and said nothing about it.
     *
     * <p>Neither is worth refusing to start over, since the whole fallback path is already a
     * degraded one; the value is clamped into the range that can work, and the clamp is logged.
     */
    private int clampAllocatablePrefix(int configured, Cidr4 pool) {
        int clamped = Math.min(Math.max(configured, pool.prefix()), MAX_ALLOCATABLE_PREFIX);
        if (clamped != configured && warnedPrefixClamp.compareAndSet(false, true)) {
            LOG.warnv("floci.services.ec2.vpc-networks.fallback-prefix-length is /{0}, which cannot serve "
                            + "instance addresses out of the pool {1}. Using /{2} instead; valid values run "
                            + "from the pool''s own prefix (/{3}) to /{4}.",
                    String.valueOf(configured), pool, String.valueOf(clamped),
                    String.valueOf(pool.prefix()), String.valueOf(MAX_ALLOCATABLE_PREFIX));
        }
        return clamped;
    }

    // ─── Address allocation ──────────────────────────────────────────────────

    /**
     * The next free address inside the subnet's effective range.
     *
     * @return the address, or empty when the subnet has no Docker-backed range, in which case
     *         the caller keeps its existing synthesised address rather than reporting nothing.
     */
    public Optional<String> allocatePrivateIp(String region, String subnetId) {
        if (!enabled() || subnetId == null) {
            return Optional.empty();
        }
        SubnetBinding subnet = subnetBinding(region, subnetId);
        if (subnet == null || subnet.effective == null) {
            return Optional.empty();
        }
        synchronized (subnet) {
            long limit = subnet.effective.size() - 1;
            // Sweep forward from the last hand-out first, so a fresh subnet allocates in the
            // readable order .10, .11, .12; only once the range is walked does it come back for
            // addresses released by terminated instances, which keeps a long-running survey from
            // exhausting a /24 after 245 launches.
            for (long pass = 0; pass < 2; pass++) {
                long from = pass == 0 ? subnet.nextOffset : subnet.firstOffset;
                long to = pass == 0 ? limit : Math.min(subnet.nextOffset, limit);
                for (long offset = from; offset < to; offset++) {
                    Optional<String> address = subnet.effective.addressAt(offset);
                    if (address.isPresent() && !subnet.leased.contains(address.get())) {
                        subnet.leased.add(address.get());
                        subnet.nextOffset = offset + 1;
                        return address;
                    }
                }
            }
        }
        LOG.warnv("Subnet {0} range {1} is exhausted; further instances get no Docker-backed address.",
                subnetId, subnet.effective);
        return Optional.empty();
    }

    /**
     * Claims one specific address in a subnet's range, rather than picking the next free one.
     *
     * <p>This exists for restart. The lease table lives only in memory, so a restarted emulator
     * rebuilds it empty while the containers of persisted, still-running instances go on holding
     * the addresses a previous run handed them. Re-reserving those addresses is what stops the
     * next {@link #allocatePrivateIp} from handing a live container's address to a new instance.
     *
     * <p>Refusal is normal and never exceptional: a persisted instance may sit in a subnet whose
     * effective range has since been substituted, or two persisted instances may claim the same
     * address. Both return false and leave the caller's other reservations intact: the
     * alternative is a restart that aborts on inherited state it cannot change.
     *
     * @return true when the address is now leased; false when the subnet has no Docker-backed
     *         range, the address is unparseable or outside that range, or it is already leased
     */
    public boolean reservePrivateIp(String region, String subnetId, String address) {
        if (!enabled() || subnetId == null || address == null) {
            return false;
        }
        SubnetBinding subnet = subnetBinding(region, subnetId);
        if (subnet == null || subnet.effective == null) {
            return false;
        }
        Optional<Cidr4> parsed = Cidr4.parse(address + "/32");
        if (parsed.isEmpty() || !subnet.effective.containsAddress(parsed.get().network())) {
            LOG.warnv("Cannot reserve {0} in subnet {1}: it is outside the subnet''s effective range {2}. "
                            + "Its holder keeps it, and this manager will not hand it out either, since "
                            + "it is not an address it allocates from.",
                    String.valueOf(address), subnetId, String.valueOf(subnet.effective));
            return false;
        }
        synchronized (subnet) {
            if (!subnet.leased.add(address)) {
                LOG.warnv("Address {0} in subnet {1} is claimed by more than one instance; the later "
                        + "claim is ignored.", address, subnetId);
                return false;
            }
            long offset = parsed.get().network() - subnet.effective.network();
            if (offset >= subnet.nextOffset) {
                subnet.nextOffset = offset + 1;
            }
            return true;
        }
    }

    public void releasePrivateIp(String region, String subnetId, String address) {
        SubnetBinding subnet = subnetBinding(region, subnetId);
        if (subnet != null && address != null) {
            subnet.leased.remove(address);
        }
    }

    // ─── Materialisation: attaching a container ──────────────────────────────

    /**
     * Creates the VPC's Docker network if it does not exist yet, and attaches the container to
     * it at {@code address}.
     *
     * <p>The container keeps its default bridge attachment as well. That is deliberate: EC2
     * containers publish SSH on a host port, and on macOS Docker Desktop setting a network mode
     * alongside port bindings suppresses publishing entirely (see ContainerLifecycleManager).
     * Attaching as a second network keeps published ports working while making the VPC address
     * the one Floci reports, and the one instances in the same VPC use to reach each other.
     *
     * @return the network name on success, empty when the instance stays on the bridge only
     */
    public Optional<String> attach(String region, String vpcId, String subnetId,
                                   String containerId, String address) {
        if (!enabled() || containerId == null || address == null) {
            return Optional.empty();
        }
        VpcBinding vpc = bindings.get(key(region, vpcId));
        if (vpc == null || vpc.effective == null) {
            return Optional.empty();
        }
        // Only attach an address this manager actually planned. A synthesised address from
        // Ec2Service's fallback would be outside the network's IPAM pool, and Docker would
        // reject it, noisily, and after the container is already up.
        SubnetBinding subnet = subnetBinding(region, subnetId);
        Optional<Cidr4> parsed = Cidr4.parse(address + "/32");
        if (subnet == null || subnet.effective == null || parsed.isEmpty()
                || !subnet.effective.containsAddress(parsed.get().network())) {
            return Optional.empty();
        }
        String networkName = materialise(vpc);
        if (networkName == null) {
            return Optional.empty();
        }
        try {
            dockerClient.connectToNetworkCmd()
                    .withContainerId(containerId)
                    .withNetworkId(networkName)
                    .withContainerNetwork(new ContainerNetwork()
                            .withIpamConfig(new ContainerNetwork.Ipam().withIpv4Address(address)))
                    .exec();
            LOG.infov("Attached container {0} to VPC network {1} at {2}", containerId, networkName, address);
            return Optional.of(networkName);
        } catch (Exception e) {
            LOG.warnv("Could not attach container {0} to VPC network {1} at {2}: {3}. The instance keeps "
                            + "its bridge address and its reported private IP will not be reachable.",
                    containerId, networkName, address, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Disconnects a container from its VPC network, for a launch that failed after {@link #attach}
     * succeeded. Docker holds the endpoint, and so the address, until the container is removed
     * or disconnected, so the lease cannot be returned to the pool before this runs: the next
     * launch would be handed the same address and Docker would refuse it.
     *
     * <p>A container that was never attached, or whose network is already gone, is not an error
     * here; both are the ordinary shape of a launch that failed early.
     */
    public void detach(String region, String vpcId, String containerId) {
        if (!enabled() || containerId == null) {
            return;
        }
        VpcBinding vpc = bindings.get(key(region, vpcId));
        if (vpc == null || !vpc.created) {
            return;
        }
        try {
            dockerClient.disconnectFromNetworkCmd()
                    .withNetworkId(vpc.networkName)
                    .withContainerId(containerId)
                    .withForce(true)
                    .exec();
        } catch (Exception e) {
            LOG.debugv("Could not disconnect {0} from VPC network {1}: {2}",
                    containerId, vpc.networkName, e.getMessage());
        }
    }

    /** @return the network name, or null when creation failed */
    private String materialise(VpcBinding vpc) {
        synchronized (vpc) {
            if (vpc.created) {
                return vpc.networkName;
            }
            Network existing = null;
            try {
                existing = dockerClient.inspectNetworkCmd().withNetworkId(vpc.networkName).exec();
            } catch (NotFoundException notThereYet) {
                // expected: first instance in this VPC
            } catch (Exception e) {
                LOG.debugv("Could not inspect VPC network {0}: {1}", vpc.networkName, e.getMessage());
            }
            if (existing != null) {
                List<Cidr4> actual = ipamSubnets(existing);
                if (actual.contains(vpc.effective)) {
                    vpc.created = true;
                    return vpc.networkName;
                }
                // A leftover under this VPC's own name, routing a range this VPC does not plan
                // from. Adopting it would hand out addresses from vpc.effective on a bridge that
                // routes something else: the exact quiet lie this class exists to remove.
                //
                // It is removed and recreated rather than re-planned onto the leftover's own
                // subnet, for two reasons. Addresses have already been allocated from
                // vpc.effective by the time materialise runs (attach validates the address
                // against the subnet plan before calling it), and after a restart some of them
                // are leases held by live containers, so the plan cannot be moved out from under
                // them. And the name carries this emulator's port and region, so the network is
                // by construction Floci's own: recreating it cannot destroy a user's network.
                LOG.warnv("Docker network {0} for VPC {1} already exists with subnet {2}, but this VPC "
                                + "allocates addresses from {3}. Removing the stale network and "
                                + "recreating it on the planned range; anything still attached to it is "
                                + "disconnected first.",
                        vpc.networkName, vpc.vpcId,
                        actual.isEmpty() ? "no readable IPAM configuration" : actual.toString(),
                        vpc.effective);
                if (!removeStaleNetwork(existing, vpc)) {
                    return null;
                }
            }
            try {
                dockerClient.createNetworkCmd()
                        .withName(vpc.networkName)
                        .withDriver(config.services().ec2().vpcNetworks().driver())
                        .withIpam(new Network.Ipam().withConfig(
                                new Network.Ipam.Config().withSubnet(vpc.effective.toString())))
                        .withLabels(networkLabels(vpc))
                        .exec();
                vpc.created = true;
                LOG.infov("Created Docker network {0} for VPC {1} ({2}){3}",
                        vpc.networkName, vpc.vpcId, vpc.effective,
                        vpc.substituted ? " [substituted; declared " + vpc.declared + "]" : "");
                return vpc.networkName;
            } catch (Exception e) {
                LOG.warnv("Could not create Docker network {0} for VPC {1} ({2}): {3}. Instances in this VPC "
                                + "stay on the shared bridge and are neither isolated nor addressed from the "
                                + "declared CIDR.",
                        vpc.networkName, vpc.vpcId, vpc.effective, e.getMessage());
                return null;
            }
        }
    }

    /**
     * @return false when the stale network is still there afterwards, in which case the caller
     *         must not adopt it: an instance on the shared bridge with an unreachable private IP
     *         is a documented degradation, whereas an instance holding an address the network
     *         does not route is a wrong answer.
     */
    private boolean removeStaleNetwork(Network existing, VpcBinding vpc) {
        String id = existing.getId() == null ? vpc.networkName : existing.getId();
        try {
            disconnectAll(existing);
            dockerClient.removeNetworkCmd(id).exec();
            return true;
        } catch (NotFoundException alreadyGone) {
            return true;
        } catch (Exception e) {
            LOG.warnv("Could not remove the stale Docker network {0} for VPC {1}: {2}. Instances in this "
                            + "VPC stay on the shared bridge rather than being given addresses the "
                            + "network does not route.",
                    vpc.networkName, vpc.vpcId, e.getMessage());
            return false;
        }
    }

    /** Every IPv4 range the network's IPAM configuration declares, in declaration order. */
    private static List<Cidr4> ipamSubnets(Network network) {
        if (network.getIpam() == null || network.getIpam().getConfig() == null) {
            return List.of();
        }
        List<Cidr4> subnets = new ArrayList<>();
        for (Network.Ipam.Config ipam : network.getIpam().getConfig()) {
            Cidr4.parse(ipam.getSubnet()).ifPresent(subnets::add);
        }
        return subnets;
    }

    private Map<String, String> networkLabels(VpcBinding vpc) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.defaultLabels(config));
        labels.put(LABEL_COMPONENT, COMPONENT_VALUE);
        labels.put(LABEL_VPC_ID, vpc.vpcId);
        labels.put(LABEL_VPC_REGION, vpc.region);
        labels.put(LABEL_OWNER_PORT, String.valueOf(config.port()));
        return labels;
    }

    // ─── Teardown ────────────────────────────────────────────────────────────

    public void forgetSubnet(String region, String subnetId) {
        String vpcKey = subnetOwner.remove(key(region, subnetId));
        if (vpcKey == null) {
            return;
        }
        VpcBinding vpc = bindings.get(vpcKey);
        if (vpc != null) {
            vpc.subnets.remove(subnetId);
        }
    }

    /** Drops the VPC's plan and removes its Docker network, retrying while endpoints drain. */
    public void deleteVpcNetwork(String region, String vpcId) {
        VpcBinding vpc = bindings.remove(key(region, vpcId));
        if (vpc == null) {
            return;
        }
        vpc.subnets.keySet().forEach(subnetId -> subnetOwner.remove(key(region, subnetId)));
        if (!vpc.created) {
            return;
        }
        removeNetworkWithRetry(vpc.networkName, 1);
    }

    private void removeNetworkWithRetry(String networkName, int attempt) {
        try {
            dockerClient.removeNetworkCmd(networkName).exec();
            LOG.infov("Removed Docker network {0}", networkName);
        } catch (NotFoundException alreadyGone) {
            // nothing to do
        } catch (Exception e) {
            // Containers terminated moments earlier still hold endpoints for a beat.
            if (attempt < 5 && !retries.isShutdown()) {
                retries.schedule(() -> removeNetworkWithRetry(networkName, attempt + 1), 2, TimeUnit.SECONDS);
                return;
            }
            LOG.warnv("Could not remove Docker network {0} after {1} attempts: {2}. It will be reconciled "
                    + "at the next startup.", networkName, String.valueOf(attempt), e.getMessage());
        }
    }

    // ─── Startup reconciliation ──────────────────────────────────────────────

    /**
     * Removes VPC networks this Floci left behind on a previous run.
     *
     * <p>A crashed or SIGKILLed run leaves its bridges and its IPAM reservations on the daemon,
     * and the next run's identical CIDRs then collide with its own corpses: a repeat failure
     * mode in this project, and one that looks like a genuine collision, so it would silently
     * push every VPC into the fallback pool.
     *
     * <p>Only networks labelled with this process's own API port are considered. Several Floci
     * instances share a daemon here; scoping by owner is what makes the deletion safe.
     *
     * @param stillDeclared answers whether (region, vpcId) exists in the restored state
     */
    public void reconcileOrphans(BiPredicate<String, String> stillDeclared) {
        if (!enabled() || !config.services().ec2().vpcNetworks().reconcileOnStartup()) {
            return;
        }
        String owner = String.valueOf(config.port());
        int removed = 0;
        try {
            for (Network network : dockerClient.listNetworksCmd()
                    .withFilter("label", List.of(LABEL_COMPONENT + "=" + COMPONENT_VALUE))
                    .exec()) {
                Map<String, String> labels = network.getLabels() == null ? Map.of() : network.getLabels();
                if (!owner.equals(labels.get(LABEL_OWNER_PORT))) {
                    continue;
                }
                String vpcId = labels.get(LABEL_VPC_ID);
                String region = labels.get(LABEL_VPC_REGION);
                if (vpcId != null && region != null && stillDeclared.test(region, vpcId)) {
                    continue;
                }
                disconnectAll(network);
                try {
                    dockerClient.removeNetworkCmd(network.getId()).exec();
                    removed++;
                    LOG.infov("Reconciled orphaned VPC network {0} (vpc {1}) left by a previous run",
                            network.getName(), String.valueOf(vpcId));
                } catch (Exception e) {
                    LOG.warnv("Could not remove orphaned VPC network {0}: {1}", network.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not reconcile orphaned VPC networks: {0}", e.getMessage());
        }
        if (removed > 0) {
            LOG.infov("Removed {0} orphaned VPC network(s)", String.valueOf(removed));
        }
    }

    /**
     * Detaches whatever is still on the network so it can be removed.
     *
     * <p>The container map has to be re-read with an inspect: Docker's network <em>list</em>
     * endpoint leaves it empty, so a crashed run's containers, the exact case this exists for,
     * are invisible in the listing and the removal then fails with "has active endpoints".
     */
    private void disconnectAll(Network listed) {
        Network network;
        try {
            network = dockerClient.inspectNetworkCmd().withNetworkId(listed.getId()).exec();
        } catch (Exception e) {
            LOG.debugv("Could not inspect {0} before removal: {1}", listed.getName(), e.getMessage());
            return;
        }
        Map<String, Network.ContainerNetworkConfig> containers = network.getContainers();
        if (containers == null) {
            return;
        }
        for (String containerId : containers.keySet()) {
            try {
                dockerClient.disconnectFromNetworkCmd()
                        .withNetworkId(listed.getId())
                        .withContainerId(containerId)
                        .withForce(true)
                        .exec();
            } catch (Exception e) {
                LOG.debugv("Could not disconnect {0} from {1}: {2}", containerId, listed.getName(), e.getMessage());
            }
        }
    }

    // ─── Introspection, for tests and reporting ──────────────────────────────

    public Optional<String> effectiveVpcCidr(String region, String vpcId) {
        VpcBinding vpc = bindings.get(key(region, vpcId));
        return vpc == null || vpc.effective == null ? Optional.empty() : Optional.of(vpc.effective.toString());
    }

    public Optional<String> effectiveSubnetCidr(String region, String subnetId) {
        SubnetBinding subnet = subnetBinding(region, subnetId);
        return subnet == null || subnet.effective == null
                ? Optional.empty() : Optional.of(subnet.effective.toString());
    }

    public Optional<String> networkNameFor(String region, String vpcId) {
        VpcBinding vpc = bindings.get(key(region, vpcId));
        return vpc == null ? Optional.empty() : Optional.of(vpc.networkName);
    }

    public boolean isSubstituted(String region, String vpcId) {
        VpcBinding vpc = bindings.get(key(region, vpcId));
        return vpc != null && vpc.substituted;
    }

    String networkName(String region, String vpcId) {
        return ContainerStorageHelper.dockerName(config,
                "floci-vpc-" + config.port() + "-" + region + "-" + vpcId);
    }

    private SubnetBinding subnetBinding(String region, String subnetId) {
        String vpcKey = subnetOwner.get(key(region, subnetId));
        if (vpcKey == null) {
            return null;
        }
        VpcBinding vpc = bindings.get(vpcKey);
        return vpc == null ? null : vpc.subnets.get(subnetId);
    }

    /**
     * Every IPv4 range the daemon has already reserved, so a declared CIDR can be checked before use.
     *
     * @param ownVpcId the VPC being planned; this instance's surviving network for that same VPC is
     *                 excluded, so a Floci restart re-planning a VPC whose bridge is still up does
     *                 not read its own network as a collision and substitute a CIDR it already has
     */
    private Set<Cidr4> dockerNetworkSubnets(String ownVpcId) {
        Set<Cidr4> taken = new java.util.LinkedHashSet<>();
        String owner = String.valueOf(config.port());
        try {
            for (Network network : dockerClient.listNetworksCmd().exec()) {
                Map<String, String> labels = network.getLabels() == null ? Map.of() : network.getLabels();
                if (ownVpcId != null && ownVpcId.equals(labels.get(LABEL_VPC_ID))
                        && owner.equals(labels.get(LABEL_OWNER_PORT))) {
                    continue;
                }
                if (network.getIpam() == null || network.getIpam().getConfig() == null) {
                    continue;
                }
                for (Network.Ipam.Config ipam : network.getIpam().getConfig()) {
                    Cidr4.parse(ipam.getSubnet()).ifPresent(taken::add);
                }
            }
        } catch (Exception e) {
            // A daemon that cannot be listed cannot be collided with either; proceeding with the
            // declared CIDR is the honest choice, and createNetwork still refuses a real overlap.
            LOG.debugv("Could not list Docker networks for CIDR collision check: {0}", e.getMessage());
        }
        return taken;
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    private static final class VpcBinding {
        final String region;
        final String vpcId;
        final Cidr4 declared;
        final Cidr4 effective;
        final boolean substituted;
        final String networkName;
        final Map<String, SubnetBinding> subnets = new ConcurrentHashMap<>();
        volatile boolean created;

        VpcBinding(String region, String vpcId, Cidr4 declared, Cidr4 effective,
                   boolean substituted, String networkName) {
            this.region = region;
            this.vpcId = vpcId;
            this.declared = declared;
            this.effective = effective;
            this.substituted = substituted;
            this.networkName = networkName;
        }
    }

    private static final class SubnetBinding {
        final String subnetId;
        final Cidr4 declared;
        final Cidr4 effective;
        final boolean substituted;
        final Set<String> leased = ConcurrentHashMap.newKeySet();
        /** Scaled to the block: a /29 cannot spare the ten addresses a /24 hands over freely. */
        final long firstOffset;
        long nextOffset;

        SubnetBinding(String subnetId, Cidr4 declared, Cidr4 effective, boolean substituted) {
            this.subnetId = subnetId;
            this.declared = declared;
            this.effective = effective;
            this.substituted = substituted;
            this.firstOffset = effective == null ? FIRST_HOST_OFFSET : firstHostOffset(effective);
            this.nextOffset = this.firstOffset;
        }
    }
}
