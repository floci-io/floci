package io.floci.aws.services.cloudfront;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudfront.model.ConnectionGroup;
import io.floci.aws.services.cloudfront.model.Distribution;
import io.floci.aws.services.cloudfront.model.DistributionConfig;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Connection groups in isolation: one account, one mocked storage factory. Multi-tenant
 * distributions are served through a connection group, so the account's default group is minted
 * with the first {@code tenant-only} distribution and can never be deleted, as AWS documents.
 */
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class CloudFrontConnectionGroupServiceTest {

    private static final String ACCOUNT = "000000000000";

    private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

    private CloudFrontService service() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> stores.computeIfAbsent(invocation.getArgument(1, String.class),
                        ignored -> AccountAwareStorageBackend.inMemory("000000000000")));
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DnsConfig dnsConfig = mock(EmulatorConfig.DnsConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.CloudFrontServiceConfig cloudFrontConfig = mock(EmulatorConfig.CloudFrontServiceConfig.class);

        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        when(config.dns()).thenReturn(dnsConfig);
        when(dnsConfig.extraSuffixes()).thenReturn(Optional.empty());
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudfront()).thenReturn(cloudFrontConfig);
        when(cloudFrontConfig.domainSuffix()).thenReturn(Optional.empty());

        return new CloudFrontService(storageFactory, config, new RegionResolver("us-east-1", ACCOUNT));
    }

    private Distribution distributionWithConnectionMode(String connectionMode) {
        DistributionConfig config = new DistributionConfig();
        config.setCallerReference("connection-group-test-" + connectionMode);
        config.setConnectionMode(connectionMode);
        Distribution dist = new Distribution();
        dist.setConfig(config);
        return dist;
    }

    @Test
    void aTenantOnlyDistributionMintsTheAccountsDefaultConnectionGroup() {
        CloudFrontService service = service();
        service.createDistribution(distributionWithConnectionMode("tenant-only"), Map.of());

        List<ConnectionGroup> groups = service.listConnectionGroups(null, 100, null);
        assertEquals(1, groups.size());
        ConnectionGroup group = groups.getFirst();
        assertTrue(group.getIsDefault());
        assertTrue(group.getEnabled());
        assertEquals("default", group.getName());
        assertEquals("Deployed", group.getStatus());
        assertTrue(group.getId().startsWith("cg_"));
        assertTrue(group.getArn().endsWith(":connection-group/" + group.getId()));
        assertTrue(group.getArn().startsWith("arn:aws:cloudfront::" + ACCOUNT + ":"));
        assertTrue(group.getRoutingEndpoint().endsWith(".cloudfront.net"));
        assertFalse(group.getRoutingEndpoint().contains(group.getId().toLowerCase()));
        assertNotNullTime(group);
    }

    private static void assertNotNullTime(ConnectionGroup group) {
        assertTrue(group.getCreatedTime() instanceof Instant);
        assertEquals(group.getCreatedTime(), group.getLastModifiedTime());
    }

    @Test
    void aDirectDistributionMintsNoConnectionGroup() {
        CloudFrontService service = service();
        service.createDistribution(distributionWithConnectionMode("direct"), Map.of());
        assertEquals(0, service.listConnectionGroups(null, 100, null).size());
    }

    @Test
    void theDefaultConnectionGroupIsMintedOnlyOnceAndCannotBeDeleted() {
        CloudFrontService service = service();
        service.createDistribution(distributionWithConnectionMode("tenant-only"), Map.of());
        ConnectionGroup first = service.listConnectionGroups(null, 100, null).getFirst();
        service.createDistribution(distributionWithConnectionMode("tenant-only"), Map.of());
        assertEquals(1, service.listConnectionGroups(null, 100, null).size());

        AwsException e = assertThrows(AwsException.class,
                () -> service.deleteConnectionGroup(first.getId(), first.getEtag()));
        assertEquals("CannotDeleteEntityWhileInUse", e.getErrorCode());
        assertEquals(1, service.listConnectionGroups(null, 100, null).size());
    }

    @Test
    void createConnectionGroupStoresWhatTheRequestCarried() {
        CloudFrontService service = service();
        service.createDistribution(distributionWithConnectionMode("tenant-only"), Map.of());
        String distributionId = service.listDistributions(null, 10).getFirst().getId();

        ConnectionGroup group = service.createConnectionGroup("edge-group", null, true, false,
                distributionId, Map.of("team", "edge"));

        assertFalse(group.getIsDefault());
        assertTrue(group.getEnabled());
        assertFalse(group.getIpv6Enabled());
        assertEquals("edge-group", group.getName());
        assertEquals(distributionId, group.getViewerFallbackDistributionId());
        assertEquals("edge", group.getTags().get("team"));
    }

    @Test
    void duplicateNamesAndMissingFallbackDistributionsAreRefused() {
        CloudFrontService service = service();
        service.createConnectionGroup("group-a", null, null, null, null, Map.of());

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createConnectionGroup("group-a", null, null, null, null, Map.of()));
        assertEquals("EntityAlreadyExists", duplicate.getErrorCode());

        AwsException missing = assertThrows(AwsException.class,
                () -> service.createConnectionGroup("group-b", null, null, null, "nonexistent-dist", Map.of()));
        assertEquals("NoSuchDistribution", missing.getErrorCode());
    }

    @Test
    void aGroupIsReadableByIdNameArnAndRoutingEndpoint() {
        CloudFrontService service = service();
        ConnectionGroup created = service.createConnectionGroup("readable-group", null, null, null, null, Map.of());

        assertEquals(created, service.getConnectionGroup(created.getId()));
        assertEquals(created, service.getConnectionGroup(created.getName()));
        assertEquals(created, service.getConnectionGroup(created.getArn()));
        assertEquals(created, service.getConnectionGroupByRoutingEndpoint(created.getRoutingEndpoint()));

        AwsException byId = assertThrows(AwsException.class,
                () -> service.getConnectionGroup("cg_doesnotexist"));
        assertEquals("EntityNotFound", byId.getErrorCode());
        AwsException byEndpoint = assertThrows(AwsException.class,
                () -> service.getConnectionGroupByRoutingEndpoint("d0000000000000.cloudfront.net"));
        assertEquals("EntityNotFound", byEndpoint.getErrorCode());
    }

    @Test
    void updateRequiresTheCurrentEtagAndChangesTheOptionalMembers() {
        CloudFrontService service = service();
        ConnectionGroup created = service.createConnectionGroup("update-group", null, true, true, null, Map.of());
        String etagBefore = created.getEtag();
        Instant modifiedBefore = created.getLastModifiedTime();

        AwsException missing = assertThrows(AwsException.class,
                () -> service.updateConnectionGroup(created.getId(), null, null, false, null, null));
        assertEquals("InvalidIfMatchVersion", missing.getErrorCode());
        AwsException stale = assertThrows(AwsException.class,
                () -> service.updateConnectionGroup(created.getId(), "E000000000000", null, false, null, null));
        assertEquals("PreconditionFailed", stale.getErrorCode());

        ConnectionGroup updated = service.updateConnectionGroup(created.getId(), created.getEtag(),
                null, false, false, null);
        assertFalse(updated.getEnabled());
        assertFalse(updated.getIpv6Enabled());
        assertNotEquals(etagBefore, updated.getEtag());
        assertTrue(updated.getLastModifiedTime().isAfter(modifiedBefore));
        assertEquals("update-group", updated.getName());
    }

    @Test
    void deleteRequiresDisabledAndTheCurrentEtag() {
        CloudFrontService service = service();
        ConnectionGroup created = service.createConnectionGroup("delete-group", null, true, null, null, Map.of());

        AwsException enabled = assertThrows(AwsException.class,
                () -> service.deleteConnectionGroup(created.getId(), created.getEtag()));
        assertEquals("ResourceNotDisabled", enabled.getErrorCode());
        AwsException stale = assertThrows(AwsException.class,
                () -> service.deleteConnectionGroup(created.getId(), "E000000000000"));
        assertEquals("PreconditionFailed", stale.getErrorCode());

        service.updateConnectionGroup(created.getId(), created.getEtag(), null, false, null, null);
        ConnectionGroup disabled = service.getConnectionGroup(created.getId());
        service.deleteConnectionGroup(disabled.getId(), disabled.getEtag());
        AwsException gone = assertThrows(AwsException.class,
                () -> service.getConnectionGroup(created.getId()));
        assertEquals("EntityNotFound", gone.getErrorCode());
    }

    @Test
    void listConnectionGroupsSortsFiltersAndPaginates() {
        CloudFrontService service = service();
        service.createConnectionGroup("zeta", "aip_list-1", null, null, null, Map.of());
        service.createConnectionGroup("alpha", "aip_list-2", null, null, null, Map.of());
        service.createConnectionGroup("mike", null, null, null, null, Map.of());

        List<ConnectionGroup> all = service.listConnectionGroups(null, 100, null);
        assertEquals(List.of("alpha", "mike", "zeta"), all.stream().map(ConnectionGroup::getName).toList());

        List<ConnectionGroup> filtered = service.listConnectionGroups(null, 100, "aip_list-1");
        assertEquals(List.of("zeta"), filtered.stream().map(ConnectionGroup::getName).toList());

        List<ConnectionGroup> page = service.listConnectionGroups("alpha", 1, null);
        assertEquals(List.of("mike"), page.stream().map(ConnectionGroup::getName).toList());
    }

    @Test
    void listDistributionsByConnectionModeReturnsOnlyTheMatchingMode() {
        CloudFrontService service = service();
        Distribution tenantOnly = service.createDistribution(distributionWithConnectionMode("tenant-only"), Map.of());
        service.createDistribution(distributionWithConnectionMode("direct"), Map.of());

        List<Distribution> tenantOnlyList = service.listDistributionsByConnectionMode("tenant-only", null, 100);
        assertEquals(1, tenantOnlyList.size());
        assertEquals(tenantOnly.getId(), tenantOnlyList.getFirst().getId());

        List<Distribution> direct = service.listDistributionsByConnectionMode("direct", null, 100);
        assertEquals(1, direct.size());
        assertNotEquals(tenantOnly.getId(), direct.getFirst().getId());

        // A marker at the mode's only id leaves nothing after it.
        assertEquals(0, service.listDistributionsByConnectionMode("tenant-only", tenantOnly.getId(), 100).size());
    }

    @Test
    void anUnknownConnectionModeIsRefusedWithInvalidArgument() {
        CloudFrontService service = service();
        service.createDistribution(distributionWithConnectionMode("direct"), Map.of());

        AwsException e = assertThrows(AwsException.class,
                () -> service.listDistributionsByConnectionMode("bogus", null, 100));
        assertEquals("InvalidArgument", e.getErrorCode());
    }

    @Test
    void aDistributionStoredWithoutConnectionModeListsUnderDirect() {
        CloudFrontService service = service();
        Distribution dist = service.createDistribution(distributionWithConnectionMode(null), Map.of());

        List<Distribution> direct = service.listDistributionsByConnectionMode("direct", null, 100);
        assertEquals(List.of(dist.getId()), direct.stream().map(Distribution::getId).toList());
        assertEquals(0, service.listDistributionsByConnectionMode("tenant-only", null, 100).size());
    }

    @Test
    void updatingADistributionToTenantOnlyMintsTheDefaultGroup() {
        CloudFrontService service = service();
        Distribution created = service.createDistribution(distributionWithConnectionMode("direct"), Map.of());
        assertEquals(0, service.listConnectionGroups(null, 100, null).size());

        created.getConfig().setConnectionMode("tenant-only");
        service.updateDistribution(created.getId(), created.getEtag(), created);

        List<ConnectionGroup> groups = service.listConnectionGroups(null, 100, null);
        assertEquals(1, groups.size());
        assertTrue(groups.getFirst().getIsDefault());
    }

    @Test
    void deletingAFallbackDistributionClearsTheGroupsViewerFallback() {
        CloudFrontService service = service();
        Distribution dist = service.createDistribution(distributionWithConnectionMode("direct"), Map.of());
        String groupId = service.createConnectionGroup("fallback-group", null, null, null, dist.getId(), Map.of())
                .getId();

        service.deleteDistribution(dist.getId(), dist.getEtag());
        assertNull(service.getConnectionGroup(groupId).getViewerFallbackDistributionId());
    }

    @Test
    void theCloudFormationRemovalAlsoClearsTheGroupsViewerFallback() {
        CloudFrontService service = service();
        Distribution dist = service.createDistribution(distributionWithConnectionMode("direct"), Map.of());
        String groupId = service.createConnectionGroup("fallback-group", null, null, null, dist.getId(), Map.of())
                .getId();

        service.removeDistribution(dist.getId());
        assertNull(service.getConnectionGroup(groupId).getViewerFallbackDistributionId());
    }
}
