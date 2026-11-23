package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreControlService;
import io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreMemoryService;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntimeEndpoint;
import io.floci.aws.services.bedrockagentcorecontrol.model.Memory;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The AgentCore provisioner in isolation, with both services mocked and a real template engine so
 * conditions and {@code AWS::NoValue} resolve the way they do in a stack.
 */
class BedrockAgentCoreCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String RUNTIME_ID = "agent-abcdefghij";
    private static final String RUNTIME_ARN = "arn:aws:bedrock-agentcore:us-east-1:000000000000:runtime/" + RUNTIME_ID;
    private static final String MEMORY_ID = "mem-abcdefghij";
    private static final String MEMORY_ARN = "arn:aws:bedrock-agentcore:us-east-1:000000000000:memory/" + MEMORY_ID;
    private static final String ENDPOINT_ARN = RUNTIME_ARN + "/runtime-endpoint/prod";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/agent";

    private final BedrockAgentCoreControlService runtimes = mock(BedrockAgentCoreControlService.class);
    private final BedrockAgentCoreMemoryService memories = mock(BedrockAgentCoreMemoryService.class);
    private final BedrockAgentCoreCfnProvisioner provisioner = new BedrockAgentCoreCfnProvisioner(runtimes, memories);
    private final ObjectMapper mapper = new ObjectMapper();

    /** A real engine (conditions and intrinsics resolve as in a stack) with no conditions defined. */
    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = CloudFormationTemplateEngine.standalone("000000000000", REGION,
                "my-stack", mapper, value -> value);
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type) {
        StackResource r = new StackResource();
        r.setLogicalId("Res");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private AgentRuntime runtime(String description, Map<String, String> environment) {
        AgentRuntime runtime = new AgentRuntime();
        runtime.setAgentRuntimeId(RUNTIME_ID);
        runtime.setAgentRuntimeName("agent");
        runtime.setUuid("uuid");
        runtime.setRoleArn(ROLE_ARN);
        runtime.setDescription(description);
        runtime.setStatus("READY");
        runtime.setLatestVersion(1);
        runtime.setCreatedAt(Instant.EPOCH);
        runtime.setLastUpdatedAt(Instant.EPOCH);
        runtime.setAgentRuntimeArtifact(json("{\"containerConfiguration\":{\"containerUri\":\"img:1\"}}"));
        runtime.setNetworkConfiguration(json("{\"networkMode\":\"PUBLIC\"}"));
        runtime.setEnvironmentVariables(new HashMap<>(environment));
        runtime.setWorkloadIdentityArn("arn:wi");
        return runtime;
    }

    private Memory memory(String description, String roleArn) {
        Memory memory = new Memory();
        memory.setMemoryId(MEMORY_ID);
        memory.setName("mem");
        memory.setStatus("ACTIVE");
        memory.setDescription(description);
        memory.setEventExpiryDuration(30);
        memory.setMemoryExecutionRoleArn(roleArn);
        memory.setCreatedAt(Instant.EPOCH);
        memory.setUpdatedAt(Instant.EPOCH);
        return memory;
    }

    private static AgentRuntimeEndpoint endpoint(String description) {
        AgentRuntimeEndpoint endpoint = new AgentRuntimeEndpoint();
        endpoint.setName("prod");
        endpoint.setUuid("ep-uuid");
        endpoint.setTargetVersion("1");
        endpoint.setLiveVersion("1");
        endpoint.setStatus("READY");
        endpoint.setDescription(description);
        endpoint.setCreatedAt(Instant.EPOCH);
        endpoint.setLastUpdatedAt(Instant.EPOCH);
        return endpoint;
    }

    private ObjectNode endpointProps(String description, Map<String, String> tags) {
        ObjectNode props = mapper.createObjectNode();
        props.put("AgentRuntimeId", RUNTIME_ID);
        props.put("Name", "prod");
        if (description != null) {
            props.put("Description", description);
        }
        if (tags != null) {
            ObjectNode tagNode = props.putObject("Tags");
            tags.forEach(tagNode::put);
        }
        return props;
    }

    /** An endpoint the stack already owns at {@code arn:ep}, carrying the given tags. */
    private StackResource existingEndpoint(AgentRuntimeEndpoint existing, Map<String, String> tags) {
        AgentRuntime runtime = runtime(null, Map.of());
        when(runtimes.findEndpointByArn(REGION, ENDPOINT_ARN))
                .thenReturn(Optional.of(new BedrockAgentCoreControlService.EndpointLocation(runtime, existing)));
        when(runtimes.endpointArn(RUNTIME_ID, existing, REGION)).thenReturn(ENDPOINT_ARN);
        when(runtimes.getEndpoint(RUNTIME_ID, "prod", REGION)).thenReturn(existing);
        when(runtimes.getAgentRuntime(RUNTIME_ID, REGION)).thenReturn(runtime);
        when(runtimes.arn(any(), eq(REGION))).thenReturn(RUNTIME_ARN);
        when(runtimes.getEndpointTagsByArn(REGION, ENDPOINT_ARN)).thenAnswer(invocation -> new HashMap<>(tags));
        StackResource r = resource("AWS::BedrockAgentCore::RuntimeEndpoint");
        r.setPhysicalId(ENDPOINT_ARN);
        return r;
    }

    private static ObjectNode runtimeProps(ObjectMapper mapper, String description, String tag) {
        ObjectNode props = mapper.createObjectNode();
        props.put("AgentRuntimeName", "agent");
        props.put("RoleArn", ROLE_ARN);
        props.putObject("AgentRuntimeArtifact").putObject("ContainerConfiguration").put("ContainerUri", "img:1");
        props.putObject("NetworkConfiguration").put("NetworkMode", "PUBLIC");
        if (description != null) {
            props.put("Description", description);
        }
        props.putObject("Tags").put("team", tag);
        return props;
    }

    @Test
    void aFnIfSelectingNoValueInsideNetworkConfigurationIsOmitted() {
        when(runtimes.createAgentRuntime(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(runtime(null, Map.of()));
        when(runtimes.arn(any(), eq(REGION))).thenReturn(RUNTIME_ARN);
        ObjectNode props = runtimeProps(mapper, null, "agents");
        // No condition is defined, so Fn::If takes its else branch, as an undefined condition does.
        props.set("NetworkConfiguration", json("""
                {"NetworkMode": "PUBLIC",
                 "NetworkModeConfig": {"Fn::If": ["UseVpc", {"Subnets": ["subnet-1"]}, {"Ref": "AWS::NoValue"}]}}
                """));

        provisioner.provision(resource("AWS::BedrockAgentCore::Runtime"), props, ctx(null));

        ArgumentCaptor<JsonNode> network = ArgumentCaptor.forClass(JsonNode.class);
        verify(runtimes).createAgentRuntime(eq("agent"), any(), network.capture(), eq(ROLE_ARN), isNull(), any(),
                isNull(), isNull(), isNull(), eq(REGION));
        assertEquals(json("{\"networkMode\":\"PUBLIC\"}"), network.getValue(),
                "the NoValue member must be omitted, not resolved to an empty string");
    }

    @Test
    void aFailedTaggingAfterCreateDeletesTheRuntimeBeforePropagating() {
        when(runtimes.createAgentRuntime(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(runtime(null, Map.of()));
        when(runtimes.arn(any(), eq(REGION))).thenReturn(RUNTIME_ARN);
        doThrow(new AwsException("ThrottlingException", "slow down", 429))
                .when(runtimes).tagByArn(eq(REGION), eq(RUNTIME_ARN), anyMap());
        StackResource r = resource("AWS::BedrockAgentCore::Runtime");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, runtimeProps(mapper, null, "agents"), ctx(null)));

        assertEquals("ThrottlingException", failure.getErrorCode());
        verify(runtimes).deleteAgentRuntime(RUNTIME_ID, null, REGION);
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR),
                "the undo succeeded, so the engine must not try to delete the runtime again");
    }

    @Test
    void aFailedRuntimeLookupAfterCreateDeletesTheEndpoint() {
        AgentRuntimeEndpoint endpoint = endpoint(null);
        when(runtimes.createEndpoint(RUNTIME_ID, "prod", null, null, Map.of(), null, REGION)).thenReturn(endpoint);
        when(runtimes.endpointArn(RUNTIME_ID, endpoint, REGION)).thenReturn("arn:ep");
        when(runtimes.getAgentRuntime(RUNTIME_ID, REGION))
                .thenThrow(new AwsException("InternalServerException", "boom", 500));
        ObjectNode props = mapper.createObjectNode();
        props.put("AgentRuntimeId", RUNTIME_ID);
        props.put("Name", "prod");

        assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::BedrockAgentCore::RuntimeEndpoint"), props, ctx(null)));

        verify(runtimes).deleteEndpoint(RUNTIME_ID, "prod", null, REGION);
    }

    @Test
    void rollbackUpdateRestoresAnInPlaceRuntimeChangeAsANewVersion() {
        AgentRuntime prior = runtime("one", Map.of("K", "v"));
        when(runtimes.getAgentRuntime(RUNTIME_ID, REGION)).thenReturn(prior);
        when(runtimes.arn(any(), eq(REGION))).thenReturn(RUNTIME_ARN);
        when(runtimes.getTagsByArn(REGION, RUNTIME_ARN)).thenReturn(new HashMap<>(Map.of("team", "agents")));
        when(runtimes.updateAgentRuntime(eq(RUNTIME_ID), any(), any(), any(), any(), any(), any(), any(), eq(REGION)))
                .thenReturn(prior);
        StackResource r = resource("AWS::BedrockAgentCore::Runtime");
        r.setPhysicalId(RUNTIME_ID);
        // The update drops the environment and the description's value changes; the tag changes too.
        provisioner.provision(r, runtimeProps(mapper, "two", "ml"), ctx(RUNTIME_ID));
        verify(runtimes).updateAgentRuntime(eq(RUNTIME_ID), any(), any(), eq(ROLE_ARN), eq("two"), eq(Map.of()),
                isNull(), isNull(), eq(REGION));
        assertTrue(r.getAttributes().containsKey(BedrockAgentCoreCfnProvisioner.IN_PLACE_PRIOR_ATTR));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(runtimes).updateAgentRuntime(eq(RUNTIME_ID), eq(prior.getAgentRuntimeArtifact()),
                eq(prior.getNetworkConfiguration()), eq(ROLE_ARN), eq("one"), eq(Map.of("K", "v")), isNull(), isNull(),
                eq(REGION));
        verify(runtimes).tagByArn(REGION, RUNTIME_ARN, Map.of("team", "agents"));
        assertNull(r.getAttributes().get(BedrockAgentCoreCfnProvisioner.IN_PLACE_PRIOR_ATTR),
                "a spent snapshot is removed so a later rollback does not restore stale state");
    }

    @Test
    void anUnchangedRuntimeIsNeitherUpdatedNorSnapshotted() {
        AgentRuntime prior = runtime("one", Map.of());
        when(runtimes.getAgentRuntime(RUNTIME_ID, REGION)).thenReturn(prior);
        when(runtimes.arn(any(), eq(REGION))).thenReturn(RUNTIME_ARN);
        when(runtimes.getTagsByArn(REGION, RUNTIME_ARN)).thenReturn(new HashMap<>(Map.of("team", "agents")));
        StackResource r = resource("AWS::BedrockAgentCore::Runtime");
        r.setPhysicalId(RUNTIME_ID);

        provisioner.provision(r, runtimeProps(mapper, "one", "agents"), ctx(RUNTIME_ID));

        verify(runtimes, never()).updateAgentRuntime(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertFalse(r.getAttributes().containsKey(BedrockAgentCoreCfnProvisioner.IN_PLACE_PRIOR_ATTR));
        assertEquals("1", r.getAttributes().get("AgentRuntimeVersion"));
    }

    @Test
    void aFailedTagReconcileAfterAnInPlaceMemoryUpdateRestoresTheMemoryBeforePropagating() {
        Memory prior = memory("one", ROLE_ARN);
        when(memories.getByArn(REGION, MEMORY_ARN)).thenReturn(prior);
        when(memories.get(MEMORY_ID, REGION)).thenReturn(prior);
        when(memories.arn(any(), eq(REGION))).thenReturn(MEMORY_ARN);
        when(memories.getTagsByArn(REGION, MEMORY_ARN)).thenReturn(new HashMap<>(Map.of("team", "agents")));
        when(memories.update(eq(MEMORY_ID), any(), anyInt(), any(), anyBoolean(), eq(REGION))).thenReturn(prior);
        doThrow(new AwsException("ThrottlingException", "slow down", 429))
                .when(memories).untagByArn(eq(REGION), eq(MEMORY_ARN), eq(List.of("team")));
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "mem");
        props.put("EventExpiryDuration", 45);
        StackResource r = resource("AWS::BedrockAgentCore::Memory");
        r.setPhysicalId(MEMORY_ARN);

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx(MEMORY_ARN)));

        // The template dropped the description and the role, so the update cleared them; the
        // restore puts both back with the prior expiry, before the engine discards this attempt.
        verify(memories).update(MEMORY_ID, null, 45, null, true, REGION);
        verify(memories).update(MEMORY_ID, "one", 30, ROLE_ARN, true, REGION);
        verify(memories).tagByArn(REGION, MEMORY_ARN, Map.of("team", "agents"));
        assertFalse(r.getAttributes().containsKey(BedrockAgentCoreCfnProvisioner.IN_PLACE_PRIOR_ATTR));
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void anOmittedEndpointDescriptionIsClearedThroughTheReplacingUpdate() {
        AgentRuntime runtime = runtime(null, Map.of());
        AgentRuntimeEndpoint existing = endpoint("old");
        when(runtimes.findEndpointByArn(REGION, "arn:ep"))
                .thenReturn(Optional.of(new BedrockAgentCoreControlService.EndpointLocation(runtime, existing)));
        when(runtimes.updateEndpoint(eq(RUNTIME_ID), eq("prod"), any(), any(), anyBoolean(), eq(REGION)))
                .thenReturn(existing);
        when(runtimes.endpointArn(RUNTIME_ID, existing, REGION)).thenReturn("arn:ep");
        when(runtimes.arn(any(), eq(REGION))).thenReturn(RUNTIME_ARN);
        ObjectNode props = mapper.createObjectNode();
        props.put("AgentRuntimeId", RUNTIME_ID);
        props.put("Name", "prod");
        StackResource r = resource("AWS::BedrockAgentCore::RuntimeEndpoint");
        r.setPhysicalId("arn:ep");

        provisioner.provision(r, props, ctx("arn:ep"));

        verify(runtimes).updateEndpoint(RUNTIME_ID, "prod", null, null, true, REGION);
        assertEquals("arn:ep", r.getPhysicalId());
    }

    @Test
    void endpointTagsAreStoredWithTheCreatedEndpoint() {
        AgentRuntimeEndpoint created = endpoint(null);
        when(runtimes.createEndpoint(any(), any(), any(), any(), anyMap(), any(), any())).thenReturn(created);
        when(runtimes.endpointArn(RUNTIME_ID, created, REGION)).thenReturn(ENDPOINT_ARN);
        when(runtimes.getAgentRuntime(RUNTIME_ID, REGION)).thenReturn(runtime(null, Map.of()));
        when(runtimes.arn(any(), eq(REGION))).thenReturn(RUNTIME_ARN);
        StackResource r = resource("AWS::BedrockAgentCore::RuntimeEndpoint");

        provisioner.provision(r, endpointProps(null, Map.of("team", "agents")), ctx(null));

        verify(runtimes).createEndpoint(RUNTIME_ID, "prod", null, null, Map.of("team", "agents"), null, REGION);
        assertEquals(ENDPOINT_ARN, r.getPhysicalId());
    }

    @Test
    void aTagOnlyEndpointChangeIsReconciledWithoutUpdatingTheEndpoint() {
        StackResource r = existingEndpoint(endpoint("same"), Map.of("team", "agents", "stage", "beta"));

        provisioner.provision(r, endpointProps("same", Map.of("team", "ml")), ctx(ENDPOINT_ARN));

        verify(runtimes, never()).updateEndpoint(any(), any(), any(), any(), anyBoolean(), any());
        verify(runtimes).untagEndpointByArn(REGION, ENDPOINT_ARN, List.of("stage"));
        verify(runtimes).tagEndpointByArn(REGION, ENDPOINT_ARN, Map.of("team", "ml"));
        assertTrue(r.getAttributes().containsKey(BedrockAgentCoreCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void omittingEndpointTagsRemovesTheStoredOnes() {
        StackResource r = existingEndpoint(endpoint("same"), Map.of("team", "agents"));

        provisioner.provision(r, endpointProps("same", null), ctx(ENDPOINT_ARN));

        verify(runtimes).untagEndpointByArn(REGION, ENDPOINT_ARN, List.of("team"));
        verify(runtimes, never()).tagEndpointByArn(any(), any(), anyMap());
    }

    @Test
    void anEndpointWithUnchangedTagsIsNeitherRetaggedNorSnapshotted() {
        StackResource r = existingEndpoint(endpoint("same"), Map.of("team", "agents"));

        provisioner.provision(r, endpointProps("same", Map.of("team", "agents")), ctx(ENDPOINT_ARN));

        verify(runtimes, never()).tagEndpointByArn(any(), any(), anyMap());
        verify(runtimes, never()).untagEndpointByArn(any(), any(), any());
        assertFalse(r.getAttributes().containsKey(BedrockAgentCoreCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void rollbackUpdateRestoresTheEndpointsPriorTags() {
        AgentRuntimeEndpoint existing = endpoint("same");
        Map<String, String> stored = new HashMap<>(Map.of("team", "agents"));
        StackResource r = existingEndpoint(existing, stored);
        provisioner.provision(r, endpointProps("same", Map.of("owner", "ml")), ctx(ENDPOINT_ARN));
        stored.clear();
        stored.put("owner", "ml");

        assertTrue(provisioner.rollbackUpdate(r));

        verify(runtimes).untagEndpointByArn(REGION, ENDPOINT_ARN, List.of("owner"));
        verify(runtimes).tagEndpointByArn(REGION, ENDPOINT_ARN, Map.of("team", "agents"));
        verify(runtimes, never()).updateEndpoint(any(), any(), any(), any(), anyBoolean(), any());
        assertNull(r.getAttributes().get(BedrockAgentCoreCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void aFailedEndpointTagWriteRestoresTheKeysRemovedBeforeIt() {
        AgentRuntimeEndpoint existing = endpoint("old");
        Map<String, String> stored = new HashMap<>(Map.of("team", "agents"));
        StackResource r = existingEndpoint(existing, stored);
        when(runtimes.updateEndpoint(eq(RUNTIME_ID), eq("prod"), any(), any(), anyBoolean(), eq(REGION)))
                .thenReturn(existing);
        doThrow(new AwsException("ThrottlingException", "slow down", 429))
                .when(runtimes).tagEndpointByArn(REGION, ENDPOINT_ARN, Map.of("owner", "ml"));

        assertThrows(AwsException.class,
                () -> provisioner.provision(r, endpointProps("new", Map.of("owner", "ml")), ctx(ENDPOINT_ARN)));

        verify(runtimes).untagEndpointByArn(REGION, ENDPOINT_ARN, List.of("team"));
        verify(runtimes).updateEndpoint(RUNTIME_ID, "prod", null, "new", true, REGION);
        verify(runtimes).updateEndpoint(RUNTIME_ID, "prod", "1", "old", true, REGION);
        verify(runtimes).tagEndpointByArn(REGION, ENDPOINT_ARN, Map.of("team", "agents"));
        assertFalse(r.getAttributes().containsKey(BedrockAgentCoreCfnProvisioner.IN_PLACE_PRIOR_ATTR));
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void aTagRestoreThatFailedIsRetriedFromTheSnapshotCarriedOntoThePreviousResource() {
        Map<String, String> stored = new HashMap<>(Map.of("team", "agents"));
        StackResource attempt = existingEndpoint(endpoint("same"), stored);
        StackResource committed = resource("AWS::BedrockAgentCore::RuntimeEndpoint");
        committed.setPhysicalId(ENDPOINT_ARN);
        doThrow(new AwsException("ThrottlingException", "slow down", 429))
                .when(runtimes).tagEndpointByArn(eq(REGION), eq(ENDPOINT_ARN), anyMap());

        assertThrows(AwsException.class,
                () -> provisioner.provision(attempt, endpointProps("same", Map.of("owner", "ml")), ctx(ENDPOINT_ARN)));
        assertTrue(attempt.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        stored.clear();

        provisioner.mergeFailedUpdateResourceTracking(committed, attempt);
        doNothing().when(runtimes).tagEndpointByArn(eq(REGION), eq(ENDPOINT_ARN), anyMap());
        assertTrue(provisioner.rollbackUpdate(committed));

        verify(runtimes, times(2)).tagEndpointByArn(REGION, ENDPOINT_ARN, Map.of("team", "agents"));
        assertNull(committed.getAttributes().get(BedrockAgentCoreCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void anEndpointUndoThatCompletedLeavesThePreviousResourceAlone() {
        StackResource attempt = existingEndpoint(endpoint("same"), Map.of("team", "agents"));
        StackResource committed = resource("AWS::BedrockAgentCore::RuntimeEndpoint");
        provisioner.provision(attempt, endpointProps("same", Map.of("team", "ml")), ctx(ENDPOINT_ARN));

        provisioner.mergeFailedUpdateResourceTracking(committed, attempt);

        assertTrue(committed.getAttributes().isEmpty());
    }
}
