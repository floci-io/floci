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
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUserGroup;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void missingCacheNodeTypeIsRejected() throws Exception {
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");
        assertThrows(AwsException.class, () -> provisioner.provision(r,
                props("{\"Engine\":\"redis\",\"NumCacheNodes\":1}"), ctx(null)));
        verify(cache, never()).createCacheCluster(any());
        verify(memcached, never()).createCacheCluster(any());
    }

    @Test
    void missingNumCacheNodesIsRejected() throws Exception {
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");
        assertThrows(AwsException.class, () -> provisioner.provision(r,
                props("{\"Engine\":\"redis\",\"CacheNodeType\":\"cache.t3.micro\"}"), ctx(null)));
        verify(cache, never()).createCacheCluster(any());
        verify(memcached, never()).createCacheCluster(any());
    }

    @Test
    void missingSubnetGroupDescriptionIsRejected() throws Exception {
        StackResource r = resource("AWS::ElastiCache::SubnetGroup", "Sng");
        assertThrows(AwsException.class, () -> provisioner.provision(r,
                props("{\"CacheSubnetGroupName\":\"my-sng\",\"SubnetIds\":[\"s-1\"]}"), ctx(null)));
        verify(cache, never()).createCacheSubnetGroup(any(), any(), any(), any());
    }

    @Test
    void oversizedClusterNameIsRejected() throws Exception {
        String longName = "c".repeat(51);
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");
        assertThrows(AwsException.class, () -> provisioner.provision(r,
                props("{\"ClusterName\":\"" + longName + "\",\"Engine\":\"redis\",\"CacheNodeType\":\"cache.t3.micro\",\"NumCacheNodes\":1}"),
                ctx(null)));
        verify(cache, never()).createCacheCluster(any());
    }

    @Test
    void oversizedSubnetGroupNameIsRejected() throws Exception {
        String longName = "s".repeat(256);
        StackResource r = resource("AWS::ElastiCache::SubnetGroup", "Sng");
        assertThrows(AwsException.class, () -> provisioner.provision(r,
                props("{\"CacheSubnetGroupName\":\"" + longName + "\",\"Description\":\"d\",\"SubnetIds\":[\"s-1\"]}"),
                ctx(null)));
        verify(cache, never()).createCacheSubnetGroup(any(), any(), any(), any());
    }

    @Test
    void cacheClusterWithoutNameGetsLowercaseGeneratedId() throws Exception {
        when(cache.createCacheCluster(any())).thenAnswer(inv -> {
            ElastiCacheService.CreateCacheClusterRequest req = inv.getArgument(0);
            return new CacheCluster(req.cacheClusterId(), CacheClusterStatus.AVAILABLE, "redis", "7.1",
                    new Endpoint("localhost", 6400), Instant.now());
        });
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "MyCluster");

        provisioner.provision(r, props("{\"Engine\":\"redis\",\"CacheNodeType\":\"cache.t3.micro\",\"NumCacheNodes\":1}"), ctx(null));

        assertEquals(r.getPhysicalId().toLowerCase(), r.getPhysicalId());
        assertEquals(true, r.getPhysicalId().length() <= 50);
    }

    @Test
    void cacheClusterUpdateWithSameNameDoesNotCreateAgain() throws Exception {
        CacheCluster existing = new CacheCluster("app-cache", CacheClusterStatus.AVAILABLE, "redis", "7.1",
                new Endpoint("localhost", 6400), Instant.now());
        when(cache.findCacheClusters("app-cache")).thenReturn(List.of(existing));
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");

        provisioner.provision(r, props("{\"ClusterName\":\"app-cache\",\"Engine\":\"redis\",\"CacheNodeType\":\"cache.t3.micro\",\"NumCacheNodes\":1}"),
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
                props("{\"ClusterName\":\"app-cache\",\"Engine\":\"valkey\",\"CacheNodeType\":\"cache.t3.micro\",\"NumCacheNodes\":1}"), ctx("app-cache")));
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

        provisioner.provision(r, props("{\"Engine\":\"valkey\",\"CacheNodeType\":\"cache.t3.micro\",\"NumCacheNodes\":1}"), ctx("old-id"));

        assertNotEquals("old-id", r.getPhysicalId());
        assertEquals("6401", r.getAttributes().get("RedisEndpoint.Port"));
    }

    @Test
    void engineSwitchCleansUpDisplacedEndpointAttributes() throws Exception {
        CacheCluster existingRedis = new CacheCluster("cluster-1", CacheClusterStatus.AVAILABLE, "redis", "7.1",
                new Endpoint("localhost", 6400), Instant.now());
        when(cache.findCacheClusters("cluster-1")).thenReturn(List.of(existingRedis));

        CacheCluster replacementMemcached = new CacheCluster("cluster-2", CacheClusterStatus.AVAILABLE,
                "memcached", "1.6", new Endpoint("localhost", 6410), Instant.now());
        when(memcached.createCacheCluster(any())).thenReturn(replacementMemcached);

        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");
        r.getAttributes().put("RedisEndpoint.Address", "localhost");
        r.getAttributes().put("RedisEndpoint.Port", "6400");

        provisioner.provision(r, props("""
                {"Engine":"memcached","CacheNodeType":"cache.t3.micro","NumCacheNodes":1}
                """), ctx("cluster-1"));

        assertEquals("6410", r.getAttributes().get("ConfigurationEndpoint.Port"));
        assertEquals("localhost", r.getAttributes().get("ConfigurationEndpoint.Address"));
        assertNull(r.getAttributes().get("RedisEndpoint.Address"));
        assertNull(r.getAttributes().get("RedisEndpoint.Port"));
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
    void userWithPasswordAuthenticationModeSetsArnAndStatus() throws Exception {
        ElastiCacheUser user = new ElastiCacheUser("app", "app", AuthMode.PASSWORD, List.of("pw-0123456789abcd"),
                "on ~* +@all", "redis", "active", Instant.now());
        user.setArn("arn:aws:elasticache:us-east-1:000000000000:user:app");
        when(cache.createUser(eq("app"), eq("app"), eq(AuthMode.PASSWORD), eq(List.of("pw-0123456789abcd")),
                eq("on ~* +@all"), eq("redis"))).thenReturn(user);
        StackResource r = resource("AWS::ElastiCache::User", "AppUser");

        provisioner.provision(r, props("""
                {"UserId":"app","UserName":"app","Engine":"redis","AccessString":"on ~* +@all",
                 "AuthenticationMode":{"Type":"password","Passwords":["pw-0123456789abcd"]}}
                """), ctx(null));

        assertEquals("app", r.getPhysicalId());
        assertEquals("arn:aws:elasticache:us-east-1:000000000000:user:app", r.getAttributes().get("Arn"));
        assertEquals("active", r.getAttributes().get("Status"));
    }

    @Test
    void userWithNoPasswordRequiredUsesNoAuth() throws Exception {
        ElastiCacheUser user = new ElastiCacheUser("d", "default", AuthMode.NO_AUTH, List.of(), "off -@all",
                "redis", "active", Instant.now());
        when(cache.createUser(eq("d"), eq("default"), eq(AuthMode.NO_AUTH), any(), eq("off -@all"), eq("redis")))
                .thenReturn(user);
        StackResource r = resource("AWS::ElastiCache::User", "Default");

        provisioner.provision(r, props("""
                {"UserId":"d","UserName":"default","Engine":"redis","AccessString":"off -@all",
                 "NoPasswordRequired":true}
                """), ctx(null));

        assertEquals("d", r.getPhysicalId());
    }

    @Test
    void userRequiresUserIdUserNameAndEngine() throws Exception {
        StackResource r = resource("AWS::ElastiCache::User", "Bad");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("{\"Engine\":\"redis\"}"), ctx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(r, props("{\"UserId\":\"u\",\"UserName\":\"u\"}"), ctx(null)));
        verify(cache, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void userRejectsPasswordModeWithoutPasswords() {
        StackResource r = resource("AWS::ElastiCache::User", "Bad");
        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"UserId":"u","UserName":"u","Engine":"redis",
                 "AuthenticationMode":{"Type":"password"}}
                """), ctx(null)));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        verify(cache, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void userRejectsPasswordsWithNoPasswordRequiredMode() {
        StackResource r = resource("AWS::ElastiCache::User", "Bad");
        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"UserId":"u","UserName":"u","Engine":"redis",
                 "AuthenticationMode":{"Type":"no-password-required","Passwords":["pw"]}}
                """), ctx(null)));
        assertEquals("InvalidParameterCombination", ex.getErrorCode());
        verify(cache, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void userRejectsPasswordsWithIamAuthenticationMode() {
        StackResource r = resource("AWS::ElastiCache::User", "Bad");
        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"UserId":"u","UserName":"u","Engine":"redis",
                 "AuthenticationMode":{"Type":"iam","Passwords":["pw"]}}
                """), ctx(null)));
        assertEquals("InvalidParameterCombination", ex.getErrorCode());
        verify(cache, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void userRejectsConflictingNoPasswordRequiredAndPasswords() {
        StackResource r = resource("AWS::ElastiCache::User", "Bad");
        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"UserId":"u","UserName":"u","Engine":"redis","NoPasswordRequired":true,"Passwords":["pw"]}
                """), ctx(null)));
        assertEquals("InvalidParameterCombination", ex.getErrorCode());
        verify(cache, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void userRejectsConflictingNoPasswordRequiredAndAuthenticationModeType() {
        StackResource r = resource("AWS::ElastiCache::User", "Bad");
        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"UserId":"u","UserName":"u","Engine":"redis","NoPasswordRequired":true,
                 "AuthenticationMode":{"Type":"password","Passwords":["pw"]}}
                """), ctx(null)));
        assertEquals("InvalidParameterCombination", ex.getErrorCode());
        verify(cache, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void userRejectsInvalidAuthenticationModeType() {
        StackResource r = resource("AWS::ElastiCache::User", "Bad");
        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"UserId":"u","UserName":"u","Engine":"redis",
                 "AuthenticationMode":{"Type":"bogus"}}
                """), ctx(null)));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        verify(cache, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void userGroupRequiresUserIdsProperty() {
        StackResource r = resource("AWS::ElastiCache::UserGroup", "Bad");
        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"UserGroupId":"team","Engine":"valkey"}
                """), ctx(null)));
        assertEquals("ValidationException", ex.getErrorCode());
        verify(cache, never()).createUserGroup(any(), any(), any(), any());
    }

    @Test
    void userGroupSetsArnAndStatus() throws Exception {
        ElastiCacheUserGroup group = new ElastiCacheUserGroup();
        group.setUserGroupId("team");
        group.setArn("arn:aws:elasticache:us-east-1:000000000000:usergroup:team");
        group.setStatus("active");
        when(cache.createUserGroup("team", "redis", List.of("d", "app"), "us-east-1")).thenReturn(group);
        StackResource r = resource("AWS::ElastiCache::UserGroup", "Group");

        provisioner.provision(r, props("""
                {"UserGroupId":"team","Engine":"redis","UserIds":["d","app"]}
                """), ctx(null));

        assertEquals("team", r.getPhysicalId());
        assertEquals("arn:aws:elasticache:us-east-1:000000000000:usergroup:team", r.getAttributes().get("Arn"));
    }

    @Test
    void userGroupUpdateAppliesMembershipDelta() throws Exception {
        ElastiCacheUserGroup existing = new ElastiCacheUserGroup();
        existing.setUserGroupId("team");
        existing.setEngine("redis");
        existing.setUserIds(new LinkedHashSet<>(List.of("d", "old")));
        existing.setArn("arn");
        existing.setStatus("active");
        when(cache.getUserGroup("team")).thenReturn(existing);
        when(cache.setUserGroupMembers(eq("team"), eq(List.of("d", "new")), eq("redis"))).thenReturn(existing);
        StackResource r = resource("AWS::ElastiCache::UserGroup", "Group");

        provisioner.provision(r, props("""
                {"UserGroupId":"team","Engine":"redis","UserIds":["d","new"]}
                """), ctx("team"));

        verify(cache, never()).createUserGroup(any(), any(), any(), any());
        verify(cache).setUserGroupMembers(eq("team"), eq(List.of("d", "new")), eq("redis"));
    }

    @Test
    void userNameChangeIsRejectedBecauseItIsCreateOnly() throws Exception {
        ElastiCacheUser existing = new ElastiCacheUser("app", "old-name", AuthMode.NO_AUTH, List.of(), "on ~* +@all",
                "redis", "active", Instant.now());
        when(cache.getUser("app")).thenReturn(existing);
        StackResource r = resource("AWS::ElastiCache::User", "AppUser");

        assertThrows(AwsException.class, () -> provisioner.provision(r,
                props("{\"UserId\":\"app\",\"UserName\":\"new-name\",\"Engine\":\"redis\"}"), ctx("app")));
        verify(cache, never()).modifyUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void userGroupEngineChangeIsAppliedInPlace() throws Exception {
        ElastiCacheUserGroup existing = new ElastiCacheUserGroup();
        existing.setUserGroupId("team");
        existing.setEngine("redis");
        existing.setArn("arn");
        existing.setStatus("active");
        when(cache.getUserGroup("team")).thenReturn(existing);
        when(cache.setUserGroupMembers(eq("team"), eq(List.of()), eq("valkey"))).thenReturn(existing);
        StackResource r = resource("AWS::ElastiCache::UserGroup", "Group");

        provisioner.provision(r, props("{\"UserGroupId\":\"team\",\"Engine\":\"valkey\",\"UserIds\":[]}"), ctx("team"));

        verify(cache).setUserGroupMembers("team", List.of(), "valkey");
        verify(cache, never()).createUserGroup(any(), any(), any(), any());
    }

    @Test
    void mixedCaseUserGroupIdIsReusedOnUpdateThroughItsLowercaseForm() throws Exception {
        ElastiCacheUserGroup existing = new ElastiCacheUserGroup();
        existing.setUserGroupId("myusers");
        existing.setEngine("redis");
        existing.setArn("arn");
        existing.setStatus("active");
        when(cache.getUserGroup("myusers")).thenReturn(existing);
        when(cache.setUserGroupMembers("myusers", List.of(), "redis")).thenReturn(existing);
        StackResource r = resource("AWS::ElastiCache::UserGroup", "Group");

        provisioner.provision(r, props("{\"UserGroupId\":\"MyUsers\",\"Engine\":\"redis\",\"UserIds\":[]}"),
                ctx("myusers"));

        verify(cache, never()).createUserGroup(any(), any(), any(), any());
        verify(cache).setUserGroupMembers("myusers", List.of(), "redis");
        assertEquals("myusers", r.getPhysicalId());
    }

    @Test
    void transitEncryptionDoesNotTurnOnIamAuthentication() throws Exception {
        when(cache.createCacheCluster(any())).thenReturn(new CacheCluster("tls", CacheClusterStatus.AVAILABLE,
                "redis", "7.1", new Endpoint("localhost", 6400), Instant.now()));
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Tls");

        provisioner.provision(r, props("{\"ClusterName\":\"tls\",\"Engine\":\"redis\",\"CacheNodeType\":\"cache.t3.micro\",\"NumCacheNodes\":1,\"TransitEncryptionEnabled\":true}"),
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

        provisioner.provision(r, props("{\"ClusterName\":\"mc\",\"Engine\":\"memcached\",\"CacheNodeType\":\"cache.t3.micro\",\"NumCacheNodes\":1,\"NetworkType\":\"ipv4\"}"),
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

        provisioner.provision(r, props("{\"Engine\":\"redis\",\"CacheNodeType\":\"cache.t3.micro\",\"NumCacheNodes\":1}"), ctx("old-id"));

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

        verify(cache).modifyCacheSubnetGroup("my-sng", "d", List.of("s-1"), Map.of());
    }

    @Test
    void rollbackHookRestoresPriorSubnetGroupConfigurationAfterLaterResourceFails() throws Exception {
        CacheSubnetGroup existing = mock(CacheSubnetGroup.class);
        when(existing.getName()).thenReturn("my-sng");
        when(existing.getDescription()).thenReturn("old-desc");
        when(existing.getSubnetAvailabilityZones()).thenReturn(Map.of("subnet-1", "us-east-1a"));
        when(existing.getTags()).thenReturn(Map.of("env", "old"));
        when(cache.describeCacheSubnetGroups("my-sng")).thenReturn(List.of(existing));

        CacheSubnetGroup modified = mock(CacheSubnetGroup.class);
        when(modified.getName()).thenReturn("my-sng");
        when(cache.modifyCacheSubnetGroup(eq("my-sng"), eq("new-desc"), eq(List.of("subnet-2")), any()))
                .thenReturn(modified);

        StackResource r = resource("AWS::ElastiCache::SubnetGroup", "Sng");
        provisioner.provision(r, props("""
                {"CacheSubnetGroupName":"my-sng","Description":"new-desc","SubnetIds":["subnet-2"]}
                """), ctx("my-sng"));

        assertTrue(provisioner.rollbackUpdate(r));
        verify(cache).modifyCacheSubnetGroup("my-sng", "old-desc", List.of("subnet-1"), Map.of("env", "old"));
    }

    @Test
    void committedSubnetGroupUpdateClearsRollbackSnapshot() {
        StackResource r = resource("AWS::ElastiCache::SubnetGroup", "Sng");
        r.getAttributes().put("__FlociCacheSubnetGroupUpdateSnapshot", "snapshot-data");
        r.setStatus("UPDATE_COMPLETE");

        UpdateCleanupResult result = provisioner.completeUpdate(r);
        assertFalse(result.applicable());
        assertNull(r.getAttributes().get("__FlociCacheSubnetGroupUpdateSnapshot"));
    }

    @Test
    void inPlaceUserUpdateCanBeRolledBackFromSnapshot() throws Exception {
        ElastiCacheUser existing = new ElastiCacheUser("app", "app", AuthMode.PASSWORD, List.of("pw-old"),
                "on ~* +@all", "redis", "active", Instant.now());
        when(cache.getUser("app")).thenReturn(existing);
        ElastiCacheUser modified = new ElastiCacheUser("app", "app", AuthMode.PASSWORD, List.of("pw-new"),
                "off -@all", "redis", "active", Instant.now());
        when(cache.modifyUser(eq("app"), eq(AuthMode.PASSWORD), eq(List.of("pw-new")), eq("off -@all"), any(), eq("redis")))
                .thenReturn(modified);

        StackResource r = resource("AWS::ElastiCache::User", "AppUser");
        provisioner.provision(r, props("""
                {"UserId":"app","UserName":"app","Engine":"redis","AccessString":"off -@all",
                 "AuthenticationMode":{"Type":"password","Passwords":["pw-new"]}}
                """), ctx("app"));

        assertTrue(provisioner.rollbackUpdate(r));
        verify(cache).modifyUser("app", AuthMode.PASSWORD, List.of("pw-old"), "on ~* +@all", null, "redis");
    }

    @Test
    void inPlaceUserGroupUpdateCanBeRolledBackFromSnapshot() throws Exception {
        ElastiCacheUserGroup existing = new ElastiCacheUserGroup();
        existing.setUserGroupId("team");
        existing.setEngine("redis");
        existing.setUserIds(new LinkedHashSet<>(List.of("u1", "u2")));
        existing.setArn("arn");
        existing.setStatus("active");
        when(cache.getUserGroup("team")).thenReturn(existing);

        ElastiCacheUserGroup modified = new ElastiCacheUserGroup();
        modified.setUserGroupId("team");
        modified.setEngine("redis");
        modified.setUserIds(new LinkedHashSet<>(List.of("u1", "u3")));
        modified.setArn("arn");
        modified.setStatus("active");
        when(cache.setUserGroupMembers(eq("team"), eq(List.of("u1", "u3")), eq("redis"))).thenReturn(modified);

        StackResource r = resource("AWS::ElastiCache::UserGroup", "Group");
        provisioner.provision(r, props("""
                {"UserGroupId":"team","Engine":"redis","UserIds":["u1","u3"]}
                """), ctx("team"));

        assertTrue(provisioner.rollbackUpdate(r));
        verify(cache).setUserGroupMembers("team", List.of("u1", "u2"), "redis");
    }

    @Test
    void committedUserAndUserGroupUpdateClearsRollbackSnapshots() {
        StackResource rUser = resource("AWS::ElastiCache::User", "User");
        rUser.getAttributes().put("__FlociUserUpdateSnapshot", "snapshot-data");
        rUser.setStatus("UPDATE_COMPLETE");
        provisioner.completeUpdate(rUser);
        assertNull(rUser.getAttributes().get("__FlociUserUpdateSnapshot"));

        StackResource rGroup = resource("AWS::ElastiCache::UserGroup", "Group");
        rGroup.getAttributes().put("__FlociUserGroupUpdateSnapshot", "snapshot-data");
        rGroup.setStatus("UPDATE_COMPLETE");
        provisioner.completeUpdate(rGroup);
        assertNull(rGroup.getAttributes().get("__FlociUserGroupUpdateSnapshot"));
    }

    @Test
    void clearUpdateClearsUserAndUserGroupSnapshots() {
        StackResource rUser = resource("AWS::ElastiCache::User", "User");
        rUser.getAttributes().put("__FlociUserUpdateSnapshot", "snapshot-data");
        provisioner.clearUpdate(rUser);
        assertNull(rUser.getAttributes().get("__FlociUserUpdateSnapshot"));

        StackResource rGroup = resource("AWS::ElastiCache::UserGroup", "Group");
        rGroup.getAttributes().put("__FlociUserGroupUpdateSnapshot", "snapshot-data");
        provisioner.clearUpdate(rGroup);
        assertNull(rGroup.getAttributes().get("__FlociUserGroupUpdateSnapshot"));
    }

    @Test
    void clusterDeletedOutsideTheStackIsRecreatedOnUpdate() throws Exception {
        when(cache.findCacheClusters("app-cache")).thenReturn(List.of());
        when(memcached.getCacheCluster("app-cache")).thenThrow(new AwsException("CacheClusterNotFound", "gone", 404));
        when(cache.createCacheCluster(any())).thenReturn(new CacheCluster("app-cache", CacheClusterStatus.AVAILABLE,
                "redis", "7.1", new Endpoint("localhost", 6402), Instant.now()));
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Cluster");

        provisioner.provision(r, props("{\"ClusterName\":\"app-cache\",\"Engine\":\"redis\",\"CacheNodeType\":\"cache.t3.micro\",\"NumCacheNodes\":1}"), ctx("app-cache"));

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
    void renamedUserGroupReplacesAndDeletesTheOldOne() throws Exception {
        ElastiCacheUserGroup created = new ElastiCacheUserGroup();
        created.setUserGroupId("new-team");
        created.setArn("arn");
        created.setStatus("active");
        when(cache.createUserGroup("new-team", "redis", List.of(), "us-east-1")).thenReturn(created);
        StackResource r = resource("AWS::ElastiCache::UserGroup", "Group");

        provisioner.provision(r, props("{\"UserGroupId\":\"new-team\",\"Engine\":\"redis\",\"UserIds\":[]}"), ctx("old-team"));

        assertTrue(provisioner.hasReplacementUpdate(r));
        provisioner.completeUpdate(r);
        verify(cache).deleteUserGroup("old-team");
    }

    @Test
    void deleteToleratesAlreadyDeletedButPropagatesOtherFailures() {
        doThrow(new AwsException("UserNotFoundFault", "gone", 404))
                .when(cache).deleteUser("gone");
        provisioner.delete("AWS::ElastiCache::User", "gone", "us-east-1");

        doThrow(new AwsException("InvalidUserGroupState", "busy", 400))
                .when(cache).deleteUserGroup("busy");
        assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::ElastiCache::UserGroup", "busy", "us-east-1"));
    }

    @Test
    void memcachedClusterRejectsNonexistentSubnetGroup() throws Exception {
        when(memcached.createCacheCluster(any())).thenThrow(new AwsException("CacheSubnetGroupNotFoundFault", "not found", 400));
        StackResource r = resource("AWS::ElastiCache::CacheCluster", "Mc");
        assertThrows(AwsException.class, () -> provisioner.provision(r,
                props("""
                {"ClusterName":"mc","Engine":"memcached","CacheNodeType":"cache.t3.micro","NumCacheNodes":1,"CacheSubnetGroupName":"missing-sng"}
                """), ctx(null)));
    }
}
