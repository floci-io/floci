package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheServerlessCfnProvisionerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ElastiCacheServerlessService serverless = mock(ElastiCacheServerlessService.class);
    private final ElastiCacheServerlessCfnProvisioner serverlessProvisioner =
            new ElastiCacheServerlessCfnProvisioner(serverless);

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
    void serverlessCacheExposesEndpointAndArnAttributes() throws Exception {
        ServerlessCache created = new ServerlessCache();
        created.setServerlessCacheName("sl");
        created.setArn("arn:aws:elasticache:us-east-1:000000000000:serverlesscache:sl");
        created.setStatus("available");
        created.setFullEngineVersion("8.0");
        created.setEndpoint(new Endpoint("localhost", 6420));
        created.setReaderEndpoint(new Endpoint("localhost", 6421));
        when(serverless.createServerlessCache(any())).thenReturn(created);
        StackResource r = resource("AWS::ElastiCache::ServerlessCache", "Sl");

        serverlessProvisioner.provision(r, props("""
                {"ServerlessCacheName":"sl","Engine":"valkey","SubnetIds":["subnet-1"],"UserGroupId":"team",
                 "CacheUsageLimits":{"DataStorage":{"Maximum":10,"Unit":"GB"},"ECPUPerSecond":{"Maximum":5000}}}
                """), ctx(null));

        assertEquals("sl", r.getPhysicalId());
        assertEquals("arn:aws:elasticache:us-east-1:000000000000:serverlesscache:sl", r.getAttributes().get("ARN"));
        assertEquals("6420", r.getAttributes().get("Endpoint.Port"));
        assertEquals("localhost", r.getAttributes().get("ReaderEndpoint.Address"));
        ArgumentCaptor<ElastiCacheServerlessService.CreateServerlessCacheRequest> request =
                ArgumentCaptor.forClass(ElastiCacheServerlessService.CreateServerlessCacheRequest.class);
        verify(serverless).createServerlessCache(request.capture());
        assertEquals("team", request.getValue().userGroupId());
        assertEquals(10, request.getValue().cacheUsageLimits().dataStorage().maximum());
        assertEquals(5000, request.getValue().cacheUsageLimits().ecpuPerSecond().maximum());
    }

    @Test
    void mixedCaseServerlessNameIsReusedOnUpdateThroughItsLowercaseForm() throws Exception {
        ServerlessCache existing = new ServerlessCache();
        existing.setServerlessCacheName("mycache");
        existing.setArn("arn");
        existing.setStatus("available");
        when(serverless.getServerlessCache("mycache")).thenReturn(existing);
        when(serverless.modifyServerlessCache(any())).thenReturn(existing);
        when(serverless.replaceTags(eq("mycache"), any())).thenReturn(existing);
        StackResource r = resource("AWS::ElastiCache::ServerlessCache", "Sl");

        serverlessProvisioner.provision(r, props("{\"ServerlessCacheName\":\"MyCache\",\"Engine\":\"valkey\"}"),
                ctx("mycache"));

        verify(serverless, never()).createServerlessCache(any());
        assertEquals("mycache", r.getPhysicalId());
    }

    @Test
    void serverlessSubnetChangeIsRejectedInsteadOfIgnored() throws Exception {
        ServerlessCache existing = new ServerlessCache();
        existing.setServerlessCacheName("sl");
        existing.setSubnetIds(List.of("subnet-1"));
        when(serverless.getServerlessCache("sl")).thenReturn(existing);
        StackResource r = resource("AWS::ElastiCache::ServerlessCache", "Sl");

        assertThrows(AwsException.class, () -> serverlessProvisioner.provision(r,
                props("{\"ServerlessCacheName\":\"sl\",\"Engine\":\"valkey\",\"SubnetIds\":[\"subnet-2\"]}"), ctx("sl")));
        verify(serverless, never()).modifyServerlessCache(any());
    }

    @Test
    void serverlessKmsKeyChangeIsRejectedInsteadOfIgnored() throws Exception {
        ServerlessCache existing = new ServerlessCache();
        existing.setServerlessCacheName("sl");
        existing.setKmsKeyId("key-1");
        when(serverless.getServerlessCache("sl")).thenReturn(existing);
        StackResource r = resource("AWS::ElastiCache::ServerlessCache", "Sl");

        assertThrows(AwsException.class, () -> serverlessProvisioner.provision(r,
                props("{\"ServerlessCacheName\":\"sl\",\"Engine\":\"valkey\"}"), ctx("sl")));
        verify(serverless, never()).modifyServerlessCache(any());
    }

    @Test
    void serverlessUpdateClearsSecurityGroupsAndDescriptionRemovedFromTheTemplate() throws Exception {
        ServerlessCache existing = new ServerlessCache();
        existing.setServerlessCacheName("sl");
        existing.setSecurityGroupIds(List.of("sg-1"));
        existing.setDescription("old");
        existing.setArn("arn");
        existing.setStatus("available");
        when(serverless.getServerlessCache("sl")).thenReturn(existing);
        when(serverless.modifyServerlessCache(any())).thenReturn(existing);
        when(serverless.replaceTags(eq("sl"), any())).thenReturn(existing);
        StackResource r = resource("AWS::ElastiCache::ServerlessCache", "Sl");

        serverlessProvisioner.provision(r, props("{\"ServerlessCacheName\":\"sl\",\"Engine\":\"valkey\"}"), ctx("sl"));

        ArgumentCaptor<ElastiCacheServerlessService.ModifyServerlessCacheRequest> request =
                ArgumentCaptor.forClass(ElastiCacheServerlessService.ModifyServerlessCacheRequest.class);
        verify(serverless).modifyServerlessCache(request.capture());
        assertEquals(List.of(), request.getValue().securityGroupIds());
        assertEquals("", request.getValue().description());
    }

    @Test
    void renamedServerlessCacheReplacesAndDeletesTheOldOne() throws Exception {
        ServerlessCache created = new ServerlessCache();
        created.setServerlessCacheName("new-sl");
        created.setArn("arn");
        created.setStatus("available");
        when(serverless.createServerlessCache(any())).thenReturn(created);
        StackResource r = resource("AWS::ElastiCache::ServerlessCache", "Sl");

        serverlessProvisioner.provision(r, props("{\"ServerlessCacheName\":\"new-sl\",\"Engine\":\"valkey\"}"), ctx("old-sl"));

        assertTrue(serverlessProvisioner.hasReplacementUpdate(r));
        serverlessProvisioner.completeUpdate(r);
        verify(serverless).deleteServerlessCache("old-sl");
    }

    @Test
    void serverlessUpdateModifiesInPlaceDropsUserGroupAndReplacesTags() throws Exception {
        ServerlessCache existing = new ServerlessCache();
        existing.setServerlessCacheName("sl");
        existing.setUserGroupId("team");
        existing.setArn("arn");
        existing.setStatus("available");
        when(serverless.getServerlessCache("sl")).thenReturn(existing);
        when(serverless.modifyServerlessCache(any())).thenReturn(existing);
        when(serverless.replaceTags(eq("sl"), any())).thenReturn(existing);
        StackResource r = resource("AWS::ElastiCache::ServerlessCache", "Sl");

        serverlessProvisioner.provision(r, props("""
                {"ServerlessCacheName":"sl","Engine":"valkey","Description":"updated",
                 "CacheUsageLimits":{"ECPUPerSecond":{"Maximum":4000}},
                 "Tags":[{"Key":"team","Value":"x"}]}
                """), ctx("sl"));

        ArgumentCaptor<ElastiCacheServerlessService.ModifyServerlessCacheRequest> request =
                ArgumentCaptor.forClass(ElastiCacheServerlessService.ModifyServerlessCacheRequest.class);
        verify(serverless).modifyServerlessCache(request.capture());
        assertEquals(Boolean.TRUE, request.getValue().removeUserGroup());
        assertEquals("updated", request.getValue().description());
        assertEquals(4000, request.getValue().cacheUsageLimits().ecpuPerSecond().maximum());
        verify(serverless).replaceTags(eq("sl"), any());
        verify(serverless, never()).createServerlessCache(any());
    }
}
