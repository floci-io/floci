package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.ClusterNode;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupSettings;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.core.common.AwsException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the empty-list read responses for the subnet/parameter group describes, so
 * SDK clients get a valid 200 instead of failing with UnsupportedOperation (400).
 */
class ElastiCacheQueryHandlerTest {

    private ElastiCacheQueryHandler handler;
    private ElastiCacheService service;
    private ElastiCacheMemcachedService memcachedService;

    @BeforeEach
    void setUp() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        service = mock(ElastiCacheService.class);
        memcachedService = mock(ElastiCacheMemcachedService.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getRegion()).thenReturn("us-east-1");
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        handler = new ElastiCacheQueryHandler(sigV4Validator, service, memcachedService, regionResolver);
    }

    @Test
    void describeCacheSubnetGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheSubnetGroups", params(), "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheSubnetGroupsResult><CacheSubnetGroups></CacheSubnetGroups></DescribeCacheSubnetGroupsResult>"),
                "Expected empty CacheSubnetGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void describeCacheParameterGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheParameterGroups", params(), "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheParameterGroupsResult><CacheParameterGroups></CacheParameterGroups></DescribeCacheParameterGroupsResult>"),
                "Expected empty CacheParameterGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void describeReplicationGroups_reportsClusterModeTopology() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setClusterEnabled(true);
        group.setEngine("valkey");
        group.setAutomaticFailoverEnabled(true);
        group.setClusterNodes(List.of(
                new ClusterNode("grp-0001-001", "0001", true, 6379, "0-8191"),
                new ClusterNode("grp-0002-001", "0002", true, 6380, "8192-16383")));
        when(service.listReplicationGroups("grp")).thenReturn(List.of(group));
        when(service.memberCacheClusters(group)).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "grp-0001-001", 6379, true),
                new ElastiCacheService.MemberCacheCluster(group, "grp-0002-001", 6380, true)));

        MultivaluedMap<String, String> params = params();
        params.putSingle("ReplicationGroupId", "grp");
        Response response = handler.handle("DescribeReplicationGroups", params, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<ClusterEnabled>true</ClusterEnabled>"));
        assertTrue(body.contains("<ClusterMode>enabled</ClusterMode>"));
        assertTrue(body.contains("<AutomaticFailover>enabled</AutomaticFailover>"));
        assertTrue(body.contains("<Engine>valkey</Engine>"));
        assertTrue(body.contains("<ClusterId>grp-0001-001</ClusterId>"));
        assertTrue(body.contains("<ClusterId>grp-0002-001</ClusterId>"));
        assertTrue(body.contains("<NodeGroupId>0002</NodeGroupId>"));
        assertTrue(body.contains("<Slots>8192-16383</Slots>"));
        assertTrue(body.contains("<CacheClusterId>grp-0002-001</CacheClusterId>"));
        assertFalse(body.contains("<PrimaryEndpoint>"),
                "Cluster-mode node groups carry Slots, not a PrimaryEndpoint");
    }

    @Test
    void describeReplicationGroups_reportsSingleNodeGroupWithPrimaryEndpoint() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setEngine("valkey");
        when(service.listReplicationGroups("grp")).thenReturn(List.of(group));
        when(service.memberCacheClusters(group)).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "grp-001", 6379, true)));

        MultivaluedMap<String, String> params = params();
        params.putSingle("ReplicationGroupId", "grp");
        Response response = handler.handle("DescribeReplicationGroups", params, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<ClusterEnabled>false</ClusterEnabled>"));
        assertTrue(body.contains("<ClusterId>grp-001</ClusterId>"));
        assertTrue(body.contains("<PrimaryEndpoint><Address>localhost</Address><Port>6379</Port></PrimaryEndpoint>"));
        assertTrue(body.contains("<CurrentRole>primary</CurrentRole>"));
    }

    @Test
    void describeCacheClusters_answersForReplicationGroupMembers() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setEngine("valkey");
        group.setEngineVersion("8.2");
        group.setCacheNodeType("cache.t4g.micro");
        when(service.listMemberCacheClusters("grp-0001-001")).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "grp-0001-001", 6379, true)));

        MultivaluedMap<String, String> params = params();
        params.putSingle("CacheClusterId", "grp-0001-001");
        params.putSingle("ShowCacheNodeInfo", "true");
        Response response = handler.handle("DescribeCacheClusters", params, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CacheClusterId>grp-0001-001</CacheClusterId>"));
        assertTrue(body.contains("<CacheClusterStatus>available</CacheClusterStatus>"));
        assertTrue(body.contains("<ReplicationGroupId>grp</ReplicationGroupId>"));
        assertTrue(body.contains("<Engine>valkey</Engine>"));
        assertTrue(body.contains("<EngineVersion>8.2</EngineVersion>"));
        assertTrue(body.contains("<CacheNodeType>cache.t4g.micro</CacheNodeType>"));
        assertTrue(body.contains("<Endpoint><Address>localhost</Address><Port>6379</Port></Endpoint>"));
    }

    @Test
    void describeCacheClusters_membersOfFailedGroupReportRestoreFailed() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.CREATE_FAILED,
                AuthMode.NO_AUTH, null, Instant.now(), 6379);
        when(service.listMemberCacheClusters("grp-0001-001")).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "grp-0001-001", 6379, true)));

        MultivaluedMap<String, String> params = params();
        params.putSingle("CacheClusterId", "grp-0001-001");
        params.putSingle("ShowCacheNodeInfo", "true");
        Response response = handler.handle("DescribeCacheClusters", params, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CacheClusterStatus>restore-failed</CacheClusterStatus>"),
                "CacheClusterStatus has no create-failed; the documented failure value is restore-failed: " + body);
        assertFalse(body.contains("<CacheNodes>"),
                "A failed group has no endpoint, so members must not hand out node endpoints");
    }

    @Test
    void describeReplicationGroups_reportsCreateFailedStatusInAwsWireForm() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.CREATE_FAILED,
                AuthMode.NO_AUTH, null, Instant.now(), 6379);
        when(service.listReplicationGroups("grp")).thenReturn(List.of(group));
        when(service.memberCacheClusters(group)).thenReturn(List.of());

        MultivaluedMap<String, String> params = params();
        params.putSingle("ReplicationGroupId", "grp");
        Response response = handler.handle("DescribeReplicationGroups", params, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<Status>create-failed</Status>"),
                "AWS reports this status hyphenated, not as the enum constant");
    }

    @Test
    void unsupportedOperationStillReturnsQueryError() {
        Response response = handler.handle("NoSuchAction", params(), "us-east-1");

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("UnsupportedOperation"));
    }

    private static MultivaluedMap<String, String> params() {
        return new MultivaluedHashMap<>();
    }

    private static ReplicationGroup group(String id) {
        ReplicationGroup g = new ReplicationGroup(id, "d", ReplicationGroupStatus.AVAILABLE, AuthMode.NO_AUTH,
                new Endpoint("localhost", 6379), java.time.Instant.now(), 6379);
        g.setArn("arn:aws:elasticache:us-east-1:000000000000:replicationgroup:" + id);
        return g;
    }

    @Test
    void createReplicationGroup_passesSettingsAndTagsToService() {
        when(service.createReplicationGroup(any(ElastiCacheService.CreateReplicationGroupRequest.class)))
                .thenReturn(group("g1"));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");
        p.add("ReplicationGroupDescription", "d");
        p.add("AtRestEncryptionEnabled", "true");
        p.add("KmsKeyId", "alias/cache");
        p.add("SnapshotRetentionLimit", "7");
        p.add("SnapshotWindow", "06:30-07:30");
        p.add("Tags.Tag.1.Key", "Name");
        p.add("Tags.Tag.1.Value", "g1");

        assertEquals(200, handler.handle("CreateReplicationGroup", p, "us-east-1").getStatus());

        ArgumentCaptor<ElastiCacheService.CreateReplicationGroupRequest> captor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateReplicationGroupRequest.class);
        verify(service).createReplicationGroup(captor.capture());
        ElastiCacheService.CreateReplicationGroupRequest request = captor.getValue();
        assertEquals("g1", request.replicationGroupId());
        assertEquals("d", request.description());
        assertEquals(AuthMode.NO_AUTH, request.authMode());
        assertEquals("us-east-1", request.region());
        assertEquals(new ReplicationGroupSettings(true, "alias/cache", 7, "06:30-07:30"), request.settings());
        assertEquals(Map.of("Name", "g1"), request.tags());
    }

    @Test
    void createReplicationGroup_passesTheRequestedPortToService() {
        // Port is a modeled optional input on CreateReplicationGroup; the handler dropped it,
        // so a caller that pinned one always read back whatever port floci allocated.
        when(service.createReplicationGroup(any(ElastiCacheService.CreateReplicationGroupRequest.class)))
                .thenReturn(group("g1"));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");
        p.add("ReplicationGroupDescription", "d");
        p.add("Port", "6395");

        assertEquals(200, handler.handle("CreateReplicationGroup", p, "us-east-1").getStatus());

        ArgumentCaptor<ElastiCacheService.CreateReplicationGroupRequest> captor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateReplicationGroupRequest.class);
        verify(service).createReplicationGroup(captor.capture());
        assertEquals(6395, captor.getValue().port());
    }

    @Test
    void createReplicationGroup_leavesPortNullWhenAbsent() {
        when(service.createReplicationGroup(any(ElastiCacheService.CreateReplicationGroupRequest.class)))
                .thenReturn(group("g1"));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");
        p.add("ReplicationGroupDescription", "d");

        assertEquals(200, handler.handle("CreateReplicationGroup", p, "us-east-1").getStatus());

        ArgumentCaptor<ElastiCacheService.CreateReplicationGroupRequest> captor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateReplicationGroupRequest.class);
        verify(service).createReplicationGroup(captor.capture());
        assertNull(captor.getValue().port(),
                "An absent Port must stay null so the service allocates as before");
    }

    @Test
    void createReplicationGroup_readsTheEncryptionFlagAsAwsDoes() {
        when(service.createReplicationGroup(any(ElastiCacheService.CreateReplicationGroupRequest.class)))
                .thenReturn(group("g1"));
        ArgumentCaptor<ElastiCacheService.CreateReplicationGroupRequest> captor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateReplicationGroupRequest.class);
        // a live account reads anything but "false" as true — probed with banana, yes and "TRUE "
        for (String value : new String[] {"true", "banana", "yes", "TRUE "}) {
            MultivaluedMap<String, String> p = params();
            p.add("ReplicationGroupId", "g1");
            p.add("AtRestEncryptionEnabled", value);
            handler.handle("CreateReplicationGroup", p, "us-east-1");
        }
        for (String value : new String[] {"false", "FALSE"}) {
            MultivaluedMap<String, String> p = params();
            p.add("ReplicationGroupId", "g1");
            p.add("AtRestEncryptionEnabled", value);
            handler.handle("CreateReplicationGroup", p, "us-east-1");
        }
        MultivaluedMap<String, String> omitted = params();
        omitted.add("ReplicationGroupId", "g1");
        handler.handle("CreateReplicationGroup", omitted, "us-east-1");

        verify(service, times(7)).createReplicationGroup(captor.capture());
        List<Boolean> seen = captor.getAllValues().stream()
                .map(r -> r.settings().atRestEncryptionEnabled()).toList();
        assertEquals(java.util.Arrays.asList(true, true, true, true, false, false, null), seen);
    }

    @Test
    void describeReplicationGroups_emitsStoredSettingsAndArn() {
        ReplicationGroup g = group("g1");
        g.setAtRestEncryptionEnabled(true);
        g.setKmsKeyId("arn:aws:kms:us-east-1:000000000000:key/k1");
        g.setSnapshotRetentionLimit(7);
        g.setSnapshotWindow("06:30-07:30");
        when(service.listReplicationGroups("g1")).thenReturn(List.of(g));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");

        String body = (String) handler.handle("DescribeReplicationGroups", p, "us-east-1").getEntity();

        assertTrue(body.contains("<AtRestEncryptionEnabled>true</AtRestEncryptionEnabled>"), body);
        assertTrue(body.contains("<KmsKeyId>arn:aws:kms:us-east-1:000000000000:key/k1</KmsKeyId>"), body);
        assertTrue(body.contains("<SnapshotRetentionLimit>7</SnapshotRetentionLimit>"), body);
        assertTrue(body.contains("<SnapshotWindow>06:30-07:30</SnapshotWindow>"), body);
        assertTrue(body.contains("<ARN>arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g1</ARN>"), body);
    }

    /**
     * A group created with TransitEncryptionEnabled=true and no auth token is recorded as
     * AuthMode.IAM. Reporting the auth-token flag as TransitEncryptionEnabled answered false for
     * it, which Terraform reads back on every plan as drift on transit_encryption_enabled.
     */
    @Test
    void describeReplicationGroups_reportsTransitEncryptionWithoutAnAuthToken() {
        ReplicationGroup g = new ReplicationGroup("g1", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.IAM, new Endpoint("localhost", 6379), Instant.now(), 6379);
        when(service.listReplicationGroups("g1")).thenReturn(List.of(g));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");

        String body = (String) handler.handle("DescribeReplicationGroups", p, "us-east-1").getEntity();

        assertTrue(body.contains("<TransitEncryptionEnabled>true</TransitEncryptionEnabled>"), body);
        // ... and it is still distinct from AuthTokenEnabled, which only a token sets.
        assertTrue(body.contains("<AuthTokenEnabled>false</AuthTokenEnabled>"), body);
    }

    @Test
    void describeReplicationGroups_reportsNoTransitEncryptionForAPlainGroup() {
        when(service.listReplicationGroups("g1")).thenReturn(List.of(group("g1")));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");

        String body = (String) handler.handle("DescribeReplicationGroups", p, "us-east-1").getEntity();

        assertTrue(body.contains("<TransitEncryptionEnabled>false</TransitEncryptionEnabled>"), body);
    }

    /**
     * The member-cluster view of the same group reported AtRestEncryptionEnabled as a hardcoded
     * false, so DescribeCacheClusters contradicted DescribeReplicationGroups about one group.
     */
    @Test
    void describeCacheClusters_reportTheGroupsRealEncryptionFlags() {
        ReplicationGroup group = new ReplicationGroup("grp", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.IAM, new Endpoint("localhost", 6379), Instant.now(), 6379);
        group.setAtRestEncryptionEnabled(true);
        when(service.listMemberCacheClusters("grp-0001-001")).thenReturn(List.of(
                new ElastiCacheService.MemberCacheCluster(group, "grp-0001-001", 6379, true)));

        MultivaluedMap<String, String> params = params();
        params.putSingle("CacheClusterId", "grp-0001-001");
        String body = (String) handler.handle("DescribeCacheClusters", params, "us-east-1").getEntity();

        assertTrue(body.contains("<TransitEncryptionEnabled>true</TransitEncryptionEnabled>"), body);
        assertTrue(body.contains("<AtRestEncryptionEnabled>true</AtRestEncryptionEnabled>"), body);
    }

    @Test
    void listTagsForResource_readsAReplicationGroupByItsArn() {
        ReplicationGroup g = group("g1");
        g.setTags(new java.util.LinkedHashMap<>(Map.of("Name", "g1")));
        when(service.getReplicationGroup("g1")).thenReturn(g);
        when(service.getReplicationGroup("absent")).thenThrow(
                new AwsException("ReplicationGroupNotFoundFault", "Replication group absent not found.", 404));

        MultivaluedMap<String, String> p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g1");
        String body = (String) handler.handle("ListTagsForResource", p, "us-east-1").getEntity();
        assertTrue(body.contains("<Key>Name</Key>"), body);

        p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:replicationgroup:absent");
        Response response = handler.handle("ListTagsForResource", p, "us-east-1");
        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ReplicationGroupNotFoundFault"));

        // the store keys groups by id alone: a same-named group created under another region is
        // not the one this ARN names
        ReplicationGroup elsewhere = group("g2");
        elsewhere.setArn("arn:aws:elasticache:eu-west-1:000000000000:replicationgroup:g2");
        elsewhere.setTags(new java.util.LinkedHashMap<>(Map.of("Name", "west")));
        when(service.getReplicationGroup("g2")).thenReturn(elsewhere);
        p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g2");
        response = handler.handle("ListTagsForResource", p, "us-east-1");
        assertEquals(404, response.getStatus(), (String) response.getEntity());
        assertFalse(((String) response.getEntity()).contains("west"));
    }

    @Test
    void modifyReplicationGroup_passesSnapshotSettingsOnly() {
        when(service.modifyReplicationGroup(eq("g1"), isNull(), isNull(), any())).thenReturn(group("g1"));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");
        p.add("SnapshotRetentionLimit", "3");
        p.add("SnapshotWindow", "01:00-02:00");
        p.add("AtRestEncryptionEnabled", "true");
        p.add("KmsKeyId", "alias/other");

        assertEquals(200, handler.handle("ModifyReplicationGroup", p, "us-east-1").getStatus());
        verify(service).modifyReplicationGroup("g1", null, null, new ReplicationGroupSettings(null, null, 3, "01:00-02:00"));
    }

    // ── CreateCacheCluster / DescribeCacheClusters: single-node redis ─────────

    private static CacheCluster redisCacheCluster(String id) {
        CacheCluster cluster = new CacheCluster(id, CacheClusterStatus.AVAILABLE, "redis", "7.1",
                new Endpoint("localhost", 6379), Instant.now());
        cluster.setNumCacheNodes(1);
        cluster.setCacheNodeType("cache.t4g.micro");
        cluster.setAuthMode(AuthMode.NO_AUTH);
        cluster.setArn("arn:aws:elasticache:us-east-1:000000000000:cluster:" + id);
        cluster.setSnapshotRetentionLimit(5);
        cluster.setSnapshotWindow("03:00-05:00");
        cluster.setPreferredMaintenanceWindow("tue:04:00-tue:05:00");
        cluster.setPreferredAvailabilityZone("us-east-1b");
        cluster.setNetworkType("ipv4");
        cluster.setIpDiscovery("ipv4");
        cluster.setSecurityGroupIds(new java.util.ArrayList<>(List.of("sg-123")));
        return cluster;
    }

    @Test
    void createCacheCluster_redisIsAcceptedAndProvisionedAsASingleNodeCluster() {
        // Real AWS accepts CreateCacheCluster with Engine=redis, and terraform's
        // aws_elasticache_cluster with engine "redis" emits exactly this call.
        ArgumentCaptor<ElastiCacheService.CreateCacheClusterRequest> captor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateCacheClusterRequest.class);
        when(service.createCacheCluster(captor.capture())).thenReturn(redisCacheCluster("tf-redis"));

        MultivaluedMap<String, String> p = params();
        p.add("CacheClusterId", "tf-redis");
        p.add("Engine", "redis");
        p.add("NumCacheNodes", "1");
        p.add("CacheNodeType", "cache.t4g.micro");
        p.add("Port", "6379");

        Response response = handler.handle("CreateCacheCluster", p, "us-east-1");

        assertEquals(200, response.getStatus(), (String) response.getEntity());
        assertEquals("redis", captor.getValue().engine());
        assertEquals(1, captor.getValue().numCacheNodes());
        assertEquals(6379, captor.getValue().port());
        assertEquals("us-east-1", captor.getValue().region());
        verify(memcachedService, never()).createCacheCluster(anyString());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<Engine>redis</Engine>"), body);
        assertTrue(body.contains("<NumCacheNodes>1</NumCacheNodes>"), body);
        assertTrue(body.contains("<CacheNodeType>cache.t4g.micro</CacheNodeType>"), body);
        // AWS reports CacheNodes only on a describe that asked for node info
        assertFalse(body.contains("<CacheNodes>"), body);
    }

    @Test
    void createCacheCluster_passesEveryOptionalMemberTheRequestCarries() {
        // Each of these is an optional aws_elasticache_cluster argument: one dropped here reads
        // back as unset on the next plan and terraform can never settle the diff.
        ArgumentCaptor<ElastiCacheService.CreateCacheClusterRequest> captor =
                ArgumentCaptor.forClass(ElastiCacheService.CreateCacheClusterRequest.class);
        when(service.createCacheCluster(captor.capture())).thenReturn(redisCacheCluster("tf-redis"));

        MultivaluedMap<String, String> p = params();
        p.add("CacheClusterId", "tf-redis");
        p.add("Engine", "redis");
        p.add("SnapshotRetentionLimit", "5");
        p.add("SnapshotWindow", "03:00-05:00");
        p.add("PreferredMaintenanceWindow", "tue:04:00-tue:05:00");
        p.add("PreferredAvailabilityZone", "us-east-1b");
        p.add("SecurityGroupIds.member.1", "sg-123");
        p.add("NetworkType", "ipv4");
        p.add("IpDiscovery", "ipv4");
        p.add("AtRestEncryptionEnabled", "true");

        assertEquals(200, handler.handle("CreateCacheCluster", p, "us-east-1").getStatus());

        ElastiCacheService.CreateCacheClusterRequest request = captor.getValue();
        assertEquals(5, request.snapshotRetentionLimit());
        assertEquals("03:00-05:00", request.snapshotWindow());
        assertEquals("tue:04:00-tue:05:00", request.preferredMaintenanceWindow());
        assertEquals("us-east-1b", request.preferredAvailabilityZone());
        assertEquals(List.of("sg-123"), request.securityGroupIds());
        assertEquals("ipv4", request.networkType());
        assertEquals("ipv4", request.ipDiscovery());
        assertEquals(Boolean.TRUE, request.atRestEncryptionEnabled());
    }

    @Test
    void createCacheCluster_unknownEngineIsStillRefused() {
        MultivaluedMap<String, String> p = params();
        p.add("CacheClusterId", "mystery");
        p.add("Engine", "mongodb");

        Response response = handler.handle("CreateCacheCluster", p, "us-east-1");

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("InvalidParameterValue"));
        verifyNoInteractions(memcachedService);
    }

    @Test
    void createCacheCluster_memcachedStillGoesToTheMemcachedService() {
        when(memcachedService.createCacheCluster("mc")).thenReturn(new CacheCluster(
                "mc", CacheClusterStatus.AVAILABLE, "memcached", "1.6.22",
                new Endpoint("localhost", 11211), Instant.now()));

        MultivaluedMap<String, String> p = params();
        p.add("CacheClusterId", "mc");
        p.add("Engine", "memcached");

        String body = (String) handler.handle("CreateCacheCluster", p, "us-east-1").getEntity();

        verify(memcachedService).createCacheCluster("mc");
        verify(service, never()).createCacheCluster(any());
        assertTrue(body.contains(
                "<ConfigurationEndpoint><Address>localhost</Address><Port>11211</Port></ConfigurationEndpoint>"), body);
    }

    @Test
    void describeCacheClusters_reportsASingleNodeRedisClusterByItsNodeEndpoint() {
        // AWS gives a redis cache cluster no ConfigurationEndpoint (that is Memcached's node
        // discovery endpoint); terraform-provider-aws reads the port off CacheNodes instead.
        when(service.findCacheClusters("tf-redis")).thenReturn(List.of(redisCacheCluster("tf-redis")));

        MultivaluedMap<String, String> p = params();
        p.add("CacheClusterId", "tf-redis");
        p.add("ShowCacheNodeInfo", "true");
        Response response = handler.handle("DescribeCacheClusters", p, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CacheClusterId>tf-redis</CacheClusterId>"), body);
        assertTrue(body.contains("<CacheClusterStatus>available</CacheClusterStatus>"), body);
        assertTrue(body.contains("<Engine>redis</Engine>"), body);
        assertTrue(body.contains("<EngineVersion>7.1</EngineVersion>"), body);
        assertTrue(body.contains("<ARN>arn:aws:elasticache:us-east-1:000000000000:cluster:tf-redis</ARN>"), body);
        assertTrue(body.contains("<CacheNodeId>0001</CacheNodeId>"), body);
        assertTrue(body.contains("<Endpoint><Address>localhost</Address><Port>6379</Port></Endpoint>"), body);
        assertFalse(body.contains("<ConfigurationEndpoint>"), body);
        // the optional members are echoed, so a terraform re-plan has nothing to change
        assertTrue(body.contains("<SnapshotRetentionLimit>5</SnapshotRetentionLimit>"), body);
        assertTrue(body.contains("<SnapshotWindow>03:00-05:00</SnapshotWindow>"), body);
        assertTrue(body.contains("<PreferredMaintenanceWindow>tue:04:00-tue:05:00</PreferredMaintenanceWindow>"), body);
        assertTrue(body.contains("<PreferredAvailabilityZone>us-east-1b</PreferredAvailabilityZone>"), body);
        assertTrue(body.contains("<SecurityGroupId>sg-123</SecurityGroupId>"), body);
        assertTrue(body.contains("<NetworkType>ipv4</NetworkType>"), body);
        assertTrue(body.contains("<IpDiscovery>ipv4</IpDiscovery>"), body);
        assertTrue(body.contains("<CacheClusterCreateTime>"), body);
        // the Memcached store is not consulted for an id another source answered for, so its
        // not-found fault cannot turn a hit into a 404
        verify(memcachedService, never()).listCacheClusters(anyString());
    }

    @Test
    void describeCacheClusters_singleNodeCarriesTheNodeMetadataTerraformDereferences() {
        // The node shape, not the endpoint, is what broke adoption: a create succeeded and the
        // very next refresh died with "Unexpected nil pointer" showing CacheNodeCreateTime:<nil>
        // and ParameterGroupStatus:<nil>. terraform-provider-aws reads both without a nil check,
        // and AWS returns both on every available node.
        when(service.findCacheClusters("tf-redis")).thenReturn(List.of(redisCacheCluster("tf-redis")));

        MultivaluedMap<String, String> p = params();
        p.add("CacheClusterId", "tf-redis");
        p.add("ShowCacheNodeInfo", "true");
        Response response = handler.handle("DescribeCacheClusters", p, "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CacheNodeCreateTime>"), body);
        assertTrue(body.contains("<ParameterGroupStatus>in-sync</ParameterGroupStatus>"), body);
        // AWS reports the node's AZ as the cluster's; a mismatch is a permanent terraform diff
        assertTrue(body.contains("<CustomerAvailabilityZone>us-east-1b</CustomerAvailabilityZone>"), body);
        // the node was created with its cluster, so the two timestamps agree
        String nodeTime = between(body, "<CacheNodeCreateTime>", "</CacheNodeCreateTime>");
        String clusterTime = between(body, "<CacheClusterCreateTime>", "</CacheClusterCreateTime>");
        assertEquals(clusterTime, nodeTime, body);
    }

    private static String between(String body, String open, String close) {
        int from = body.indexOf(open);
        assertTrue(from >= 0, "missing " + open + " in " + body);
        return body.substring(from + open.length(), body.indexOf(close, from));
    }

    @Test
    void describeCacheClusters_stillRaisesNotFoundWhenNoSourceKnowsTheId() {
        when(service.findCacheClusters("absent")).thenReturn(List.of());
        when(memcachedService.listCacheClusters("absent")).thenThrow(
                new AwsException("CacheClusterNotFound", "Cache cluster absent not found.", 404));

        MultivaluedMap<String, String> p = params();
        p.add("CacheClusterId", "absent");

        assertEquals(404, handler.handle("DescribeCacheClusters", p, "us-east-1").getStatus());
    }

    @Test
    void deleteCacheCluster_routesToTheServiceThatHoldsTheCluster() {
        CacheCluster cluster = redisCacheCluster("tf-redis");
        when(service.findCacheClusters("tf-redis")).thenReturn(List.of(cluster));
        when(service.deleteCacheCluster("tf-redis")).thenReturn(cluster);

        MultivaluedMap<String, String> p = params();
        p.add("CacheClusterId", "tf-redis");

        assertEquals(200, handler.handle("DeleteCacheCluster", p, "us-east-1").getStatus());
        verify(service).deleteCacheCluster("tf-redis");
        verify(memcachedService, never()).deleteCacheCluster(anyString());
    }

    @Test
    void listTagsForResource_readsTagsOffACacheClusterArn() {
        CacheCluster cluster = redisCacheCluster("tf-redis");
        cluster.setTags(new java.util.LinkedHashMap<>(Map.of("Name", "cache")));
        when(service.findCacheClusters("tf-redis")).thenReturn(List.of(cluster));

        MultivaluedMap<String, String> p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:cluster:tf-redis");

        String body = (String) handler.handle("ListTagsForResource", p, "us-east-1").getEntity();
        assertTrue(body.contains("<Key>Name</Key>"), body);
        assertTrue(body.contains("<Value>cache</Value>"), body);
    }

    @Test
    void listTagsForResource_clusterArnOfAnUnknownIdIsNotFound() {
        when(service.findCacheClusters("absent")).thenReturn(List.of());
        when(memcachedService.getCacheCluster("absent")).thenThrow(
                new AwsException("CacheClusterNotFound", "Cache cluster absent not found.", 404));

        MultivaluedMap<String, String> p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:cluster:absent");

        Response response = handler.handle("ListTagsForResource", p, "us-east-1");
        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("CacheClusterNotFound"));
    }

    @Test
    void listTagsForResource_clusterArnFromAnotherRegionIsNotThisCluster() {
        // the store keys clusters by id alone, so a same-named cluster created under another
        // region is not the one this ARN names
        CacheCluster elsewhere = redisCacheCluster("tf-redis");
        elsewhere.setArn("arn:aws:elasticache:eu-west-1:000000000000:cluster:tf-redis");
        elsewhere.setTags(new java.util.LinkedHashMap<>(Map.of("Name", "west")));
        when(service.findCacheClusters("tf-redis")).thenReturn(List.of(elsewhere));

        MultivaluedMap<String, String> p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:cluster:tf-redis");

        Response response = handler.handle("ListTagsForResource", p, "us-east-1");
        assertEquals(404, response.getStatus(), (String) response.getEntity());
        assertFalse(((String) response.getEntity()).contains("west"));
    }

    @Test
    void listTagsForResource_memcachedClusterArnStillAnswersWithNoTags() {
        // Memcached clusters carry no tags but they do exist: this must not become a 404.
        when(service.findCacheClusters("mc")).thenReturn(List.of());
        when(service.listMemberCacheClusters("mc")).thenReturn(List.of());
        when(memcachedService.getCacheCluster("mc")).thenReturn(new CacheCluster(
                "mc", CacheClusterStatus.AVAILABLE, "memcached", "1.6.22",
                new Endpoint("localhost", 11211), Instant.now()));

        MultivaluedMap<String, String> p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:cluster:mc");

        Response response = handler.handle("ListTagsForResource", p, "us-east-1");
        assertEquals(200, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<TagList></TagList>"),
                (String) response.getEntity());
    }
}
