package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheMemcachedService;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheService;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheCfnProvisionerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ElastiCacheService cache = mock(ElastiCacheService.class);
    private final ElastiCacheMemcachedService memcached = mock(ElastiCacheMemcachedService.class);
    private final ElastiCacheCfnProvisioner provisioner = new ElastiCacheCfnProvisioner(cache, memcached);

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            List<String> values = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(item -> values.add(item.asText()));
            }
            return values;
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode props(String json) throws Exception {
        return (ObjectNode) mapper.readTree(json);
    }

    @Test
    void redisCacheClusterExposesRedisEndpointAttributes() throws Exception {
        CacheCluster created = new CacheCluster("app-cache", CacheClusterStatus.AVAILABLE, "redis", "7.1",
                new Endpoint("localhost", 6400), Instant.now());
        when(cache.createCacheCluster(any())).thenReturn(created);
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");

        provisioner.provision(r, props("""
                {"ClusterName":"app-cache","Engine":"redis","CacheNodeType":"cache.t3.micro","NumCacheNodes":1}
                """), ctx(null));

        assertEquals("app-cache", r.getPhysicalId());
        assertEquals("localhost", r.getAttributes().get("RedisEndpoint.Address"));
        assertEquals("6400", r.getAttributes().get("RedisEndpoint.Port"));
        ArgumentCaptor<ElastiCacheService.CreateCacheClusterRequest> request =
                ArgumentCaptor.forClass(ElastiCacheService.CreateCacheClusterRequest.class);
        verify(cache).createCacheCluster(request.capture());
        assertEquals("cache.t3.micro", request.getValue().cacheNodeType());
        assertEquals(1, request.getValue().numCacheNodes());
        verify(memcached, never()).createCacheCluster(any());
    }

    @Test
    void memcachedCacheClusterGoesToMemcachedServiceWithConfigurationEndpoint() throws Exception {
        CacheCluster created = new CacheCluster("mc", CacheClusterStatus.AVAILABLE, "memcached", "1.6",
                new Endpoint("localhost", 6410), Instant.now());
        when(memcached.createCacheCluster(any())).thenReturn(created);
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Mc");

        provisioner.provision(r, props("""
                {"ClusterName":"mc","Engine":"memcached","CacheNodeType":"cache.t3.micro","NumCacheNodes":1}
                """), ctx(null));

        assertEquals("6410", r.getAttributes().get("ConfigurationEndpoint.Port"));
        verify(cache, never()).createCacheCluster(any());
    }

    @Test
    void cacheClusterWithoutNameGetsLowercaseGeneratedId() throws Exception {
        when(cache.createCacheCluster(any())).thenAnswer(inv -> {
            ElastiCacheService.CreateCacheClusterRequest req = inv.getArgument(0);
            return new CacheCluster(req.cacheClusterId(), CacheClusterStatus.AVAILABLE, "redis", "7.1",
                    new Endpoint("localhost", 6400), Instant.now());
        });
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "MyCluster");

        provisioner.provision(r, props("{\"Engine\":\"redis\"}"), ctx(null));

        assertEquals(r.getPhysicalId().toLowerCase(), r.getPhysicalId());
        assertEquals(true, r.getPhysicalId().length() <= 50);
    }

    @Test
    void cacheClusterUpdateWithSameNameDoesNotCreateAgain() throws Exception {
        CacheCluster existing = new CacheCluster("app-cache", CacheClusterStatus.AVAILABLE, "redis", "7.1",
                new Endpoint("localhost", 6400), Instant.now());
        when(cache.findCacheClusters("app-cache")).thenReturn(List.of(existing));
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");

        provisioner.provision(r, props("{\"ClusterName\":\"app-cache\",\"Engine\":\"redis\"}"),
                ctx("app-cache"));

        verify(cache, never()).createCacheCluster(any());
        assertEquals("6400", r.getAttributes().get("RedisEndpoint.Port"));
    }

    @Test
    void explicitClusterNameWithChangedEngineIsRejectedOnUpdate() throws Exception {
        CacheCluster existing = new CacheCluster("app-cache", CacheClusterStatus.AVAILABLE, "redis", "7.1",
                new Endpoint("localhost", 6400), Instant.now());
        when(cache.findCacheClusters("app-cache")).thenReturn(List.of(existing));
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");

        assertThrows(AwsException.class, () -> provisioner.provision(r,
                props("{\"ClusterName\":\"app-cache\",\"Engine\":\"valkey\"}"), ctx("app-cache")));
    }

    @Test
    void unnamedClusterWithChangedEngineIsReplacedUnderANewId() throws Exception {
        CacheCluster existing = new CacheCluster("old-id", CacheClusterStatus.AVAILABLE, "redis", "7.1",
                new Endpoint("localhost", 6400), Instant.now());
        when(cache.findCacheClusters("old-id")).thenReturn(List.of(existing));
        when(cache.createCacheCluster(any())).thenAnswer(inv -> {
            ElastiCacheService.CreateCacheClusterRequest req = inv.getArgument(0);
            return new CacheCluster(req.cacheClusterId(), CacheClusterStatus.AVAILABLE, "valkey", "8.0",
                    new Endpoint("localhost", 6401), Instant.now());
        });
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");

        provisioner.provision(r, props("{\"Engine\":\"valkey\"}"), ctx("old-id"));

        assertNotEquals("old-id", r.getPhysicalId());
        assertEquals("6401", r.getAttributes().get("RedisEndpoint.Port"));
    }

    @Test
    void subnetGroupUsesNameAsPhysicalId() throws Exception {
        CacheSubnetGroup group =
                mock(CacheSubnetGroup.class);
        when(group.getName()).thenReturn("my-sng");
        when(cache.createCacheSubnetGroup(eq("my-sng"), eq("d"), eq(List.of("subnet-1")), any())).thenReturn(group);
        StackResource r = resource("AWS::ElastiCache::SubnetGroup", "Sng");

        provisioner.provision(r, props("""
                {"CacheSubnetGroupName":"my-sng","Description":"d","SubnetIds":["subnet-1"]}
                """), ctx(null));

        assertEquals("my-sng", r.getPhysicalId());
    }

    @Test
    void transitEncryptionDoesNotTurnOnIamAuthentication() throws Exception {
        when(cache.createCacheCluster(any())).thenReturn(new CacheCluster("tls", CacheClusterStatus.AVAILABLE,
                "redis", "7.1", new Endpoint("localhost", 6400), Instant.now()));
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Tls");

        provisioner.provision(r, props("{\"ClusterName\":\"tls\",\"Engine\":\"redis\",\"TransitEncryptionEnabled\":true}"),
                ctx(null));

        ArgumentCaptor<ElastiCacheService.CreateCacheClusterRequest> request =
                ArgumentCaptor.forClass(ElastiCacheService.CreateCacheClusterRequest.class);
        verify(cache).createCacheCluster(request.capture());
        assertEquals(AuthMode.NO_AUTH, request.getValue().authMode());
    }

    @Test
    void memcachedClusterWithUnchangedNetworkTypeIsNotReplaced() throws Exception {
        CacheCluster existing = new CacheCluster("mc", CacheClusterStatus.AVAILABLE, "memcached", "1.6",
                new Endpoint("localhost", 6410), Instant.now());
        when(cache.findCacheClusters("mc")).thenReturn(List.of());
        when(memcached.getCacheCluster("mc")).thenReturn(existing);
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Mc");

        provisioner.provision(r, props("{\"ClusterName\":\"mc\",\"Engine\":\"memcached\",\"NetworkType\":\"ipv4\"}"),
                ctx("mc"));

        verify(memcached, never()).createCacheCluster(any());
        assertEquals("mc", r.getPhysicalId());
    }

    @Test
    void removingANonDefaultNetworkTypeReplacesAnUnnamedCluster() throws Exception {
        CacheCluster existing = new CacheCluster("old-id", CacheClusterStatus.AVAILABLE, "redis", "7.1",
                new Endpoint("localhost", 6400), Instant.now());
        existing.setNetworkType("dual_stack");
        when(cache.findCacheClusters("old-id")).thenReturn(List.of(existing));
        when(cache.createCacheCluster(any())).thenAnswer(inv -> {
            ElastiCacheService.CreateCacheClusterRequest req = inv.getArgument(0);
            return new CacheCluster(req.cacheClusterId(), CacheClusterStatus.AVAILABLE, "redis", "7.1",
                    new Endpoint("localhost", 6401), Instant.now());
        });
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");

        provisioner.provision(r, props("{\"Engine\":\"redis\"}"), ctx("old-id"));

        assertNotEquals("old-id", r.getPhysicalId());
        verify(cache).createCacheCluster(any());
    }

    @Test
    void subnetGroupUpdatePassesTheDesiredTags() throws Exception {
        CacheSubnetGroup group = mock(CacheSubnetGroup.class);
        when(group.getName()).thenReturn("my-sng");
        when(cache.modifyCacheSubnetGroup(eq("my-sng"), any(), any(), any())).thenReturn(group);
        StackResource r = resource("AWS::ElastiCache::SubnetGroup", "Sng");

        provisioner.provision(r, props("{\"CacheSubnetGroupName\":\"my-sng\",\"Description\":\"d\",\"SubnetIds\":[\"s-1\"]}"),
                ctx("my-sng"));

        verify(cache).modifyCacheSubnetGroup("my-sng", "d", List.of("s-1"), java.util.Map.of());
    }

    @Test
    void clusterDeletedOutsideTheStackIsRecreatedOnUpdate() throws Exception {
        when(cache.findCacheClusters("app-cache")).thenReturn(List.of());
        when(memcached.getCacheCluster("app-cache")).thenThrow(new AwsException("CacheClusterNotFound", "gone", 404));
        when(cache.createCacheCluster(any())).thenReturn(new CacheCluster("app-cache", CacheClusterStatus.AVAILABLE,
                "redis", "7.1", new Endpoint("localhost", 6402), Instant.now()));
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");

        provisioner.provision(r, props("{\"ClusterName\":\"app-cache\",\"Engine\":\"redis\"}"), ctx("app-cache"));

        verify(cache).createCacheCluster(any());
        assertEquals("6402", r.getAttributes().get("RedisEndpoint.Port"));
    }

    @Test
    void renamedSubnetGroupReplacesAndDeletesTheOldOneAfterTheUpdate() throws Exception {
        CacheSubnetGroup created = mock(CacheSubnetGroup.class);
        when(created.getName()).thenReturn("new-sng");
        when(cache.createCacheSubnetGroup(eq("new-sng"), any(), any(), any())).thenReturn(created);
        StackResource r = resource("AWS::ElastiCache::SubnetGroup", "Sng");

        provisioner.provision(r, props("{\"CacheSubnetGroupName\":\"new-sng\",\"Description\":\"d\",\"SubnetIds\":[\"s-1\"]}"),
                ctx("old-sng"));

        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("old-sng", provisioner.updateCleanupPhysicalId(r));
        provisioner.completeUpdate(r);
        verify(cache).deleteCacheSubnetGroup("old-sng");
    }

    @Test
    void deleteToleratesAlreadyDeletedButPropagatesOtherFailures() {
        doThrow(new AwsException("CacheSubnetGroupNotFoundFault", "gone", 400))
                .when(cache).deleteCacheSubnetGroup("gone");
        provisioner.delete("AWS::ElastiCache::SubnetGroup", "gone", "us-east-1");

        doThrow(new AwsException("CacheSubnetGroupInUse", "busy", 400))
                .when(cache).deleteCacheSubnetGroup("busy");
        assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::ElastiCache::SubnetGroup", "busy", "us-east-1"));
    }
}
