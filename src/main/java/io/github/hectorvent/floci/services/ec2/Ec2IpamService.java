package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Ipam;
import io.github.hectorvent.floci.services.ec2.model.IpamPool;
import io.github.hectorvent.floci.services.ec2.model.IpamPoolAllocation;
import io.github.hectorvent.floci.services.ec2.model.IpamPoolCidr;
import io.github.hectorvent.floci.services.ec2.model.IpamScope;
import io.github.hectorvent.floci.services.ec2.model.AsnAssociation;
import io.github.hectorvent.floci.services.ec2.model.Tag;

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
    private final StorageBackend<String, AsnAssociation> asnAssociations;


    @Inject
    public Ec2IpamService(EmulatorConfig config, StorageFactory storageFactory) {
        this(config,
                storageFactory.create("ec2", "ec2-ipams.json", new TypeReference<Map<String, Ipam>>() {}),
                storageFactory.create("ec2", "ec2-ipam-pools.json", new TypeReference<Map<String, IpamPool>>() {}),
                storageFactory.create("ec2", "ec2-ipam-settings.json", new TypeReference<Map<String, String>>() {}),
                storageFactory.create("ec2", "ec2-ipam-asn-associations.json", new TypeReference<Map<String, AsnAssociation>>() {}));
    }

    // Package-private for hermetic tests (pass in-memory StorageBackends directly).
    Ec2IpamService(EmulatorConfig config,
                   StorageBackend<String, Ipam> ipams,
                   StorageBackend<String, IpamPool> pools,
                   StorageBackend<String, String> settings,
                   StorageBackend<String, AsnAssociation> asnAssociations) {
        this.config = config;
        this.ipams = ipams;
        this.pools = pools;
        this.settings = settings;
        this.asnAssociations = asnAssociations;
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
        return createIpam(region, description, operatingRegions, config.defaultAccountId());
    }

    /** Overload for callers (e.g. CloudFormation provisioning) that already have the real
     *  owning account resolved, instead of falling back to this service's ambient default. */
    public Ipam createIpam(String region, String description, List<String> operatingRegions,
                           String ownerId) {
        return createIpam(region, description, operatingRegions, ownerId,
                false, "ipam-owner", "free", null, List.of());
    }

    public Ipam createIpam(String region, String description, List<String> operatingRegions,
                           String ownerId, Boolean enablePrivateGua, String meteredAccount,
                           String tier, String clientToken, List<Tag> tags) {
        if (clientToken != null && !clientToken.isBlank()) {
            for (Ipam existing : ipams.scan(k -> true)) {
                if (region.equals(existing.getRegion())
                        && clientToken.equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }
        Ipam ipam = new Ipam();
        ipam.setIpamId("ipam-" + randomHex(17));
        ipam.setIpamArn("arn:aws:ec2::" + ownerId + ":ipam/" + ipam.getIpamId());
        ipam.setOwnerId(ownerId);
        ipam.setRegion(region);
        ipam.setDescription(description);
        ipam.setState("create-complete");
        ipam.setEnablePrivateGua(enablePrivateGua != null ? enablePrivateGua : false);
        ipam.setMeteredAccount(meteredAccount != null ? meteredAccount : "ipam-owner");
        ipam.setTier(tier != null ? tier : "free");
        ipam.setClientToken(clientToken != null && !clientToken.isBlank() ? clientToken : null);
        ipam.setTags(tags != null ? new ArrayList<>(tags) : new ArrayList<>());
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

    public AsnAssociation associateIpamByoasn(String region, String asn, String cidr) {
        if (asn == null || asn.isBlank()) {
            throw new AwsException("MissingParameter", "Asn is required.", 400);
        }
        if (cidr == null || cidr.isBlank()) {
            throw new AwsException("MissingParameter", "Cidr is required.", 400);
        }
        String key = key(region, asn + "|" + cidr);
        String ipamId = null;
        for (Ipam ipam : ipams.scan(k -> true)) {
            if (region != null && ipam.getRegion() != null && region.equals(ipam.getRegion())) {
                ipamId = ipam.getIpamId();
                break;
            }
        }
        AsnAssociation association = new AsnAssociation();
        association.setAsn(asn);
        association.setCidr(cidr);
        association.setIpamId(ipamId);
        association.setState("associated");
        association.setStatusMessage("");
        asnAssociations.put(key, association);
        return association;
    }

    public boolean hasAsnAssociation(String region, String asn, String cidr) {
        String key = key(region, asn + "|" + cidr);
        return asnAssociations.get(key).isPresent();
    }

    public List<AsnAssociation> describeIpamByoasn(String region) {
        List<AsnAssociation> result = new ArrayList<>();
        String prefix = region + "|";
        result.addAll(asnAssociations.scan(k -> k.startsWith(prefix)));
        return result;
    }

    public AsnAssociation disassociateIpamByoasn(String region, String asn, String cidr) {
        if (asn == null || asn.isBlank()) {
            throw new AwsException("MissingParameter", "Asn is required.", 400);
        }
        if (cidr == null || cidr.isBlank()) {
            throw new AwsException("MissingParameter", "Cidr is required.", 400);
        }
        String storageKey = key(region, asn + "|" + cidr);
        AsnAssociation association = asnAssociations.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidParameterValue", "BYOASN association was not found.", 400));
        asnAssociations.delete(storageKey);
        association.setState("disassociated");
        return association;
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

    public Ipam modifyIpam(String region, String ipamId, String description,
                           List<String> addOperatingRegions, List<String> removeOperatingRegions) {
        return modifyIpam(region, ipamId, description, addOperatingRegions, removeOperatingRegions,
                null, null, null);
    }

    public Ipam modifyIpam(String region, String ipamId, String description,
                           List<String> addOperatingRegions, List<String> removeOperatingRegions,
                           Boolean enablePrivateGua, String meteredAccount, String tier) {
        Ipam ipam = requireIpam(region, ipamId);
        if (description != null) {
            ipam.setDescription(description);
        }
        if (enablePrivateGua != null) {
            ipam.setEnablePrivateGua(enablePrivateGua);
        }
        if (meteredAccount != null) {
            ipam.setMeteredAccount(meteredAccount);
        }
        if (tier != null) {
            ipam.setTier(tier);
        }
        if (addOperatingRegions != null) {
            for (String operatingRegion : addOperatingRegions) {
                if (operatingRegion != null
                        && !operatingRegion.isBlank()
                        && !ipam.getOperatingRegions().contains(operatingRegion)) {
                    ipam.getOperatingRegions().add(operatingRegion);
                }
            }
        }
        if (removeOperatingRegions != null) {
            ipam.getOperatingRegions().removeIf(removeOperatingRegions::contains);
        }
        ipams.put(key(ipam.getRegion(), ipam.getIpamId()), ipam);
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

    public IpamPool modifyIpamPool(String region, String ipamPoolId, String description,
                                   Boolean autoImport, Integer allocationMinNetmaskLength,
                                   Integer allocationMaxNetmaskLength, Integer allocationDefaultNetmaskLength,
                                   boolean clearAllocationDefaultNetmaskLength) {
        OwnedPool ownedPool = requireOwnedPool(region, ipamPoolId);
        IpamPool pool = ownedPool.pool();
        if (description != null) {
            pool.setDescription(description);
        }
        if (autoImport != null) {
            pool.setAutoImport(autoImport);
        }
        if (allocationMinNetmaskLength != null) {
            pool.setAllocationMinNetmaskLength(allocationMinNetmaskLength);
        }
        if (allocationMaxNetmaskLength != null) {
            pool.setAllocationMaxNetmaskLength(allocationMaxNetmaskLength);
        }
        if (clearAllocationDefaultNetmaskLength) {
            pool.setAllocationDefaultNetmaskLength(null);
        } else if (allocationDefaultNetmaskLength != null) {
            pool.setAllocationDefaultNetmaskLength(allocationDefaultNetmaskLength);
        }
        savePool(ownedPool);
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
        OwnedPool ownedPool = requireOwnedPool(region, ipamPoolId);
        IpamPool pool = ownedPool.pool();
        deletePool(ownedPool);
        pool.setState("delete-complete");
        return pool;
    }

    public IpamPoolCidr provisionIpamPoolCidr(String region, String ipamPoolId, String cidr) {
        OwnedPool ownedPool = requireOwnedPool(region, ipamPoolId);
        IpamPool pool = ownedPool.pool();
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
        savePool(ownedPool);
        return poolCidr;
    }

    public List<IpamPoolCidr> getIpamPoolCidrs(String region, String ipamPoolId) {
        return requirePool(region, ipamPoolId).getProvisionedCidrs();
    }

    // ─── Allocations ────────────────────────────────────────────────────────

    public IpamPoolAllocation allocateIpamPoolCidr(String region, String ipamPoolId,
                                                   Integer netmaskLength, String cidr,
                                                   String description) {
        OwnedPool ownedPool = requireOwnedPool(region, ipamPoolId);
        IpamPool pool = ownedPool.pool();
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
        savePool(ownedPool);
        LOG.infov("Allocated {0} from IPAM pool {1}", chosen, ipamPoolId);
        return allocation;
    }

    public boolean releaseIpamPoolAllocation(String region, String ipamPoolId,
                                             String ipamPoolAllocationId, String cidr) {
        OwnedPool ownedPool = requireOwnedPool(region, ipamPoolId);
        IpamPool pool = ownedPool.pool();
        boolean removed = pool.getAllocations().removeIf(a ->
                (ipamPoolAllocationId != null && ipamPoolAllocationId.equals(a.getIpamPoolAllocationId()))
                        || (ipamPoolAllocationId == null && cidr != null && cidr.equals(a.getCidr())));
        if (!removed) {
            throw new AwsException("InvalidIpamPoolAllocationId.NotFound",
                    "Allocation " + ipamPoolAllocationId + " not found in pool " + ipamPoolId + ".", 400);
        }
        savePool(ownedPool);
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
        for (IpamPool other : allPools()) {
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
        return requireOwnedPool(region, ipamPoolId).pool();
    }

    private OwnedPool requireOwnedPool(String region, String ipamPoolId) {
        if (pools instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<IpamPool> accountAware =
                    (AccountAwareStorageBackend<IpamPool>) rawAccountAware;
            Optional<AccountAwareStorageBackend.OwnedEntry<IpamPool>> exact =
                    accountAware.findAnyAccountEntry(key(region, ipamPoolId));
            if (exact.isPresent()) {
                var entry = exact.get();
                return new OwnedPool(entry.account(), entry.value());
            }
            for (IpamPool pool : accountAware.scanAllAccounts()) {
                if (pool.getIpamPoolId().equals(ipamPoolId)) {
                    var entry = accountAware.findAnyAccountEntry(
                            key(pool.getRegion(), pool.getIpamPoolId())).orElseThrow();
                    return new OwnedPool(entry.account(), entry.value());
                }
            }
            throw poolNotFound(ipamPoolId);
        }

        Optional<IpamPool> direct = pools.get(key(region, ipamPoolId));
        if (direct.isPresent()) {
            return new OwnedPool(null, direct.get());
        }
        // RAM-shared pools are resolved from other accounts/regions by id.
        for (IpamPool pool : pools.scan(k -> true)) {
            if (pool.getIpamPoolId().equals(ipamPoolId)) {
                return new OwnedPool(null, pool);
            }
        }
        throw poolNotFound(ipamPoolId);
    }

    private List<IpamPool> allPools() {
        if (pools instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<IpamPool> accountAware =
                    (AccountAwareStorageBackend<IpamPool>) rawAccountAware;
            return accountAware.scanAllAccounts();
        }
        return pools.scan(k -> true);
    }

    private void savePool(OwnedPool ownedPool) {
        IpamPool pool = ownedPool.pool();
        String poolKey = key(pool.getRegion(), pool.getIpamPoolId());
        if (ownedPool.accountId() != null
                && pools instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<IpamPool> accountAware =
                    (AccountAwareStorageBackend<IpamPool>) rawAccountAware;
            accountAware.putForAccount(ownedPool.accountId(), poolKey, pool);
            return;
        }
        pools.put(poolKey, pool);
    }

    private void deletePool(OwnedPool ownedPool) {
        IpamPool pool = ownedPool.pool();
        String poolKey = key(pool.getRegion(), pool.getIpamPoolId());
        if (ownedPool.accountId() != null
                && pools instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<IpamPool> accountAware =
                    (AccountAwareStorageBackend<IpamPool>) rawAccountAware;
            accountAware.deleteForAccount(ownedPool.accountId(), poolKey);
            return;
        }
        pools.delete(poolKey);
    }

    private static AwsException poolNotFound(String ipamPoolId) {
        return new AwsException("InvalidIpamPoolId.NotFound",
                "IPAM pool " + ipamPoolId + " does not exist.", 400);
    }

    private record OwnedPool(String accountId, IpamPool pool) {}

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
