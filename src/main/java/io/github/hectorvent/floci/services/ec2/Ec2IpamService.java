package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
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
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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

    /**
     * Partition the delegated-admin setting is stored under. The management account delegates it
     * once and every member account must read the same value, so it cannot live in the ordinary
     * account-prefixed namespace. The literal is not a valid 12-digit account id precisely so it
     * can never collide with a real account's partition.
     */
    private static final String ORGANIZATION_PARTITION = "organization";

    private final EmulatorConfig config;
    // key(region, ipamId) -> Ipam
    private final StorageBackend<String, Ipam> ipams;
    // key(region, ipamPoolId) -> IpamPool
    private final StorageBackend<String, IpamPool> pools;
    // org-wide settings, e.g. the delegated admin account
    private final StorageBackend<String, String> settings;
    private final StorageBackend<String, AsnAssociation> asnAssociations;
    private final Instance<RequestContext> requestContextInstance;

    @Inject
    public Ec2IpamService(EmulatorConfig config, StorageFactory storageFactory,
                          Instance<RequestContext> requestContextInstance) {
        this(config,
                storageFactory.create("ec2", "ec2-ipams.json", new TypeReference<Map<String, Ipam>>() {}),
                storageFactory.create("ec2", "ec2-ipam-pools.json", new TypeReference<Map<String, IpamPool>>() {}),
                storageFactory.create("ec2", "ec2-ipam-settings.json", new TypeReference<Map<String, String>>() {}),
                storageFactory.create("ec2", "ec2-ipam-asn-associations.json", new TypeReference<Map<String, AsnAssociation>>() {}),
                requestContextInstance);
    }

    // Package-private for hermetic tests (pass in-memory StorageBackends directly).
    Ec2IpamService(EmulatorConfig config,
                   StorageBackend<String, Ipam> ipams,
                   StorageBackend<String, IpamPool> pools,
                   StorageBackend<String, String> settings,
                   StorageBackend<String, AsnAssociation> asnAssociations) {
        this(config, ipams, pools, settings, asnAssociations, null);
    }

    Ec2IpamService(EmulatorConfig config,
                   StorageBackend<String, Ipam> ipams,
                   StorageBackend<String, IpamPool> pools,
                   StorageBackend<String, String> settings,
                   StorageBackend<String, AsnAssociation> asnAssociations,
                   Instance<RequestContext> requestContextInstance) {
        this.config = config;
        this.ipams = ipams;
        this.pools = pools;
        this.settings = settings;
        this.asnAssociations = asnAssociations;
        this.requestContextInstance = requestContextInstance;
    }

    /**
     * The account making the current request, resolved the same way
     * {@code AccountAwareStorageBackend} derives its key prefix, so an ownership comparison
     * against a resolved entry's partition is meaningful both in and out of a request scope.
     */
    private String callerAccountId() {
        if (requestContextInstance != null) {
            try {
                String accountId = requestContextInstance.get().getAccountId();
                if (accountId != null) {
                    return accountId;
                }
            } catch (ContextNotActiveException e) {
                // Tolerated: callers outside a request scope (startup, internal provisioning)
                // legitimately fall through to the default account. Logged so an unexpected
                // context loss inside a request doesn't produce silent wrong-owner decisions.
                LOG.debugv("No active request context — resolving caller as default account {0}",
                        config.defaultAccountId());
            }
        }
        return config.defaultAccountId();
    }

    /**
     * Only the organization's management account may enable/disable the IPAM delegated
     * administrator (real AWS: a non-management member calling either op is denied). This
     * emulator has no cross-service organization membership to resolve the true management
     * account from, so the configured default account stands in for it — the same account
     * launched Lambdas resolve to when they call without an assumed role, which is how LZA's
     * Organization-stage custom resource invokes these operations.
     *
     * <p>{@code UnauthorizedOperation} is EC2 Query's permission-denied code: both operations
     * model a {@code DryRun} member documented as returning {@code DryRunOperation} when the
     * caller has permission and {@code UnauthorizedOperation} when it does not.</p>
     */
    private void requireManagementAccount() {
        String caller = callerAccountId();
        if (!caller.equals(config.defaultAccountId())) {
            throw new AwsException("UnauthorizedOperation",
                    "Account " + caller + " is not authorized to modify the IPAM organization "
                            + "delegated administrator; only the organization's management account may.", 403);
        }
    }

    // ─── Organization admin delegation ──────────────────────────────────────

    public boolean enableIpamOrganizationAdminAccount(String delegatedAdminAccountId) {
        requireManagementAccount();
        if (delegatedAdminAccountId == null || delegatedAdminAccountId.isBlank()) {
            throw new AwsException("MissingParameter", "DelegatedAdminAccountId is required.", 400);
        }
        Optional<String> existing = organizationAdmin();
        if (existing.isPresent() && !existing.get().equals(delegatedAdminAccountId)) {
            throw new AwsException("InvalidParameterValue",
                    "Account " + existing.get() + " is already the IPAM delegated administrator.", 400);
        }
        putOrganizationAdmin(delegatedAdminAccountId);
        LOG.infov("IPAM organization admin delegated to {0}", delegatedAdminAccountId);
        return true;
    }

    public Optional<String> getIpamOrganizationAdminAccount() {
        return organizationAdmin();
    }

    public boolean disableIpamOrganizationAdminAccount(String delegatedAdminAccountId) {
        requireManagementAccount();
        Optional<String> existing = organizationAdmin();
        if (existing.isEmpty() || !existing.get().equals(delegatedAdminAccountId)) {
            throw new AwsException("InvalidParameterValue",
                    "Account " + delegatedAdminAccountId + " is not the IPAM delegated administrator.", 400);
        }
        deleteOrganizationAdmin();
        LOG.infov("IPAM organization admin delegation removed from {0}", delegatedAdminAccountId);
        return true;
    }

    private Optional<String> organizationAdmin() {
        if (settings instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<String> accountAware =
                    (AccountAwareStorageBackend<String>) rawAccountAware;
            return accountAware.getForAccount(ORGANIZATION_PARTITION, ADMIN_ACCOUNT_KEY);
        }
        return settings.get(ADMIN_ACCOUNT_KEY);
    }

    private void putOrganizationAdmin(String delegatedAdminAccountId) {
        if (settings instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<String> accountAware =
                    (AccountAwareStorageBackend<String>) rawAccountAware;
            accountAware.putForAccount(ORGANIZATION_PARTITION, ADMIN_ACCOUNT_KEY, delegatedAdminAccountId);
            return;
        }
        settings.put(ADMIN_ACCOUNT_KEY, delegatedAdminAccountId);
    }

    private void deleteOrganizationAdmin() {
        if (settings instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<String> accountAware =
                    (AccountAwareStorageBackend<String>) rawAccountAware;
            accountAware.deleteForAccount(ORGANIZATION_PARTITION, ADMIN_ACCOUNT_KEY);
            return;
        }
        settings.delete(ADMIN_ACCOUNT_KEY);
    }

    // ─── IPAMs + default scopes ─────────────────────────────────────────────

    /** {@code IpamTier}. Stored on the IPAM and echoed back by DescribeIpams. */
    private static final Set<String> TIERS = Set.of("free", "advanced");

    /** {@code IpamMeteredAccount}. Stored on the IPAM and echoed back by DescribeIpams. */
    private static final Set<String> METERED_ACCOUNTS = Set.of("ipam-owner", "resource-owner");

    /** {@code AddressFamily}. Decides how a pool's CIDRs are parsed, so a wrong one is not cosmetic. */
    private static final Set<String> ADDRESS_FAMILIES = Set.of("ipv4", "ipv6");

    /**
     * Rejects a value outside a modelled enum. An absent value is left to the caller's default:
     * every one of these members is optional, and the defaults applied below are AWS's own.
     */
    private static void requireEnum(String value, Set<String> allowed, String parameter) {
        if (value != null && !allowed.contains(value)) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + value + ") for parameter " + parameter + " is invalid. "
                            + "Valid values are: " + String.join(", ", new TreeSet<>(allowed)) + ".", 400);
        }
    }

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
                           String requestedOwnerId, Boolean enablePrivateGua, String meteredAccount,
                           String tier, String clientToken, List<Tag> tags) {
        // A null owner means "whoever is calling": resolve it the same way account-aware
        // storage picks its partition, so the recorded owner and the partition always agree.
        String ownerId = requestedOwnerId != null ? requestedOwnerId : callerAccountId();
        requireEnum(tier, TIERS, "Tier");
        requireEnum(meteredAccount, METERED_ACCOUNTS, "MeteredAccount");
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
        OwnedIpam ownedIpam = requireOwnedIpamForMutation(region, ipamId);
        Ipam ipam = ownedIpam.ipam();
        deleteIpamEntry(ownedIpam);
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
        requireEnum(tier, TIERS, "Tier");
        requireEnum(meteredAccount, METERED_ACCOUNTS, "MeteredAccount");
        OwnedIpam ownedIpam = requireOwnedIpamForMutation(region, ipamId);
        Ipam ipam = ownedIpam.ipam();
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
        saveIpam(ownedIpam);
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
        return createIpamPool(region, ipamScopeId, locale, sourceIpamPoolId, addressFamily,
                description, ownerId, null);
    }

    /**
     * Creates a pool, honouring {@code ClientToken}: a replay returns the pool the first call
     * created rather than minting a second one. Parameter differences on a replay are ignored,
     * as {@link #createIpam} already does.
     */
    public IpamPool createIpamPool(String region, String ipamScopeId, String locale,
                                   String sourceIpamPoolId, String addressFamily, String description,
                                   String requestedOwnerId, String clientToken) {
        // Same null-means-caller resolution as createIpam.
        String ownerId = requestedOwnerId != null ? requestedOwnerId : callerAccountId();
        if (ipamScopeId == null || ipamScopeId.isBlank()) {
            throw new AwsException("MissingParameter", "IpamScopeId is required.", 400);
        }
        requireEnum(addressFamily, ADDRESS_FAMILIES, "AddressFamily");
        if (sourceIpamPoolId != null && sourceIpamPoolId.isBlank()) {
            throw new AwsException("MissingParameter", "SourceIpamPoolId is required.", 400);
        }
        if (clientToken != null && !clientToken.isBlank()) {
            // Caller-partition scan: an idempotency token is scoped to the account that used it.
            for (IpamPool existing : pools.scan(k -> true)) {
                if (region.equals(existing.getRegion())
                        && clientToken.equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }
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
        pool.setClientToken(clientToken != null && !clientToken.isBlank() ? clientToken : null);
        pools.put(key(region, pool.getIpamPoolId()), pool);
        LOG.infov("Created IPAM pool {0} (scope {1}, source {2})",
                pool.getIpamPoolId(), ipamScopeId, sourceIpamPoolId);
        return pool;
    }

    public IpamPool modifyIpamPool(String region, String ipamPoolId, String description,
                                   Boolean autoImport, Integer allocationMinNetmaskLength,
                                   Integer allocationMaxNetmaskLength, Integer allocationDefaultNetmaskLength,
                                   boolean clearAllocationDefaultNetmaskLength) {
        OwnedPool ownedPool = requireOwnedPoolForMutation(region, ipamPoolId);
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

    /** Resolves the IPAM owning a scope. {@code IpamScopeId} is a required member of
     *  CreateIpamPool, so an unknown scope is an error rather than a null {@code ipamId}. */
    private String ipamIdOfScope(String ipamScopeId) {
        for (Ipam ipam : ipams.scan(k -> true)) {
            for (IpamScope scope : ipam.getScopes()) {
                if (ipamScopeId.equals(scope.getIpamScopeId())) {
                    return ipam.getIpamId();
                }
            }
        }
        throw new AwsException("InvalidIpamScopeId.NotFound",
                "IPAM scope " + ipamScopeId + " does not exist.", 400);
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
        OwnedPool ownedPool = requireOwnedPoolForMutation(region, ipamPoolId);
        IpamPool pool = ownedPool.pool();
        deletePool(ownedPool);
        pool.setState("delete-complete");
        return pool;
    }

    public IpamPoolCidr provisionIpamPoolCidr(String region, String ipamPoolId, String cidr) {
        return provisionIpamPoolCidr(region, ipamPoolId, cidr, null);
    }

    /**
     * Provisions a CIDR onto a pool, honouring {@code ClientToken}: a replay returns the CIDR the
     * first call provisioned instead of adding a duplicate entry.
     */
    public IpamPoolCidr provisionIpamPoolCidr(String region, String ipamPoolId, String cidr,
                                              String clientToken) {
        OwnedPool ownedPool = requireOwnedPoolForMutation(region, ipamPoolId);
        IpamPool pool = ownedPool.pool();
        if (clientToken != null && !clientToken.isBlank()) {
            for (IpamPoolCidr existing : pool.getProvisionedCidrs()) {
                if (clientToken.equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }
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
        poolCidr.setClientToken(clientToken != null && !clientToken.isBlank() ? clientToken : null);
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
        return allocateIpamPoolCidr(region, ipamPoolId, netmaskLength, cidr, description, null);
    }

    /**
     * Allocates a CIDR from a pool, honouring {@code ClientToken}. LZA's
     * {@code get-ipam-subnet-cidr} custom resource retries this call, and without the replay
     * check each retry would burn another distinct CIDR out of the pool.
     */
    public IpamPoolAllocation allocateIpamPoolCidr(String region, String ipamPoolId,
                                                   Integer netmaskLength, String cidr,
                                                   String description, String clientToken) {
        OwnedPool ownedPool = requireOwnedPool(region, ipamPoolId);
        IpamPool pool = ownedPool.pool();
        if (clientToken != null && !clientToken.isBlank()) {
            for (IpamPoolAllocation existing : pool.getAllocations()) {
                if (clientToken.equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }
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
        allocation.setClientToken(clientToken != null && !clientToken.isBlank() ? clientToken : null);
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
            String identifier = ipamPoolAllocationId != null
                    ? ipamPoolAllocationId
                    : "for CIDR " + cidr;
            throw new AwsException("InvalidIpamPoolAllocationId.NotFound",
                    "Allocation " + identifier + " not found in pool " + ipamPoolId + ".", 400);
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

    /**
     * Resolves an IPAM the way pools resolve — across every account's partition — and then rejects
     * a caller that does not own it. Modifying or deleting another account's IPAM is not an AWS
     * operation, and AWS reports an IPAM you cannot see as absent.
     */
    private OwnedIpam requireOwnedIpamForMutation(String region, String ipamId) {
        OwnedIpam ownedIpam = requireOwnedIpam(region, ipamId);
        if (ownedIpam.accountId() != null && !ownedIpam.accountId().equals(callerAccountId())) {
            throw ipamNotFound(ipamId);
        }
        return ownedIpam;
    }

    private OwnedIpam requireOwnedIpam(String region, String ipamId) {
        if (ipams instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<Ipam> accountAware =
                    (AccountAwareStorageBackend<Ipam>) rawAccountAware;
            Optional<AccountAwareStorageBackend.OwnedEntry<Ipam>> exact =
                    accountAware.findAnyAccountEntry(key(region, ipamId));
            if (exact.isPresent()) {
                var entry = exact.get();
                return new OwnedIpam(entry.account(), entry.value());
            }
            for (Ipam ipam : accountAware.scanAllAccounts()) {
                if (ipam.getIpamId().equals(ipamId)) {
                    var entry = accountAware.findAnyAccountEntry(
                            key(ipam.getRegion(), ipam.getIpamId())).orElseThrow();
                    return new OwnedIpam(entry.account(), entry.value());
                }
            }
            throw ipamNotFound(ipamId);
        }

        Optional<Ipam> direct = ipams.get(key(region, ipamId));
        if (direct.isPresent()) {
            return new OwnedIpam(null, direct.get());
        }
        for (Ipam ipam : ipams.scan(k -> true)) {
            if (ipam.getIpamId().equals(ipamId)) {
                return new OwnedIpam(null, ipam);
            }
        }
        throw ipamNotFound(ipamId);
    }

    private void saveIpam(OwnedIpam ownedIpam) {
        Ipam ipam = ownedIpam.ipam();
        String ipamKey = key(ipam.getRegion(), ipam.getIpamId());
        if (ownedIpam.accountId() != null
                && ipams instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<Ipam> accountAware =
                    (AccountAwareStorageBackend<Ipam>) rawAccountAware;
            accountAware.putForAccount(ownedIpam.accountId(), ipamKey, ipam);
            return;
        }
        ipams.put(ipamKey, ipam);
    }

    private void deleteIpamEntry(OwnedIpam ownedIpam) {
        Ipam ipam = ownedIpam.ipam();
        String ipamKey = key(ipam.getRegion(), ipam.getIpamId());
        if (ownedIpam.accountId() != null
                && ipams instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<Ipam> accountAware =
                    (AccountAwareStorageBackend<Ipam>) rawAccountAware;
            accountAware.deleteForAccount(ownedIpam.accountId(), ipamKey);
            return;
        }
        ipams.delete(ipamKey);
    }

    private static AwsException ipamNotFound(String ipamId) {
        return new AwsException("InvalidIpamId.NotFound", "IPAM " + ipamId + " does not exist.", 400);
    }

    private IpamPool requirePool(String region, String ipamPoolId) {
        return requireOwnedPool(region, ipamPoolId).pool();
    }

    /**
     * Resolves a pool for an operation on the pool itself. A RAM-shared pool permits cross-account
     * <em>allocation</em>, but Modify/Delete/Provision are owner-only in AWS, which reports a pool
     * you cannot act on as simply absent.
     */
    private OwnedPool requireOwnedPoolForMutation(String region, String ipamPoolId) {
        OwnedPool ownedPool = requireOwnedPool(region, ipamPoolId);
        if (ownedPool.accountId() != null && !ownedPool.accountId().equals(callerAccountId())) {
            throw poolNotFound(ipamPoolId);
        }
        return ownedPool;
    }

    private OwnedPool requireOwnedPool(String region, String ipamPoolId) {
        if (ipamPoolId == null || ipamPoolId.isBlank()) {
            throw new AwsException("MissingParameter", "IpamPoolId is required.", 400);
        }
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

    private record OwnedIpam(String accountId, Ipam ipam) {}

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
