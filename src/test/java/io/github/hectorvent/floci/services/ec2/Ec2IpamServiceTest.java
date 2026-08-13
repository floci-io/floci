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
                config, ipams, pools, new InMemoryStorage<>(), new InMemoryStorage<>());

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
}
