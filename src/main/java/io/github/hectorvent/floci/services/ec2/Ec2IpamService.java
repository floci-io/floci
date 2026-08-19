package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Ipam;
import io.github.hectorvent.floci.services.ec2.model.IpamPool;
import io.github.hectorvent.floci.services.ec2.model.IpamPoolAllocation;
import io.github.hectorvent.floci.services.ec2.model.IpamPoolCidr;
import io.github.hectorvent.floci.services.ec2.model.IpamScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Amazon VPC IP Address Manager (IPAM) emulation: organization admin
 * delegation, IPAMs with default scopes, hierarchical pools with provisioned
 * CIDRs, and CIDR allocations.
 *
 * <p>This is what LZA needs end to end: the Organization stage delegates the
 * IPAM admin ({@code Custom::EnableIpamOrganizationAdminAccount}), the Network
 * stages create the IPAM + pool hierarchy through CloudFormation, and the
 * {@code get-ipam-subnet-cidr} custom-resource Lambda allocates subnet CIDRs
 * from pools at Deploy time. Pool lookups deliberately fall back to an
 * id-only scan so RAM-shared pools resolve from workload accounts/regions.</p>
 */
@ApplicationScoped
public class Ec2IpamService {

    private static final Logger LOG = Logger.getLogger(Ec2IpamService.class);

    private static final String ADMIN_ACCOUNT_KEY = "ipam-organization-admin-account";

    private final EmulatorConfig config;
    // key(region, ipamId) -> Ipam
    private final StorageBackend<String, Ipam> ipams;
    // key(region, ipamPoolId) -> IpamPool
    private final StorageBackend<String, IpamPool> pools;
    // org-wide settings, e.g. the delegated admin account
    private final StorageBackend<String, String> settings;

    @Inject
    public Ec2IpamService(EmulatorConfig config, StorageFactory storageFactory) {
        this(config,
                storageFactory.create("ec2", "ec2-ipams.json", new TypeReference<Map<String, Ipam>>() {}),
                storageFactory.create("ec2", "ec2-ipam-pools.json", new TypeReference<Map<String, IpamPool>>() {}),
                storageFactory.create("ec2", "ec2-ipam-settings.json", new TypeReference<Map<String, String>>() {}));
    }

    // Package-private for hermetic tests (pass in-memory StorageBackends directly).
    Ec2IpamService(EmulatorConfig config,
                   StorageBackend<String, Ipam> ipams,
                   StorageBackend<String, IpamPool> pools,
                   StorageBackend<String, String> settings) {
        this.config = config;
        this.ipams = ipams;
        this.pools = pools;
        this.settings = settings;
    }

    // ─── Organization admin delegation ──────────────────────────────────────

    public boolean enableIpamOrganizationAdminAccount(String delegatedAdminAccountId) {
        if (delegatedAdminAccountId == null || delegatedAdminAccountId.isBlank()) {
            throw new AwsException("MissingParameter", "DelegatedAdminAccountId is required.", 400);
        }
        Optional<String> existing = settings.get(ADMIN_ACCOUNT_KEY);
        if (existing.isPresent() && !existing.get().equals(delegatedAdminAccountId)) {
            throw new AwsException("InvalidParameterValue",
                    "Account " + existing.get() + " is already the IPAM delegated administrator.", 400);
        }
        settings.put(ADMIN_ACCOUNT_KEY, delegatedAdminAccountId);
        LOG.infov("IPAM organization admin delegated to {0}", delegatedAdminAccountId);
        return true;
    }

    public Optional<String> getIpamOrganizationAdminAccount() {
        return settings.get(ADMIN_ACCOUNT_KEY);
    }

    public boolean disableIpamOrganizationAdminAccount(String delegatedAdminAccountId) {
        Optional<String> existing = settings.get(ADMIN_ACCOUNT_KEY);
        if (existing.isEmpty() || !existing.get().equals(delegatedAdminAccountId)) {
            throw new AwsException("InvalidParameterValue",
                    "Account " + delegatedAdminAccountId + " is not the IPAM delegated administrator.", 400);
        }
        settings.delete(ADMIN_ACCOUNT_KEY);
        LOG.infov("IPAM organization admin delegation removed from {0}", delegatedAdminAccountId);
        return true;
    }

    // ─── IPAMs + default scopes ─────────────────────────────────────────────

    public Ipam createIpam(String region, String description, List<String> operatingRegions) {
        String ownerId = config.defaultAccountId();
        Ipam ipam = new Ipam();
        ipam.setIpamId("ipam-" + randomHex(17));
        ipam.setIpamArn("arn:aws:ec2::" + ownerId + ":ipam/" + ipam.getIpamId());
        ipam.setOwnerId(ownerId);
        ipam.setRegion(region);
        ipam.setDescription(description);
        ipam.setState("create-complete");
        ipam.setOperatingRegions(operatingRegions != null ? new ArrayList<>(operatingRegions) : new ArrayList<>());
        ipam.getScopes().add(defaultScope(ipam, ownerId, "private"));
        ipam.getScopes().add(defaultScope(ipam, ownerId, "public"));
        ipam.setPrivateDefaultScopeId(ipam.getScopes().get(0).getIpamScopeId());
        ipam.setPublicDefaultScopeId(ipam.getScopes().get(1).getIpamScopeId());
        ipams.put(key(region, ipam.getIpamId()), ipam);
        LOG.infov("Created IPAM {0} in {1}", ipam.getIpamId(), region);
        return ipam;
    }

    private static IpamScope defaultScope(Ipam ipam, String ownerId, String scopeType) {
        IpamScope scope = new IpamScope();
        scope.setIpamScopeId("ipam-scope-" + randomHex(17));
        scope.setIpamScopeArn("arn:aws:ec2::" + ownerId + ":ipam-scope/" + scope.getIpamScopeId());
        scope.setIpamId(ipam.getIpamId());
        scope.setScopeType(scopeType);
        scope.setDefault(true);
        scope.setState("create-complete");
        return scope;
    }

    public List<Ipam> describeIpams(String region, List<String> ipamIds) {
        List<Ipam> result = new ArrayList<>();
        for (Ipam ipam : ipams.scan(k -> true)) {
            if (region != null && ipam.getRegion() != null && !region.equals(ipam.getRegion())) {
                continue;
            }
            if (ipamIds != null && !ipamIds.isEmpty() && !ipamIds.contains(ipam.getIpamId())) {
                continue;
            }
            result.add(ipam);
        }
        return result;
    }

    public Ipam deleteIpam(String region, String ipamId) {
        Ipam ipam = requireIpam(region, ipamId);
        ipams.delete(key(ipam.getRegion(), ipam.getIpamId()));
        // Lenient cascade (AWS requires --cascade): drop the IPAM's pools too.
        for (IpamPool pool : pools.scan(k -> true)) {
            if (ipamId.equals(pool.getIpamId())) {
                pools.delete(key(pool.getRegion(), pool.getIpamPoolId()));
            }
        }
        ipam.setState("delete-complete");
        return ipam;
    }

    // ─── Pools + provisioned CIDRs ──────────────────────────────────────────

    public IpamPool createIpamPool(String region, String ipamScopeId, String locale,
                                   String sourceIpamPoolId, String addressFamily, String description) {
        return createIpamPool(region, ipamScopeId, locale, sourceIpamPoolId, addressFamily,
                description, config.defaultAccountId());
    }

    /** Overload for callers (e.g. CloudFormation provisioning) that already have the real
     *  owning account resolved, instead of falling back to this service's ambient default. */
    public IpamPool createIpamPool(String region, String ipamScopeId, String locale,
                                   String sourceIpamPoolId, String addressFamily, String description,
                                   String ownerId) {
        if (sourceIpamPoolId != null) {
            requirePool(region, sourceIpamPoolId);
        }
        IpamPool pool = new IpamPool();
        pool.setIpamPoolId("ipam-pool-" + randomHex(17));
        pool.setIpamPoolArn("arn:aws:ec2::" + ownerId + ":ipam-pool/" + pool.getIpamPoolId());
        pool.setIpamId(ipamIdOfScope(ipamScopeId));
        pool.setIpamScopeId(ipamScopeId);
        pool.setOwnerId(ownerId);
        pool.setRegion(region);
        pool.setLocale(locale);
        pool.setSourceIpamPoolId(sourceIpamPoolId);
        pool.setAddressFamily(addressFamily != null ? addressFamily : "ipv4");
        pool.setDescription(description);
        pool.setState("create-complete");
        pools.put(key(region, pool.getIpamPoolId()), pool);
        LOG.infov("Created IPAM pool {0} (scope {1}, source {2})",
                pool.getIpamPoolId(), ipamScopeId, sourceIpamPoolId);
        return pool;
    }

    private String ipamIdOfScope(String ipamScopeId) {
        if (ipamScopeId == null) {
            return null;
        }
        for (Ipam ipam : ipams.scan(k -> true)) {
            for (IpamScope scope : ipam.getScopes()) {
                if (ipamScopeId.equals(scope.getIpamScopeId())) {
                    return ipam.getIpamId();
                }
            }
        }
        return null;
    }

    public List<IpamPool> describeIpamPools(String region, List<String> ipamPoolIds) {
        if (ipamPoolIds != null && !ipamPoolIds.isEmpty()) {
            List<IpamPool> result = new ArrayList<>();
            for (String id : ipamPoolIds) {
                result.add(requirePool(region, id));
            }
            return result;
        }
        List<IpamPool> result = new ArrayList<>();
        for (IpamPool pool : pools.scan(k -> true)) {
            if (region != null && pool.getRegion() != null && !region.equals(pool.getRegion())) {
                continue;
            }
            result.add(pool);
        }
        return result;
    }

    public IpamPool deleteIpamPool(String region, String ipamPoolId) {
        IpamPool pool = requirePool(region, ipamPoolId);
        pools.delete(key(pool.getRegion(), pool.getIpamPoolId()));
        pool.setState("delete-complete");
        return pool;
    }

    public IpamPoolCidr provisionIpamPoolCidr(String region, String ipamPoolId, String cidr) {
        IpamPool pool = requirePool(region, ipamPoolId);
        if (cidr == null || cidr.isBlank()) {
            throw new AwsException("MissingParameter", "Cidr is required.", 400);
        }
        if (pool.getSourceIpamPoolId() != null) {
            IpamPool source = requirePool(region, pool.getSourceIpamPoolId());
            boolean inSource = source.getProvisionedCidrs().stream()
                    .anyMatch(provisioned -> Ipv4Cidrs.contains(provisioned.getCidr(), cidr));
            if (!inSource) {
                throw new AwsException("InvalidParameterValue",
                        "CIDR " + cidr + " is not within the provisioned CIDRs of source pool "
                                + pool.getSourceIpamPoolId() + ".", 400);
            }
        } else {
            Ipv4Cidrs.contains(cidr, cidr); // validate syntax for top-level pools
        }
        IpamPoolCidr poolCidr = new IpamPoolCidr(cidr, "provisioned");
        pool.getProvisionedCidrs().add(poolCidr);
        pools.put(key(pool.getRegion(), pool.getIpamPoolId()), pool);
        return poolCidr;
    }

    public List<IpamPoolCidr> getIpamPoolCidrs(String region, String ipamPoolId) {
        return requirePool(region, ipamPoolId).getProvisionedCidrs();
    }

    // ─── Allocations ────────────────────────────────────────────────────────

    public IpamPoolAllocation allocateIpamPoolCidr(String region, String ipamPoolId,
                                                   Integer netmaskLength, String cidr,
                                                   String description) {
        IpamPool pool = requirePool(region, ipamPoolId);
        List<String> provisioned = pool.getProvisionedCidrs().stream()
                .map(IpamPoolCidr::getCidr).toList();
        List<String> occupied = occupiedSpace(pool);

        String chosen;
        if (cidr != null && !cidr.isBlank()) {
            boolean inPool = provisioned.stream().anyMatch(p -> Ipv4Cidrs.contains(p, cidr));
            if (!inPool) {
                throw new AwsException("InvalidParameterValue",
                        "CIDR " + cidr + " is not within pool " + ipamPoolId + ".", 400);
            }
            boolean taken = occupied.stream().anyMatch(o -> Ipv4Cidrs.overlaps(o, cidr));
            if (taken) {
                throw new AwsException("InvalidParameterValue",
                        "CIDR " + cidr + " overlaps an existing allocation in pool " + ipamPoolId + ".", 400);
            }
            chosen = cidr;
        } else if (netmaskLength != null) {
            chosen = Ipv4Cidrs.firstFreeBlock(provisioned, occupied, netmaskLength);
            if (chosen == null) {
                throw new AwsException("InsufficientCidrBlocks",
                        "Pool " + ipamPoolId + " has no free /" + netmaskLength + " block.", 400);
            }
        } else {
            throw new AwsException("MissingParameter",
                    "Either NetmaskLength or Cidr is required.", 400);
        }

        IpamPoolAllocation allocation = new IpamPoolAllocation();
        allocation.setIpamPoolAllocationId("ipam-pool-alloc-" + randomHex(17));
        allocation.setCidr(chosen);
        allocation.setDescription(description);
        allocation.setResourceType("custom");
        allocation.setResourceOwner(config.defaultAccountId());
        pool.getAllocations().add(allocation);
        pools.put(key(pool.getRegion(), pool.getIpamPoolId()), pool);
        LOG.infov("Allocated {0} from IPAM pool {1}", chosen, ipamPoolId);
        return allocation;
    }

    public boolean releaseIpamPoolAllocation(String region, String ipamPoolId,
                                             String ipamPoolAllocationId, String cidr) {
        IpamPool pool = requirePool(region, ipamPoolId);
        boolean removed = pool.getAllocations().removeIf(a ->
                (ipamPoolAllocationId != null && ipamPoolAllocationId.equals(a.getIpamPoolAllocationId()))
                        || (ipamPoolAllocationId == null && cidr != null && cidr.equals(a.getCidr())));
        if (!removed) {
            throw new AwsException("InvalidIpamPoolAllocationId.NotFound",
                    "Allocation " + ipamPoolAllocationId + " not found in pool " + ipamPoolId + ".", 400);
        }
        pools.put(key(pool.getRegion(), pool.getIpamPoolId()), pool);
        return true;
    }

    public List<IpamPoolAllocation> getIpamPoolAllocations(String region, String ipamPoolId) {
        return requirePool(region, ipamPoolId).getAllocations();
    }

    /**
     * Space already unavailable in a pool: live allocations plus every CIDR
     * provisioned onward to child pools.
     */
    private List<String> occupiedSpace(IpamPool pool) {
        List<String> occupied = new ArrayList<>();
        for (IpamPoolAllocation allocation : pool.getAllocations()) {
            occupied.add(allocation.getCidr());
        }
        for (IpamPool other : pools.scan(k -> true)) {
            if (pool.getIpamPoolId().equals(other.getSourceIpamPoolId())) {
                for (IpamPoolCidr provisioned : other.getProvisionedCidrs()) {
                    occupied.add(provisioned.getCidr());
                }
            }
        }
        return occupied;
    }

    // ─── Lookup helpers ─────────────────────────────────────────────────────

    private Ipam requireIpam(String region, String ipamId) {
        Optional<Ipam> direct = ipams.get(key(region, ipamId));
        if (direct.isPresent()) {
            return direct.get();
        }
        for (Ipam ipam : ipams.scan(k -> true)) {
            if (ipam.getIpamId().equals(ipamId)) {
                return ipam;
            }
        }
        throw new AwsException("InvalidIpamId.NotFound", "IPAM " + ipamId + " does not exist.", 400);
    }

    private IpamPool requirePool(String region, String ipamPoolId) {
        Optional<IpamPool> direct = pools.get(key(region, ipamPoolId));
        if (direct.isPresent()) {
            return direct.get();
        }
        // RAM-shared pools are resolved from other accounts/regions by id.
        for (IpamPool pool : pools.scan(k -> true)) {
            if (pool.getIpamPoolId().equals(ipamPoolId)) {
                return pool;
            }
        }
        throw new AwsException("InvalidIpamPoolId.NotFound",
                "IPAM pool " + ipamPoolId + " does not exist.", 400);
    }

    private static String key(String region, String id) {
        return region + "|" + id;
    }

    private static String randomHex(int len) {
        String chars = "0123456789abcdef";
        StringBuilder sb = new StringBuilder(len);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
