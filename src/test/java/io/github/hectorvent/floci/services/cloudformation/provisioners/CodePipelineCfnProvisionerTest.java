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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void customActionTypeIdentityChangeCreatesNewAndDeletesOld() {
        when(service.handle(eq("CreateCustomActionType"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode());
        ObjectNode props = mapper.createObjectNode();
        props.put("Category", "Build");
        props.put("Provider", "MyBuilder");
        props.put("Version", "3");
        StackResource r = resource("AWS::CodePipeline::CustomActionType");
        // Previously provisioned under Version "2" — an update to Version "3" changes identity.
        r.setPhysicalId("Build|Custom|MyBuilder|2");

        provisioner.provision(r, props, ctx());

        verify(service).handle(eq("CreateCustomActionType"), any(), eq("us-east-1"), eq("000000000000"));
        ArgumentCaptor<JsonNode> deleteRequest = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).handle(eq("DeleteCustomActionType"), deleteRequest.capture(), any(), any());
        assertEquals("Build", deleteRequest.getValue().path("category").asText());
        assertEquals("MyBuilder", deleteRequest.getValue().path("provider").asText());
        assertEquals("2", deleteRequest.getValue().path("version").asText());
        assertEquals("Build|Custom|MyBuilder|3", r.getPhysicalId());
    }

    @Test
    void removedTransitionRestrictionIsReEnabledOnUpdate() {
        when(service.handle(eq("UpdatePipeline"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("pipeline",
                        mapper.createObjectNode().put("version", 2)));
        // Previously deployed by this stack with "Gate" disabled; this update's template no
        // longer lists it, so it must be re-enabled (tracked via the stack-owned attribute,
        // never an externally-disabled stage this resource never claimed).
        ObjectNode props = pipelineProps("gated");
        StackResource r = resource("AWS::CodePipeline::Pipeline");
        r.setPhysicalId("gated");
        r.getAttributes().put("__FlociCodePipelineDisabledStages", "Gate");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).handle(eq("EnableStageTransition"), request.capture(), eq("us-east-1"), eq("000000000000"));
        assertEquals("Gate", request.getValue().path("stageName").asText());
    }

    @Test
    void registerWithThirdPartyReadsResolvedValueNotRawIntrinsic() {
        when(service.handle(eq("PutWebhook"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("webhook",
                        mapper.createObjectNode().put("url", "http://localhost/hook")));
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "hook");
        props.put("TargetPipeline", "the-pipeline");
        props.put("TargetAction", "Source");
        // Simulate an unresolved intrinsic (e.g. Fn::If) that only resolves to true —
        // raw props must never be consulted directly for this boolean.
        props.putObject("RegisterWithThirdParty").put("Fn::If", "SomeCondition");
        StackResource r = resource("AWS::CodePipeline::Webhook");
        var engine = mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(inv -> {
            ObjectNode resolved = ((ObjectNode) inv.getArgument(0)).deepCopy();
            resolved.put("RegisterWithThirdParty", true);
            return resolved;
        });
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "000000000000", "cfn-stack");

        provisioner.provision(r, props, ctx);

        verify(service).handle(eq("RegisterWebhookWithThirdParty"), any(), any(), any());
    }

    @Test
    void externallyDisabledStageIsNeverReEnabled() {
        when(service.handle(eq("UpdatePipeline"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("pipeline",
                        mapper.createObjectNode().put("version", 2)));
        // This stack has run the tracking code since it was created and never disabled "Gate"
        // itself (empty, non-null attribute) -- some outside actor (a manual gate, a human)
        // disabled it, and an unrelated update must not touch it.
        ObjectNode props = pipelineProps("gated");
        StackResource r = resource("AWS::CodePipeline::Pipeline");
        r.setPhysicalId("gated");
        r.getAttributes().put("__FlociCodePipelineDisabledStages", "");

        provisioner.provision(r, props, ctx());

        verify(service, never()).handle(eq("EnableStageTransition"), any(), any(), any());
    }

    @Test
    void legacyResourcePredatingTrackingAttributeReconcilesAllDeclaredStages() {
        when(service.handle(eq("UpdatePipeline"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("pipeline",
                        mapper.createObjectNode().put("version", 2)));
        // A resource stored by a provisioner version predating DISABLED_STAGES_ATTR has no
        // tracking attribute at all -- the very first post-upgrade update must still reconcile
        // (fall back to every declared stage) instead of leaving a removed entry stuck disabled.
        ObjectNode props = pipelineProps("gated");
        StackResource r = resource("AWS::CodePipeline::Pipeline");
        r.setPhysicalId("gated");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).handle(eq("EnableStageTransition"), request.capture(), eq("us-east-1"), eq("000000000000"));
        assertEquals("Gate", request.getValue().path("stageName").asText());
    }

    @Test
    void failedTransitionReconciliationDeletesTheNewlyCreatedReplacementPipeline() {
        when(service.handle(eq("CreatePipeline"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("pipeline",
                        mapper.createObjectNode().put("version", 1)));
        when(service.handle(eq("DisableStageTransition"), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        StackResource r = resource("AWS::CodePipeline::Pipeline");
        r.setPhysicalId("old-name");
        ObjectNode props = pipelineProps("new-name");
        props.putArray("DisableInboundStageTransitions").addObject()
                .put("StageName", "Gate").put("Reason", "hold");

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> provisioner.provision(r, props, ctx()));
        assertEquals("boom", thrown.getMessage());

        ArgumentCaptor<JsonNode> deleteRequest = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).handle(eq("DeletePipeline"), deleteRequest.capture(), any(), any());
        assertEquals("new-name", deleteRequest.getValue().path("name").asText());
        // The old pipeline must survive an aborted replacement -- it's still the resource
        // CloudFormation will keep tracking after reverting this failed update.
        verify(service, never()).handle(eq("DeletePipeline"),
                argThat(n -> "old-name".equals(n.path("name").asText())), any(), any());
    }

    @Test
    void pipelineRenameReplacesRatherThanUpdatingWrongResource() {
        when(service.handle(eq("CreatePipeline"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("pipeline",
                        mapper.createObjectNode().put("version", 1)));
        StackResource r = resource("AWS::CodePipeline::Pipeline");
        r.setPhysicalId("old-name");

        provisioner.provision(r, pipelineProps("new-name"), ctx());

        verify(service, never()).handle(eq("UpdatePipeline"), any(), any(), any());
        ArgumentCaptor<JsonNode> createRequest = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).handle(eq("CreatePipeline"), createRequest.capture(), any(), any());
        assertEquals("new-name", createRequest.getValue().path("pipeline").path("name").asText());
        ArgumentCaptor<JsonNode> deleteRequest = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).handle(eq("DeletePipeline"), deleteRequest.capture(), any(), any());
        assertEquals("old-name", deleteRequest.getValue().path("name").asText());
        assertEquals("new-name", r.getPhysicalId());
    }

    @Test
    void webhookRenameDeletesTheOldNameInsteadOfOrphaningIt() {
        when(service.handle(eq("PutWebhook"), any(), any(), any()))
                .thenReturn(mapper.createObjectNode().set("webhook",
                        mapper.createObjectNode().put("url", "http://localhost/new-hook")));
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "new-hook");
        props.put("TargetPipeline", "the-pipeline");
        props.put("TargetAction", "Source");
        StackResource r = resource("AWS::CodePipeline::Webhook");
        r.setPhysicalId("old-hook");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<JsonNode> deleteRequest = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).handle(eq("DeleteWebhook"), deleteRequest.capture(), any(), any());
        assertEquals("old-hook", deleteRequest.getValue().path("name").asText());
        assertEquals("new-hook", r.getPhysicalId());
    }
}
