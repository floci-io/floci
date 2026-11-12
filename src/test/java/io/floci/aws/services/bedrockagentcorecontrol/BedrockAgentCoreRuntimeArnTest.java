package io.floci.aws.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntimeEndpoint;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.BedrockAgentCoreCfnProvisioner;
import io.floci.aws.services.cloudformation.provisioners.ProvisionContext;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BedrockAgentCoreRuntimeArnTest {

    private static final String REGION = "us-east-1";
    private static final String PREFIX = "arn:aws:bedrock-agentcore:us-east-1:000000000000:runtime/";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver resolver = new RegionResolver(REGION, "000000000000");
    private final BedrockAgentCoreControlService service = new BedrockAgentCoreControlService(
            new InMemoryStorage<>(), resolver, null, new MutableClock());

    private ObjectNode artifact() {
        return mapper.createObjectNode().set("containerConfiguration",
                mapper.createObjectNode().put("containerUri", "123456789012.dkr.ecr/x:latest"));
    }

    private ObjectNode network() {
        return mapper.createObjectNode().put("networkMode", "PUBLIC");
    }

    private AgentRuntime create(String name) {
        return service.createAgentRuntime(name, artifact(), network(),
                "arn:aws:iam::000000000000:role/agent", null, null, null, null, null, REGION);
    }

    @Test
    void runtimeArnUsesTheRuntimeIdAndStaysStableAcrossVersions() {
        AgentRuntime runtime = create("versioned");
        String expected = PREFIX + runtime.getAgentRuntimeId();
        assertEquals(expected, service.arn(runtime, REGION));
        AgentRuntime updated = service.updateAgentRuntime(runtime.getAgentRuntimeId(), artifact(), network(),
                "arn:aws:iam::000000000000:role/agent", null, null, null, null, REGION);
        assertEquals(expected, service.arn(updated, REGION));
    }

    @Test
    void runtimeArnResolvesItsIdAndChecksDeletedRuntimes() {
        AgentRuntime runtime = create("lookup");
        String arn = PREFIX + runtime.getAgentRuntimeId();
        assertEquals(runtime.getAgentRuntimeId(), service.getAgentRuntimeByArn(REGION, arn).getAgentRuntimeId());
        assertTrue(service.runtimeArnExists(REGION, arn));
        service.deleteAgentRuntime(runtime.getAgentRuntimeId(), null, REGION);
        assertFalse(service.runtimeArnExists(REGION, arn));
    }

    @Test
    void missingRuntimeArnDoesNotPassTheExistenceCheck() {
        assertFalse(service.runtimeArnExists(REGION, PREFIX + "missing-AbCdEfGhIj"));
    }

    @Test
    void endpointArnIncludesItsParentAndNameIncludingDefault() {
        AgentRuntime runtime = create("endpoints");
        AgentRuntimeEndpoint endpoint = service.createEndpoint(runtime.getAgentRuntimeId(), "prod",
                null, null, null, REGION);
        assertEquals(PREFIX + runtime.getAgentRuntimeId() + "/runtime-endpoint/prod",
                service.endpointArn(runtime.getAgentRuntimeId(), endpoint, REGION));
        AgentRuntimeEndpoint defaultEndpoint = service.getEndpoint(runtime.getAgentRuntimeId(), "DEFAULT", REGION);
        assertEquals(PREFIX + runtime.getAgentRuntimeId() + "/runtime-endpoint/DEFAULT",
                service.endpointArn(runtime.getAgentRuntimeId(), defaultEndpoint, REGION));
    }

    @Test
    void endpointLookupUsesTheParentWhenNamesRepeat() {
        AgentRuntime first = create("first");
        AgentRuntime second = create("second");
        service.createEndpoint(first.getAgentRuntimeId(), "prod", null, null, null, REGION);
        service.createEndpoint(second.getAgentRuntimeId(), "prod", null, null, null, REGION);
        String arn = PREFIX + second.getAgentRuntimeId() + "/runtime-endpoint/prod";
        BedrockAgentCoreControlService.EndpointLocation located = service.findEndpointByArn(REGION, arn).orElseThrow();
        assertEquals(second.getAgentRuntimeId(), located.runtime().getAgentRuntimeId());
        assertEquals("prod", located.endpoint().getName());
        assertTrue(service.findEndpointByArn("us-west-2", arn).isEmpty());
        service.deleteEndpoint(second.getAgentRuntimeId(), "prod", null, REGION);
        assertTrue(service.findEndpointByArn(REGION, arn).isEmpty());
    }

    @Test
    void tagHandlerDispatchesRuntimeAndNestedEndpointArnsSeparately() {
        AgentRuntime runtime = create("tagged");
        service.createEndpoint(runtime.getAgentRuntimeId(), "prod", null, null, null, REGION);
        String runtimeArn = PREFIX + runtime.getAgentRuntimeId();
        String endpointArn = runtimeArn + "/runtime-endpoint/prod";
        BedrockAgentCoreTagHandler handler = new BedrockAgentCoreTagHandler(service,
                mock(BedrockAgentCoreGatewayService.class), mock(BedrockAgentCoreMemoryService.class),
                mock(BedrockAgentCoreHarnessRegistry.class), resolver);
        handler.tagResource(REGION, runtimeArn, Map.of("owner", "runtime"));
        handler.tagResource(REGION, endpointArn, Map.of("owner", "endpoint"));
        assertEquals(Map.of("owner", "runtime"), handler.listTags(REGION, runtimeArn));
        assertEquals(Map.of("owner", "endpoint"), handler.listTags(REGION, endpointArn));
        handler.untagResource(REGION, endpointArn, List.of("owner"));
        assertTrue(handler.listTags(REGION, endpointArn).isEmpty());
        assertEquals(Map.of("owner", "runtime"), handler.listTags(REGION, runtimeArn));
    }

    @Test
    void unknownLegacyRuntimeDoesNotPassExistenceValidation() {
        assertFalse(service.runtimeArnExists(REGION,
                resolver.buildArn("bedrock-agentcore", REGION, "agent/missing-uuid:1")));
    }

    @Test
    void legacyRuntimeExistenceFollowsItsStoredUuidAndDeletion() {
        AgentRuntime runtime = create("legacyRuntime");
        String arn = resolver.buildArn("bedrock-agentcore", REGION, "agent/" + runtime.getUuid() + ":1");
        assertTrue(service.runtimeArnExists(REGION, arn));
        service.deleteAgentRuntime(runtime.getAgentRuntimeId(), null, REGION);
        assertFalse(service.runtimeArnExists(REGION, arn));
    }

    @Test
    void legacyRuntimeAndEndpointTagsRemainSeparate() {
        AgentRuntime runtime = create("legacyTags");
        AgentRuntimeEndpoint endpoint = service.createEndpoint(runtime.getAgentRuntimeId(), "prod",
                null, null, null, REGION);
        String runtimeArn = resolver.buildArn("bedrock-agentcore", REGION, "agent/" + runtime.getUuid() + ":1");
        String endpointArn = resolver.buildArn("bedrock-agentcore", REGION, "agentEndpoint/" + endpoint.getUuid());
        BedrockAgentCoreTagHandler handler = new BedrockAgentCoreTagHandler(service,
                mock(BedrockAgentCoreGatewayService.class), mock(BedrockAgentCoreMemoryService.class),
                mock(BedrockAgentCoreHarnessRegistry.class), resolver);
        handler.tagResource(REGION, runtimeArn, Map.of("scope", "runtime"));
        handler.tagResource(REGION, endpointArn, Map.of("scope", "endpoint"));
        assertEquals(Map.of("scope", "runtime"), handler.listTags(REGION, runtimeArn));
        assertEquals(Map.of("scope", "endpoint"), handler.listTags(REGION, endpointArn));
        assertEquals(runtime.getAgentRuntimeId(), service.getAgentRuntimeByArn(REGION, runtimeArn).getAgentRuntimeId());
    }

    @Test
    void legacyEndpointLookupResolvesItsUuidAndDisappearsAfterDeletion() {
        AgentRuntime runtime = create("legacyLookup");
        AgentRuntimeEndpoint endpoint = service.createEndpoint(runtime.getAgentRuntimeId(), "prod",
                null, null, null, REGION);
        String arn = resolver.buildArn("bedrock-agentcore", REGION, "agentEndpoint/" + endpoint.getUuid());
        assertEquals(runtime.getAgentRuntimeId(), service.findEndpointByArn(REGION, arn).orElseThrow()
                .runtime().getAgentRuntimeId());
        assertTrue(service.findEndpointByArn("us-west-2", arn).isEmpty());
        service.deleteEndpoint(runtime.getAgentRuntimeId(), "prod", null, REGION);
        assertTrue(service.findEndpointByArn(REGION, arn).isEmpty());
    }

    @Test
    void cloudFormationUpdatesAnEndpointWithAPersistedLegacyPhysicalId() {
        AgentRuntime runtime = create("legacyUpdate");
        AgentRuntimeEndpoint endpoint = service.createEndpoint(runtime.getAgentRuntimeId(), "prod",
                null, "old", null, REGION);
        String arn = resolver.buildArn("bedrock-agentcore", REGION, "agentEndpoint/" + endpoint.getUuid());
        StackResource resource = new StackResource();
        resource.setLogicalId("Endpoint");
        resource.setResourceType("AWS::BedrockAgentCore::RuntimeEndpoint");
        resource.setPhysicalId(arn);
        resource.setAttributes(new HashMap<>());
        ObjectNode props = mapper.createObjectNode().put("AgentRuntimeId", runtime.getAgentRuntimeId())
                .put("Name", "prod").put("Description", "new");
        CloudFormationTemplateEngine engine = CloudFormationTemplateEngine.standalone("000000000000", REGION,
                "legacy-stack", mapper, value -> value);
        new BedrockAgentCoreCfnProvisioner(service, mock(BedrockAgentCoreMemoryService.class)).provision(
                resource, props, new ProvisionContext(engine, REGION, "000000000000", "legacy-stack", arn));
        assertEquals(service.endpointArn(runtime.getAgentRuntimeId(), endpoint, REGION), resource.getPhysicalId());
        assertEquals("new", service.getEndpoint(runtime.getAgentRuntimeId(), "prod", REGION).getDescription());
        assertEquals(2, service.getAgentRuntime(runtime.getAgentRuntimeId(), REGION).getEndpoints().size());
    }

    @Test
    void cloudFormationDeletesAnEndpointWithAPersistedLegacyPhysicalId() {
        AgentRuntime runtime = create("legacyDelete");
        AgentRuntimeEndpoint endpoint = service.createEndpoint(runtime.getAgentRuntimeId(), "prod",
                null, null, null, REGION);
        String arn = resolver.buildArn("bedrock-agentcore", REGION, "agentEndpoint/" + endpoint.getUuid());
        new BedrockAgentCoreCfnProvisioner(service, mock(BedrockAgentCoreMemoryService.class))
                .delete("AWS::BedrockAgentCore::RuntimeEndpoint", arn, REGION);
        assertThrows(AwsException.class, () -> service.getEndpoint(runtime.getAgentRuntimeId(), "prod", REGION));
    }
}
