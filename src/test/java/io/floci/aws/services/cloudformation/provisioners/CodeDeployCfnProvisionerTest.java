package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.codedeploy.CodeDeployService;
import io.floci.aws.services.codedeploy.model.Application;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::CodeDeploy::Application} in isolation, with only {@link CodeDeployService} mocked. */
class CodeDeployCfnProvisionerTest {

    private static final String ARN = "arn:aws:codedeploy:us-east-1:000000000000:application:my-app";

    private final CodeDeployService codeDeploy = mock(CodeDeployService.class);
    private final CodeDeployCfnProvisioner provisioner = new CodeDeployCfnProvisioner(codeDeploy);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        return ctx(priorPhysicalId, null);
    }

    private ProvisionContext ctx(String priorPhysicalId, JsonNode priorProperties) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId,
                event -> { }, priorProperties);
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("App");
        r.setResourceType("AWS::CodeDeploy::Application");
        r.setAttributes(new HashMap<>());
        return r;
    }

    /** A provisioned application whose tag keys {@code keys} the stack set. */
    private static StackResource owning(String... keys) {
        StackResource r = resource();
        r.getAttributes().put(CodeDeployCfnProvisioner.TAG_KEYS_ATTR,
                "[" + String.join(",", Arrays.stream(keys).map(k -> "\"" + k + "\"").toList()) + "]");
        return r;
    }

    private static Application app(String name) {
        Application a = new Application();
        a.setApplicationName(name);
        return a;
    }

    @Test
    void createsTheApplicationWithItsComputePlatformAndTags() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq("my-app"), eq("Lambda"), any()))
                .thenReturn(app("my-app"));
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app").put("ComputePlatform", "Lambda");
        props.putArray("Tags").add(mapper.createObjectNode().put("Key", "team").put("Value", "core"));
        StackResource r = resource();

        provisioner.provision(r, props, ctx(null));

        verify(codeDeploy).createApplication("us-east-1", "my-app", "Lambda",
                List.of(Map.of("Key", "team", "Value", "core")));
        assertEquals("my-app", r.getPhysicalId());
    }

    @Test
    void anUnnamedApplicationGetsAGeneratedName() {
        when(codeDeploy.createApplication(eq("us-east-1"), anyString(), eq(null), any()))
                .thenAnswer(inv -> app(inv.getArgument(1)));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode(), ctx(null));

        assertTrue(r.getPhysicalId().startsWith("my-stack-App-"), r.getPhysicalId());
    }

    @Test
    void aBlankWrittenIntoTheTemplateReachesCodeDeployToBeRejected() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq(""), any(), any()))
                .thenThrow(new AwsException("ApplicationNameRequiredException", "required", 400));
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "").put("ComputePlatform", "");

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, ctx(null)));

        verify(codeDeploy).createApplication("us-east-1", "", "", List.of());
    }

    @Test
    void aPropertyThatResolvesToNoValueIsAbsent() {
        when(codeDeploy.createApplication(eq("us-east-1"), anyString(), eq(null), any()))
                .thenAnswer(inv -> app(inv.getArgument(1)));
        ObjectNode props = mapper.createObjectNode();
        props.putObject("ApplicationName").put("Ref", "AWS::NoValue");
        props.putObject("ComputePlatform").put("Ref", "AWS::NoValue");
        StackResource r = resource();

        provisioner.provision(r, props, ctx(null));

        assertTrue(r.getPhysicalId().startsWith("my-stack-App-"), r.getPhysicalId());
    }

    @Test
    void anUpdateReconcilesTagsOnTheSameApplication() {
        when(codeDeploy.applicationArn("us-east-1", "my-app")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of(
                Map.of("Key", "team", "Value", "core"), Map.of("Key", "owner", "Value", "alice")));
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app");
        props.putArray("Tags").add(mapper.createObjectNode().put("Key", "team").put("Value", "edge"));
        StackResource r = owning("team", "owner");

        provisioner.provision(r, props, ctx("my-app"));

        verify(codeDeploy, never()).createApplication(anyString(), anyString(), any(), any());
        verify(codeDeploy).untagResource(ARN, List.of("owner"));
        verify(codeDeploy).tagResource(ARN, List.of(Map.of("Key", "team", "Value", "edge")));
        assertEquals("my-app", r.getPhysicalId());
    }

    @Test
    void anUnnamedApplicationKeepsItsNameAcrossUpdates() {
        when(codeDeploy.applicationArn("us-east-1", "my-stack-App-0123456789ab")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of());
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode(), ctx("my-stack-App-0123456789ab"));

        verify(codeDeploy, never()).createApplication(anyString(), anyString(), any(), any());
        assertEquals("my-stack-App-0123456789ab", r.getPhysicalId());
    }

    @Test
    void changingTheComputePlatformOfAnUnnamedApplicationReplacesIt() {
        when(codeDeploy.createApplication(eq("us-east-1"), anyString(), eq("ECS"), any()))
                .thenAnswer(inv -> app(inv.getArgument(1)));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode().put("ComputePlatform", "ECS"),
                ctx("my-stack-App-0123456789ab", mapper.createObjectNode().put("ComputePlatform", "Server")));

        assertTrue(r.getPhysicalId().startsWith("my-stack-App-"), r.getPhysicalId());
        assertNotEquals("my-stack-App-0123456789ab", r.getPhysicalId());
        verify(codeDeploy).createApplication(eq("us-east-1"), eq(r.getPhysicalId()), eq("ECS"), any());
    }

    @Test
    void changingTheComputePlatformOfANamedApplicationIsRefused() {
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app").put("ComputePlatform", "ECS");
        ObjectNode prior = mapper.createObjectNode().put("ApplicationName", "my-app").put("ComputePlatform", "Server");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, ctx("my-app", prior)));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(codeDeploy, never()).createApplication(anyString(), anyString(), any(), any());
    }

    @Test
    void renamingANamedApplicationCreatesTheNewName() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq("new-app"), any(), any())).thenReturn(app("new-app"));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode().put("ApplicationName", "new-app"),
                ctx("my-app", mapper.createObjectNode().put("ApplicationName", "my-app")));

        assertEquals("new-app", r.getPhysicalId());
        verify(codeDeploy).createApplication(eq("us-east-1"), eq("new-app"), any(), any());
    }

    @Test
    void droppingTheNameOfANamedApplicationGeneratesOne() {
        when(codeDeploy.createApplication(eq("us-east-1"), anyString(), any(), any()))
                .thenAnswer(inv -> app(inv.getArgument(1)));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode(),
                ctx("my-app", mapper.createObjectNode().put("ApplicationName", "my-app")));

        assertTrue(r.getPhysicalId().startsWith("my-stack-App-"), r.getPhysicalId());
        verify(codeDeploy).createApplication(eq("us-east-1"), eq(r.getPhysicalId()), any(), any());
    }

    @Test
    void removingAllTagsUntagsEveryKey() {
        when(codeDeploy.applicationArn("us-east-1", "my-app")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of(
                Map.of("Key", "team", "Value", "core"), Map.of("Key", "owner", "Value", "alice")));
        StackResource r = owning("team", "owner");

        provisioner.provision(r, mapper.createObjectNode().put("ApplicationName", "my-app"), ctx("my-app"));

        verify(codeDeploy).untagResource(eq(ARN),
                argThat(keys -> keys.size() == 2 && keys.containsAll(List.of("team", "owner"))));
        verify(codeDeploy, never()).tagResource(anyString(), any());
    }

    @Test
    void aTagAddedOutsideTheStackStays() {
        when(codeDeploy.applicationArn("us-east-1", "my-app")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of(
                Map.of("Key", "team", "Value", "core"), Map.of("Key", "outside", "Value", "kept")));

        provisioner.provision(owning("team"), mapper.createObjectNode().put("ApplicationName", "my-app"),
                ctx("my-app"));

        verify(codeDeploy).untagResource(ARN, List.of("team"));
    }

    /** An in-place update of my-app from team=core, owner=alice (both the stack's) to team=edge. */
    private StackResource updatedInPlace() {
        when(codeDeploy.applicationArn("us-east-1", "my-app")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of(
                Map.of("Key", "team", "Value", "core"), Map.of("Key", "owner", "Value", "alice")));
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app");
        props.putArray("Tags").add(mapper.createObjectNode().put("Key", "team").put("Value", "edge"));
        StackResource r = owning("owner", "team");
        provisioner.provision(r, props, ctx("my-app"));
        return r;
    }

    @Test
    void rollingBackAnInPlaceUpdatePutsTheTagsBack() {
        StackResource r = updatedInPlace();

        assertTrue(provisioner.rollbackUpdate(r));

        verify(codeDeploy).tagResource(eq(ARN), argThat(tags -> tags.size() == 2
                && tags.containsAll(List.of(Map.of("Key", "team", "Value", "core"),
                        Map.of("Key", "owner", "Value", "alice")))));
        assertEquals("[\"owner\",\"team\"]", r.getAttributes().get(CodeDeployCfnProvisioner.TAG_KEYS_ATTR));
        assertFalse(r.getAttributes().containsKey(CodeDeployCfnProvisioner.SNAPSHOT_ATTR));
    }

    @Test
    void aCommittedInPlaceUpdateHasNothingToRollBack() {
        StackResource r = updatedInPlace();

        provisioner.completeUpdate(r);

        assertFalse(r.getAttributes().containsKey(CodeDeployCfnProvisioner.SNAPSHOT_ATTR));
        assertEquals("[\"team\"]", r.getAttributes().get(CodeDeployCfnProvisioner.TAG_KEYS_ATTR));
        assertFalse(provisioner.rollbackUpdate(r));
    }

    @Test
    void aTagUpdateThatFailsPutsTheTagsBackBeforeFailing() {
        when(codeDeploy.applicationArn("us-east-1", "my-app")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of(Map.of("Key", "team", "Value", "core")));
        doThrow(new AwsException("InvalidTagsToAddException", "bad", 400)).doNothing()
                .when(codeDeploy).tagResource(eq(ARN), any());
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app");
        props.putArray("Tags")
                .add(mapper.createObjectNode().put("Key", "team").put("Value", "edge"))
                .add(mapper.createObjectNode().put("Key", "added").put("Value", "x"));

        assertThrows(AwsException.class, () -> provisioner.provision(owning("team"), props, ctx("my-app")));

        verify(codeDeploy).untagResource(ARN, List.of("added"));
        verify(codeDeploy).tagResource(ARN, List.of(Map.of("Key", "team", "Value", "core")));
    }

    /** Replaces an unnamed application by changing its ComputePlatform; returns the resource. */
    private StackResource replacedUnnamedApplication() {
        when(codeDeploy.createApplication(eq("us-east-1"), anyString(), eq("ECS"), any()))
                .thenAnswer(inv -> app(inv.getArgument(1)));
        StackResource r = resource();
        provisioner.provision(r, mapper.createObjectNode().put("ComputePlatform", "ECS"),
                ctx("old-app", mapper.createObjectNode().put("ComputePlatform", "Server")));
        return r;
    }

    @Test
    void completingAReplacementDeletesThePriorApplication() {
        StackResource r = replacedUnnamedApplication();
        String replacement = r.getPhysicalId();

        provisioner.completeUpdate(r);

        verify(codeDeploy).deleteApplication("us-east-1", "old-app");
        verify(codeDeploy, never()).deleteApplication("us-east-1", replacement);
    }

    @Test
    void rollingBackAReplacementDeletesTheNewApplicationAndRestoresTheOld() {
        StackResource r = replacedUnnamedApplication();
        String replacement = r.getPhysicalId();

        assertTrue(provisioner.rollbackUpdate(r));

        verify(codeDeploy).deleteApplication("us-east-1", replacement);
        verify(codeDeploy, never()).deleteApplication("us-east-1", "old-app");
        assertEquals("old-app", r.getPhysicalId());
    }

    /** The dispatcher's stub for App, as a stack created before this provisioner holds it. */
    private static StackResource stub() {
        StackResource r = resource();
        r.getAttributes().put("Arn", "arn:aws:stub:::App");
        return r;
    }

    @Test
    void anUpdateOfAStubCreatesTheApplicationUnderTheStubsId() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq("App-1a2b3c4d"), eq("Lambda"), any()))
                .thenReturn(app("App-1a2b3c4d"));
        StackResource r = stub();
        ObjectNode props = mapper.createObjectNode().put("ComputePlatform", "Lambda");

        provisioner.provision(r, props, ctx("App-1a2b3c4d", props.deepCopy()));

        verify(codeDeploy).createApplication(eq("us-east-1"), eq("App-1a2b3c4d"), eq("Lambda"), any());
        verify(codeDeploy, never()).tagResource(anyString(), any());
        verify(codeDeploy, never()).untagResource(anyString(), any());
        assertEquals("App-1a2b3c4d", r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey("Arn"), "the stub's Arn names nothing that exists");
    }

    @Test
    void rollingBackAStubMigrationDeletesTheApplicationAndRestoresTheStub() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq("App-1a2b3c4d"), any(), any()))
                .thenReturn(app("App-1a2b3c4d"));
        StackResource r = stub();
        provisioner.provision(r, mapper.createObjectNode(), ctx("App-1a2b3c4d", mapper.createObjectNode()));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(codeDeploy).deleteApplication("us-east-1", "App-1a2b3c4d");
        assertEquals("App-1a2b3c4d", r.getPhysicalId());
        assertEquals("arn:aws:stub:::App", r.getAttributes().get("Arn"));
    }

    @Test
    void deleteRemovesTheApplication() {
        provisioner.delete("AWS::CodeDeploy::Application", "my-app", "us-east-1");
        verify(codeDeploy).deleteApplication("us-east-1", "my-app");
    }

    @Test
    void deletingAnApplicationThatIsAlreadyGoneSucceeds() {
        doThrow(new AwsException("ApplicationDoesNotExistException", "gone", 400))
                .when(codeDeploy).deleteApplication("us-east-1", "my-app");
        assertDoesNotThrow(() -> provisioner.delete("AWS::CodeDeploy::Application", "my-app", "us-east-1"));
    }

    @Test
    void anyOtherDeleteFailurePropagates() {
        doThrow(new AwsException("InternalFailure", "boom", 500))
                .when(codeDeploy).deleteApplication("us-east-1", "my-app");
        assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::CodeDeploy::Application", "my-app", "us-east-1"));
    }
}
