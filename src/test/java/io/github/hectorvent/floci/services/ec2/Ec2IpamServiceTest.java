package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.model.Ipam;
import io.github.hectorvent.floci.services.ec2.model.IpamPool;
import io.github.hectorvent.floci.services.ec2.model.IpamPoolAllocation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ec2IpamServiceTest {

    private static final String REGION = "us-east-1";

    private Ec2IpamService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        service = new Ec2IpamService(config,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    // --- organization admin delegation (the Organization-stage blocker, LZA
    // Custom::EnableIpamOrganizationAdminAccount) ---

    @Test
    void enableIpamOrganizationAdminAccountRecordsTheDelegatedAdmin() {
        assertTrue(service.enableIpamOrganizationAdminAccount("111111111111"));
        assertEquals("111111111111", service.getIpamOrganizationAdminAccount().orElseThrow());
        // enabling the same account again is idempotent, mirroring AWS
        assertTrue(service.enableIpamOrganizationAdminAccount("111111111111"));
    }

    @Test
    void enablingADifferentAccountWhileOneIsDelegatedThrows() {
        service.enableIpamOrganizationAdminAccount("111111111111");
        assertThrows(AwsException.class,
                () -> service.enableIpamOrganizationAdminAccount("222222222222"));
    }

    @Test
    void disableClearsTheDelegatedAdminAndRejectsMismatches() {
        service.enableIpamOrganizationAdminAccount("111111111111");
        assertThrows(AwsException.class,
                () -> service.disableIpamOrganizationAdminAccount("222222222222"));
        assertTrue(service.disableIpamOrganizationAdminAccount("111111111111"));
        assertTrue(service.getIpamOrganizationAdminAccount().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDelegatedAdminIsVisibleFromEveryAccountButOnlyManagementCanEnableOrConflict() {
        AtomicReference<String> caller = new AtomicReference<>("222222222222");
        RequestContext requestContext = mock(RequestContext.class);
        when(requestContext.getAccountId()).thenAnswer(invocation -> caller.get());
        Instance<RequestContext> contextInstance = mock(Instance.class);
        when(contextInstance.get()).thenReturn(requestContext);

        AccountAwareStorageBackend<String> settings = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), contextInstance, "000000000000");
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("222222222222");
        Ec2IpamService orgService = new Ec2IpamService(config, new InMemoryStorage<>(),
                new InMemoryStorage<>(), settings, new InMemoryStorage<>(), contextInstance);

        orgService.enableIpamOrganizationAdminAccount("333333333333");

        caller.set("111111111111");
        assertEquals("333333333333", orgService.getIpamOrganizationAdminAccount().orElseThrow(),
                "the delegated admin is an organization-wide setting, readable from any account");
        AwsException conflict = assertThrows(AwsException.class,
                () -> orgService.enableIpamOrganizationAdminAccount("444444444444"),
                "a non-management member account must not be able to change the org-wide delegation");
        assertEquals("UnauthorizedOperation", conflict.getErrorCode());

        caller.set("222222222222");
        AwsException error = assertThrows(AwsException.class,
                () -> orgService.enableIpamOrganizationAdminAccount("444444444444"));
        assertEquals("InvalidParameterValue", error.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyManagementAccountCanDisableTheDelegatedAdmin() {
        AtomicReference<String> caller = new AtomicReference<>("222222222222");
        RequestContext requestContext = mock(RequestContext.class);
        when(requestContext.getAccountId()).thenAnswer(invocation -> caller.get());
        Instance<RequestContext> contextInstance = mock(Instance.class);
        when(contextInstance.get()).thenReturn(requestContext);

        AccountAwareStorageBackend<String> settings = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), contextInstance, "000000000000");
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("222222222222");
        Ec2IpamService orgService = new Ec2IpamService(config, new InMemoryStorage<>(),
                new InMemoryStorage<>(), settings, new InMemoryStorage<>(), contextInstance);

        orgService.enableIpamOrganizationAdminAccount("333333333333");

        caller.set("111111111111");
        AwsException error = assertThrows(AwsException.class,
                () -> orgService.disableIpamOrganizationAdminAccount("333333333333"),
                "a non-management member account must not be able to remove the org-wide delegation");
        assertEquals("UnauthorizedOperation", error.getErrorCode());
        assertEquals("333333333333", orgService.getIpamOrganizationAdminAccount().orElseThrow());

        caller.set("222222222222");
        assertTrue(orgService.disableIpamOrganizationAdminAccount("333333333333"));
    }

    // --- IPAM + scopes ---

    @Test
    void createIpamCreatesDefaultScopesAndIsDescribable() {
        Ipam ipam = service.createIpam(REGION, "test ipam", List.of(REGION));
        assertTrue(ipam.getIpamId().startsWith("ipam-"));
        assertTrue(ipam.getPrivateDefaultScopeId().startsWith("ipam-scope-"));
        assertTrue(ipam.getPublicDefaultScopeId().startsWith("ipam-scope-"));
        assertNotNull(ipam.getIpamArn());

        List<Ipam> described = service.describeIpams(REGION, List.of());
        assertEquals(1, described.size());
        assertEquals(ipam.getIpamId(), described.get(0).getIpamId());
    }

    @Test
    void createIpamPreservesFullFieldsAndClientTokenIsIdempotent() {
        Ipam first = service.createIpam(REGION, "full", List.of(REGION, "us-west-2"),
                "000000000000", true, "resource-owner", "advanced", "token-1",
                List.of(new Tag("Name", "full")));
        Ipam second = service.createIpam(REGION, "different", List.of(REGION),
                "000000000000", false, "ipam-owner", "free", "token-1", List.of());
        assertEquals(first.getIpamId(), second.getIpamId());
        assertEquals(true, first.getEnablePrivateGua());
        assertEquals("resource-owner", first.getMeteredAccount());
        assertEquals("advanced", first.getTier());
        assertEquals("full", first.getTags().getFirst().getValue());
    }

    @Test
    void deleteIpamRemovesItAndUnknownIdsThrow() {
        Ipam ipam = service.createIpam(REGION, null, List.of(REGION));
        service.deleteIpam(REGION, ipam.getIpamId());
        assertTrue(service.describeIpams(REGION, List.of()).isEmpty());
        assertThrows(AwsException.class, () -> service.deleteIpam(REGION, "ipam-doesnotexist"));
    }

    @Test
    void associateIpamByoasnStoresAssociation() {
        service.associateIpamByoasn("us-east-1", "65000", "10.0.0.0/16");
        assertTrue(service.hasAsnAssociation("us-east-1", "65000", "10.0.0.0/16"));
    }

    @Test
    void associateIpamByoasnValidatesNullAndBlankInputs() {
        assertThrows(AwsException.class, () -> service.associateIpamByoasn("us-east-1", null, "10.0.0.0/16"));
        assertThrows(AwsException.class, () -> service.associateIpamByoasn("us-east-1", "", "10.0.0.0/16"));
        assertThrows(AwsException.class, () -> service.associateIpamByoasn("us-east-1", "65000", null));
        assertThrows(AwsException.class, () -> service.associateIpamByoasn("us-east-1", "65000", ""));
    }

    @Test
    void associateIpamByoasnPersistsAcrossServiceInstances() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        InMemoryStorage<String, io.github.hectorvent.floci.services.ec2.model.AsnAssociation> backend = new InMemoryStorage<>();
        Ec2IpamService firstService = new Ec2IpamService(config,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), backend);
        firstService.associateIpamByoasn("us-west-2", "65001", "192.168.0.0/16");
        Ec2IpamService secondService = new Ec2IpamService(config,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), backend);
        assertTrue(secondService.hasAsnAssociation("us-west-2", "65001", "192.168.0.0/16"));
    }

    @Test
    void modifyIpamUpdatesDescriptionAndOperatingRegions() {
        Ipam ipam = service.createIpam(REGION, "initial", List.of(REGION, "us-west-1"));

        Ipam modified = service.modifyIpam(
                REGION,
                ipam.getIpamId(),
                "updated",
                List.of("us-west-2", "us-west-1"),
                List.of(REGION));

        assertEquals("updated", modified.getDescription());
        assertEquals(List.of("us-west-1", "us-west-2"), modified.getOperatingRegions());
        assertEquals(modified.getIpamId(),
                service.describeIpams(REGION, List.of(ipam.getIpamId())).getFirst().getIpamId());
    }

    @Test
    void modifyIpamUnknownIdsThrow() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.modifyIpam(REGION, "ipam-doesnotexist", "updated", List.of(), List.of()));
        assertEquals("InvalidIpamId.NotFound", error.getErrorCode());
    }

    // --- pools + provisioned CIDRs ---

    @Test
    void createPoolProvisionCidrAndReadItBack() {
        Ipam ipam = service.createIpam(REGION, null, List.of(REGION));
        IpamPool pool = service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                REGION, null, "ipv4", "global pool");
        assertTrue(pool.getIpamPoolId().startsWith("ipam-pool-"));

        service.provisionIpamPoolCidr(REGION, pool.getIpamPoolId(), "10.0.0.0/8");
        var cidrs = service.getIpamPoolCidrs(REGION, pool.getIpamPoolId());
        assertEquals(1, cidrs.size());
        assertEquals("10.0.0.0/8", cidrs.get(0).getCidr());
        assertEquals("provisioned", cidrs.get(0).getState());
    }

    @Test
    void provisioningOutsideTheSourcePoolThrows() {
        Ipam ipam = service.createIpam(REGION, null, List.of(REGION));
        IpamPool parent = service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                REGION, null, "ipv4", "parent");
        service.provisionIpamPoolCidr(REGION, parent.getIpamPoolId(), "10.0.0.0/8");
        IpamPool child = service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                REGION, parent.getIpamPoolId(), "ipv4", "child");

        service.provisionIpamPoolCidr(REGION, child.getIpamPoolId(), "10.0.0.0/12");
        assertThrows(AwsException.class,
                () -> service.provisionIpamPoolCidr(REGION, child.getIpamPoolId(), "192.168.0.0/16"));
    }

    @Test
    void describeIpamPoolsFiltersById() {
        Ipam ipam = service.createIpam(REGION, null, List.of(REGION));
        IpamPool pool = service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                REGION, null, "ipv4", null);
        assertEquals(1, service.describeIpamPools(REGION, List.of(pool.getIpamPoolId())).size());
        assertThrows(AwsException.class,
                () -> service.describeIpamPools(REGION, List.of("ipam-pool-doesnotexist")));
    }

    @Test
    void omittingIpamPoolIdIsAMissingParameterNotANotFound() {
        for (Executable call : List.<Executable>of(
                () -> service.deleteIpamPool(REGION, null),
                () -> service.modifyIpamPool(REGION, "", "d", null, null, null, null, false),
                () -> service.provisionIpamPoolCidr(REGION, null, "10.0.0.0/8"),
                () -> service.getIpamPoolCidrs(REGION, ""),
                () -> service.allocateIpamPoolCidr(REGION, null, 24, null, null),
                () -> service.releaseIpamPoolAllocation(REGION, "", "alloc", null),
                () -> service.getIpamPoolAllocations(REGION, null))) {
            AwsException error = assertThrows(AwsException.class, call);
            assertEquals("MissingParameter", error.getErrorCode());
        }
    }

    @Test
    void createIpamPoolRejectsMissingAndUnknownScopes() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.createIpamPool(REGION, null, REGION, null, "ipv4", null));
        assertEquals("MissingParameter", missing.getErrorCode());

        AwsException unknown = assertThrows(AwsException.class,
                () -> service.createIpamPool(REGION, "ipam-scope-doesnotexist", REGION, null, "ipv4", null));
        assertEquals("InvalidIpamScopeId.NotFound", unknown.getErrorCode());
    }

    @Test
    void createIpamPoolRejectsABlankSourcePoolIdAsTheSourceParameter() {
        Ipam ipam = service.createIpam(REGION, null, List.of(REGION));
        AwsException error = assertThrows(AwsException.class,
                () -> service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                        REGION, "", "ipv4", null));
        assertEquals("MissingParameter", error.getErrorCode());
        assertTrue(error.getMessage().contains("SourceIpamPoolId"),
                "a blank SourceIpamPoolId must not be reported as a missing IpamPoolId");
    }

    // --- allocations (what LZA's get-ipam-subnet-cidr Lambda needs) ---

    private IpamPool poolWith(String... provisionedCidrs) {
        Ipam ipam = service.createIpam(REGION, null, List.of(REGION));
        IpamPool pool = service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                REGION, null, "ipv4", null);
        for (String cidr : provisionedCidrs) {
            service.provisionIpamPoolCidr(REGION, pool.getIpamPoolId(), cidr);
        }
        return pool;
    }

    @Test
    void allocateByNetmaskHandsOutSequentialFreeBlocks() {
        IpamPool pool = poolWith("10.0.0.0/16");
        IpamPoolAllocation first =
                service.allocateIpamPoolCidr(REGION, pool.getIpamPoolId(), 24, null, null);
        IpamPoolAllocation second =
                service.allocateIpamPoolCidr(REGION, pool.getIpamPoolId(), 24, null, null);
        assertEquals("10.0.0.0/24", first.getCidr());
        assertEquals("10.0.1.0/24", second.getCidr());
        assertTrue(first.getIpamPoolAllocationId().startsWith("ipam-pool-alloc-"));
    }

    @Test
    void allocateSkipsSpaceProvisionedToChildPools() {
        IpamPool parent = poolWith("10.0.0.0/16");
        IpamPool child = service.createIpamPool(REGION,
                service.describeIpamPools(REGION, List.of(parent.getIpamPoolId()))
                        .get(0).getIpamScopeId(),
                REGION, parent.getIpamPoolId(), "ipv4", "child");
        service.provisionIpamPoolCidr(REGION, child.getIpamPoolId(), "10.0.0.0/24");

        IpamPoolAllocation alloc =
                service.allocateIpamPoolCidr(REGION, parent.getIpamPoolId(), 24, null, null);
        assertEquals("10.0.1.0/24", alloc.getCidr());
    }

    @Test
    void allocateSpecificCidrAndRejectOverlap() {
        IpamPool pool = poolWith("10.0.0.0/16");
        IpamPoolAllocation alloc =
                service.allocateIpamPoolCidr(REGION, pool.getIpamPoolId(), null, "10.0.5.0/24", null);
        assertEquals("10.0.5.0/24", alloc.getCidr());
        assertThrows(AwsException.class,
                () -> service.allocateIpamPoolCidr(REGION, pool.getIpamPoolId(), null, "10.0.5.128/25", null));
    }

    @Test
    void allocationExhaustionThrows() {
        IpamPool pool = poolWith("10.0.0.0/24");
        service.allocateIpamPoolCidr(REGION, pool.getIpamPoolId(), 24, null, null);
        assertThrows(AwsException.class,
                () -> service.allocateIpamPoolCidr(REGION, pool.getIpamPoolId(), 24, null, null));
    }

    @Test
    void releaseFreesTheAllocationForReuse() {
        IpamPool pool = poolWith("10.0.0.0/24");
        IpamPoolAllocation alloc =
                service.allocateIpamPoolCidr(REGION, pool.getIpamPoolId(), 24, null, null);
        assertTrue(service.releaseIpamPoolAllocation(REGION, pool.getIpamPoolId(),
                alloc.getIpamPoolAllocationId(), alloc.getCidr()));
        IpamPoolAllocation again =
                service.allocateIpamPoolCidr(REGION, pool.getIpamPoolId(), 24, null, null);
        assertEquals("10.0.0.0/24", again.getCidr());
    }

    @Test
    void getIpamPoolAllocationsListsLiveAllocations() {
        IpamPool pool = poolWith("10.0.0.0/16");
        service.allocateIpamPoolCidr(REGION, pool.getIpamPoolId(), 24, null, "subnet-a");
        service.allocateIpamPoolCidr(REGION, pool.getIpamPoolId(), 24, null, "subnet-b");
        assertEquals(2, service.getIpamPoolAllocations(REGION, pool.getIpamPoolId()).size());
    }

    // --- ClientToken idempotency (LZA's get-ipam-subnet-cidr custom resource retries
    // AllocateIpamPoolCidr; a replay must not burn a second CIDR out of the pool) ---

    @Test
    void replayingAnAllocationClientTokenReturnsTheOriginalAllocation() {
        IpamPool pool = poolWith("10.0.0.0/16");
        IpamPoolAllocation first = service.allocateIpamPoolCidr(
                REGION, pool.getIpamPoolId(), 24, null, "subnet-a", "alloc-token");
        IpamPoolAllocation replay = service.allocateIpamPoolCidr(
                REGION, pool.getIpamPoolId(), 24, null, "subnet-a", "alloc-token");

        assertEquals(first.getIpamPoolAllocationId(), replay.getIpamPoolAllocationId());
        assertEquals(first.getCidr(), replay.getCidr());
        assertEquals(1, service.getIpamPoolAllocations(REGION, pool.getIpamPoolId()).size(),
                "a replayed ClientToken must not consume a second CIDR");
    }

    @Test
    void distinctAllocationClientTokensStillConsumeDistinctCidrs() {
        IpamPool pool = poolWith("10.0.0.0/16");
        IpamPoolAllocation first = service.allocateIpamPoolCidr(
                REGION, pool.getIpamPoolId(), 24, null, null, "token-a");
        IpamPoolAllocation second = service.allocateIpamPoolCidr(
                REGION, pool.getIpamPoolId(), 24, null, null, "token-b");
        assertEquals("10.0.0.0/24", first.getCidr());
        assertEquals("10.0.1.0/24", second.getCidr());
    }

    @Test
    void replayingAProvisionClientTokenReturnsTheOriginalCidr() {
        Ipam ipam = service.createIpam(REGION, null, List.of(REGION));
        IpamPool pool = service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                REGION, null, "ipv4", null);
        service.provisionIpamPoolCidr(REGION, pool.getIpamPoolId(), "10.0.0.0/8", "provision-token");
        service.provisionIpamPoolCidr(REGION, pool.getIpamPoolId(), "10.0.0.0/8", "provision-token");

        assertEquals(1, service.getIpamPoolCidrs(REGION, pool.getIpamPoolId()).size(),
                "a replayed ClientToken must not provision the CIDR twice");
    }

    @Test
    void replayingAPoolCreateClientTokenReturnsTheOriginalPool() {
        Ipam ipam = service.createIpam(REGION, null, List.of(REGION));
        IpamPool first = service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                REGION, null, "ipv4", "first", "000000000000", "pool-token");
        IpamPool replay = service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                REGION, null, "ipv4", "second", "000000000000", "pool-token");

        assertEquals(first.getIpamPoolId(), replay.getIpamPoolId());
        assertEquals(1, service.describeIpamPools(REGION, List.of()).size());
    }

    @Test
    void allocatingFromAnUnknownPoolThrows() {
        assertThrows(AwsException.class,
                () -> service.allocateIpamPoolCidr(REGION, "ipam-pool-doesnotexist", 24, null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allocationResolvesSharedPoolAndWritesBackToOwnerAccount() {
        String ownerAccount = "111111111111";
        String workloadAccount = "222222222222";
        AtomicReference<String> caller = new AtomicReference<>(ownerAccount);
        RequestContext requestContext = mock(RequestContext.class);
        when(requestContext.getAccountId()).thenAnswer(invocation -> caller.get());
        Instance<RequestContext> contextInstance = mock(Instance.class);
        when(contextInstance.get()).thenReturn(requestContext);

        AccountAwareStorageBackend<Ipam> ipams = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), contextInstance, "000000000000");
        AccountAwareStorageBackend<IpamPool> pools = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), contextInstance, "000000000000");
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(workloadAccount);
        Ec2IpamService accountAwareService = new Ec2IpamService(
                config, ipams, pools, new InMemoryStorage<>(), new InMemoryStorage<>(),
                contextInstance);

        Ipam ipam = accountAwareService.createIpam(REGION, null, List.of(REGION), ownerAccount);
        IpamPool pool = accountAwareService.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                REGION, null, "ipv4", "shared pool", ownerAccount);
        accountAwareService.provisionIpamPoolCidr(REGION, pool.getIpamPoolId(), "10.0.0.0/16");

        caller.set(workloadAccount);
        IpamPoolAllocation allocation = accountAwareService.allocateIpamPoolCidr(
                REGION, pool.getIpamPoolId(), 24, null, "shared-services subnet");

        assertEquals("10.0.0.0/24", allocation.getCidr());
        String poolKey = REGION + "|" + pool.getIpamPoolId();
        assertEquals(1, pools.getForAccount(ownerAccount, poolKey).orElseThrow()
                .getAllocations().size());
        assertTrue(pools.getForAccount(workloadAccount, poolKey).isEmpty(),
                "cross-account allocation must not fork the pool into the caller account");
    }

    /**
     * A RAM-shared pool lets a workload account allocate from it, but Modify/Delete/Provision of
     * the pool itself stay owner-only in AWS. The fixture puts the pool in {@code ownerAccount}
     * and leaves the caller set to {@code workloadAccount}.
     */
    @SuppressWarnings("unchecked")
    private record SharedPoolFixture(Ec2IpamService service, AccountAwareStorageBackend<IpamPool> pools,
                                     AccountAwareStorageBackend<Ipam> ipams, AtomicReference<String> caller,
                                     Ipam ipam, IpamPool pool) {}

    @SuppressWarnings("unchecked")
    private SharedPoolFixture sharedPoolOwnedByAnotherAccount() {
        String ownerAccount = "111111111111";
        String workloadAccount = "222222222222";
        AtomicReference<String> caller = new AtomicReference<>(ownerAccount);
        RequestContext requestContext = mock(RequestContext.class);
        when(requestContext.getAccountId()).thenAnswer(invocation -> caller.get());
        Instance<RequestContext> contextInstance = mock(Instance.class);
        when(contextInstance.get()).thenReturn(requestContext);

        AccountAwareStorageBackend<Ipam> ipams = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), contextInstance, "000000000000");
        AccountAwareStorageBackend<IpamPool> pools = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), contextInstance, "000000000000");
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(workloadAccount);
        Ec2IpamService service = new Ec2IpamService(
                config, ipams, pools, new InMemoryStorage<>(), new InMemoryStorage<>(),
                contextInstance);

        Ipam ipam = service.createIpam(REGION, null, List.of(REGION), ownerAccount);
        IpamPool pool = service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(),
                REGION, null, "ipv4", "shared pool", ownerAccount);
        service.provisionIpamPoolCidr(REGION, pool.getIpamPoolId(), "10.0.0.0/16");

        caller.set(workloadAccount);
        return new SharedPoolFixture(service, pools, ipams, caller, ipam, pool);
    }

    @Test
    void modifyingAnotherAccountsPoolIsRejected() {
        SharedPoolFixture fixture = sharedPoolOwnedByAnotherAccount();
        AwsException error = assertThrows(AwsException.class,
                () -> fixture.service().modifyIpamPool(REGION, fixture.pool().getIpamPoolId(),
                        "hijacked", null, null, null, null, false));
        assertEquals("InvalidIpamPoolId.NotFound", error.getErrorCode());
        assertEquals("shared pool",
                fixture.pools().getForAccount("111111111111", REGION + "|" + fixture.pool().getIpamPoolId())
                        .orElseThrow().getDescription());
    }

    @Test
    void deletingAnotherAccountsPoolIsRejected() {
        SharedPoolFixture fixture = sharedPoolOwnedByAnotherAccount();
        AwsException error = assertThrows(AwsException.class,
                () -> fixture.service().deleteIpamPool(REGION, fixture.pool().getIpamPoolId()));
        assertEquals("InvalidIpamPoolId.NotFound", error.getErrorCode());
        assertTrue(fixture.pools()
                .getForAccount("111111111111", REGION + "|" + fixture.pool().getIpamPoolId()).isPresent());
    }

    @Test
    void provisioningIntoAnotherAccountsPoolIsRejected() {
        SharedPoolFixture fixture = sharedPoolOwnedByAnotherAccount();
        AwsException error = assertThrows(AwsException.class,
                () -> fixture.service().provisionIpamPoolCidr(
                        REGION, fixture.pool().getIpamPoolId(), "192.168.0.0/16"));
        assertEquals("InvalidIpamPoolId.NotFound", error.getErrorCode());
        assertEquals(1, fixture.pools()
                .getForAccount("111111111111", REGION + "|" + fixture.pool().getIpamPoolId())
                .orElseThrow().getProvisionedCidrs().size());
    }

    @Test
    void allocatingFromAnotherAccountsSharedPoolIsStillAllowed() {
        SharedPoolFixture fixture = sharedPoolOwnedByAnotherAccount();
        IpamPoolAllocation allocation = fixture.service().allocateIpamPoolCidr(
                REGION, fixture.pool().getIpamPoolId(), 24, null, "workload subnet");
        assertEquals("10.0.0.0/24", allocation.getCidr());
    }

    @Test
    void deletingAnotherAccountsIpamIsRejectedAndLeavesItIntact() {
        SharedPoolFixture fixture = sharedPoolOwnedByAnotherAccount();
        AwsException error = assertThrows(AwsException.class,
                () -> fixture.service().deleteIpam(REGION, fixture.ipam().getIpamId()));
        assertEquals("InvalidIpamId.NotFound", error.getErrorCode());
        assertTrue(fixture.ipams()
                .getForAccount("111111111111", REGION + "|" + fixture.ipam().getIpamId()).isPresent());
    }

    @Test
    void modifyingAnotherAccountsIpamIsRejectedAndForksNoCopy() {
        SharedPoolFixture fixture = sharedPoolOwnedByAnotherAccount();
        AwsException error = assertThrows(AwsException.class,
                () -> fixture.service().modifyIpam(REGION, fixture.ipam().getIpamId(),
                        "hijacked", List.of(), List.of()));
        assertEquals("InvalidIpamId.NotFound", error.getErrorCode());
        assertTrue(fixture.ipams()
                        .getForAccount("222222222222", REGION + "|" + fixture.ipam().getIpamId()).isEmpty(),
                "a rejected cross-account modify must not fork the IPAM into the caller account");
    }

    // --- modelled enums (Tier, MeteredAccount, AddressFamily) ---

    @Test
    void createIpamRejectsAnUnmodelledTier() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.createIpam(REGION, "bad tier", List.of(REGION), "000000000000",
                        false, "ipam-owner", "platinum", null, List.of()));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertTrue(service.describeIpams(REGION, List.of()).isEmpty(),
                "a rejected CreateIpam must not have stored an IPAM");
    }

    @Test
    void createIpamRejectsAnUnmodelledMeteredAccount() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.createIpam(REGION, "bad metered account", List.of(REGION), "000000000000",
                        false, "someone-else", "free", null, List.of()));
        assertEquals("InvalidParameterValue", error.getErrorCode());
    }

    @Test
    void createIpamAcceptsBothModelledTiers() {
        assertEquals("free", service.createIpam(REGION, "free tier", List.of(REGION), "000000000000",
                false, "ipam-owner", "free", null, List.of()).getTier());
        assertEquals("advanced", service.createIpam(REGION, "advanced tier", List.of(REGION), "000000000000",
                false, "resource-owner", "advanced", null, List.of()).getTier());
    }

    @Test
    void modifyIpamRejectsAnUnmodelledTierAndLeavesTheStoredOneIntact() {
        Ipam ipam = service.createIpam(REGION, "initial", List.of(REGION));
        AwsException error = assertThrows(AwsException.class,
                () -> service.modifyIpam(REGION, ipam.getIpamId(), null, List.of(), List.of(),
                        null, null, "platinum"));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals("free", service.describeIpams(REGION, List.of(ipam.getIpamId())).getFirst().getTier());
    }

    @Test
    void createIpamPoolRejectsAnUnmodelledAddressFamily() {
        Ipam ipam = service.createIpam(REGION, null, List.of(REGION));
        AwsException error = assertThrows(AwsException.class,
                () -> service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(), REGION,
                        null, "ipv5", "bad address family"));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertTrue(service.describeIpamPools(REGION, List.of()).isEmpty(),
                "a rejected CreateIpamPool must not have stored a pool");
    }

    @Test
    void createIpamPoolAcceptsBothModelledAddressFamilies() {
        Ipam ipam = service.createIpam(REGION, null, List.of(REGION));
        assertEquals("ipv4", service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(), REGION,
                null, "ipv4", "v4 pool").getAddressFamily());
        assertEquals("ipv6", service.createIpamPool(REGION, ipam.getPrivateDefaultScopeId(), REGION,
                null, "ipv6", "v6 pool").getAddressFamily());
    }
}
