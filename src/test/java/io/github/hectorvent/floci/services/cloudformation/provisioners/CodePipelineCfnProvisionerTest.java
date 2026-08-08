package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.codepipeline.CodePipelineService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The CodePipeline CFN provisioner in isolation, with the service mocked. */
class CodePipelineCfnProvisionerTest {

    private final CodePipelineService service = mock(CodePipelineService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodePipelineCfnProvisioner provisioner =
            new CodePipelineCfnProvisioner(service, mock(RegionResolver.class), mapper);

    private ProvisionContext ctx() {
        var engine = mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "cfn-stack");
    }

    private StackResource resource(String type) {
        StackResource r = new StackResource();
        r.setLogicalId("MyResource");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode pipelineProps(String name) {
        ObjectNode props = mapper.createObjectNode();
        if (name != null) {
            props.put("Name", name);
        }
        props.put("RoleArn", "arn:aws:iam::000000000000:role/cp");
        props.putObject("ArtifactStore").put("Type", "S3").put("Location", "bucket");
        ObjectNode stage = props.putArray("Stages").addObject();
        stage.put("Name", "Gate");
        ObjectNode action = stage.putArray("Actions").addObject();
        action.put("Name", "HumanGate");
        action.putObject("ActionTypeId")
                .put("Category", "Approval").put("Owner", "AWS").put("Provider", "Manual").put("Version", "1");
        action.putObject("Configuration")
                .put("ProjectName", "installer-build").put("Owner", "awslabs");
        return props;
    }

    @Test
    void pipelinePropertiesAreLoweredAndVersionExposedForGetAtt() {
        when(service.handle(eq("CreatePipeline"), any(), eq("us-east-1"), eq("000000000000")))
                .thenReturn(mapper.createObjectNode().set("pipeline",
                        mapper.createObjectNode().put("version", 1)));
        StackResource r = resource("AWS::CodePipeline::Pipeline");

        provisioner.provision(r, pipelineProps("the-pipeline"), ctx());

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).handle(eq("CreatePipeline"), request.capture(), eq("us-east-1"), eq("000000000000"));
        JsonNode declaration = request.getValue().path("pipeline");
        assertEquals("the-pipeline", declaration.path("name").asText());
        assertEquals("arn:aws:iam::000000000000:role/cp", declaration.path("roleArn").asText());
        assertEquals("S3", declaration.path("artifactStore").path("type").asText());
        JsonNode action = declaration.path("stages").get(0).path("actions").get(0);
        assertEquals("Approval", action.path("actionTypeId").path("category").asText());
        // Configuration keys are provider-defined strings the executors match verbatim —
        // they must survive the PascalCase→camelCase transform untouched.
        assertEquals("installer-build", action.path("configuration").path("ProjectName").asText());
        assertEquals("awslabs", action.path("configuration").path("Owner").asText());

        assertEquals("the-pipeline", r.getPhysicalId());
        assertEquals("1", r.getAttributes().get("Version"));
    }

    @Test
    void pipelineWithoutNameGetsGeneratedPhysicalName() {
        when(service.handle(eq("CreatePipeline"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("pipeline",
                        mapper.createObjectNode().put("version", 1)));
        StackResource r = resource("AWS::CodePipeline::Pipeline");

        provisioner.provision(r, pipelineProps(null), ctx());

        assertTrue(r.getPhysicalId().startsWith("cfn-stack-MyResource-"));
    }

    @Test
    void existingPhysicalIdRoutesToUpdatePipeline() {
        when(service.handle(eq("UpdatePipeline"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("pipeline",
                        mapper.createObjectNode().put("version", 2)));
        StackResource r = resource("AWS::CodePipeline::Pipeline");
        r.setPhysicalId("the-pipeline");

        provisioner.provision(r, pipelineProps("the-pipeline"), ctx());

        verify(service).handle(eq("UpdatePipeline"), any(), eq("us-east-1"), eq("000000000000"));
        assertEquals("2", r.getAttributes().get("Version"));
    }

    @Test
    void disabledInboundTransitionsAreApplied() {
        when(service.handle(eq("CreatePipeline"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("pipeline",
                        mapper.createObjectNode().put("version", 1)));
        ObjectNode props = pipelineProps("gated");
        props.putArray("DisableInboundStageTransitions").addObject()
                .put("StageName", "Gate").put("Reason", "hold");
        StackResource r = resource("AWS::CodePipeline::Pipeline");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).handle(eq("DisableStageTransition"), request.capture(), any(), any());
        assertEquals("Gate", request.getValue().path("stageName").asText());
        assertEquals("hold", request.getValue().path("reason").asText());
    }

    @Test
    void webhookExposesUrlForGetAtt() {
        when(service.handle(eq("PutWebhook"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("webhook", mapper.createObjectNode()
                        .put("url", "http://localhost:4566/codepipeline/webhooks/hook")));
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "hook");
        props.put("TargetPipeline", "the-pipeline");
        props.put("TargetAction", "Source");
        StackResource r = resource("AWS::CodePipeline::Webhook");

        provisioner.provision(r, props, ctx());

        assertEquals("hook", r.getPhysicalId());
        assertEquals("http://localhost:4566/codepipeline/webhooks/hook", r.getAttributes().get("Url"));
    }

    @Test
    void customActionTypeBuildsCompositePhysicalId() {
        when(service.handle(eq("CreateCustomActionType"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode());
        ObjectNode props = mapper.createObjectNode();
        props.put("Category", "Build");
        props.put("Provider", "MyBuilder");
        props.put("Version", "2");
        StackResource r = resource("AWS::CodePipeline::CustomActionType");

        provisioner.provision(r, props, ctx());

        assertEquals("Build|Custom|MyBuilder|2", r.getPhysicalId());
    }
}
