package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iot.IotService;
import io.github.hectorvent.floci.services.iot.model.IotPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.REGION;
import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.ctx;
import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.notFound;
import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IoT::Policy} through the IoT CFN provisioner in isolation: one mocked service. Every
 * case asserts the exact physical id and the exact {@code Fn::GetAtt} attribute keys, because an
 * unmapped type still reports CREATE_COMPLETE through the dispatcher's stub arm.
 */
class IotPolicyCfnProvisionerTest {

    private static final String TYPE = "AWS::IoT::Policy";
    private static final String ARN = "arn:aws:iot:us-east-1:000000000000:policy/device-policy";
    private static final String RENAMED_ARN = "arn:aws:iot:us-east-1:000000000000:policy/renamed";
    private static final String DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"iot:Connect\",\"Resource\":\"*\"}]}";

    private final IotService iot = mock(IotService.class);
    private final IotCfnProvisioner provisioner = new IotCfnProvisioner(iot);
    private final ObjectMapper mapper = new ObjectMapper();

    private static IotPolicy policy(String name, String document) {
        IotPolicy policy = new IotPolicy();
        policy.setPolicyName(name);
        policy.setPolicyArn("arn:aws:iot:us-east-1:000000000000:policy/" + name);
        policy.setPolicyDocument(document);
        policy.setDefaultVersionId("1");
        return policy;
    }

    private ObjectNode props(String name, JsonNode document, Map<String, String> tags) {
        ObjectNode props = mapper.createObjectNode();
        if (name != null) {
            props.put("PolicyName", name);
        }
        if (document != null) {
            props.set("PolicyDocument", document);
        }
        if (tags != null) {
            tags.forEach((key, value) -> props.withArray("Tags").addObject().put("Key", key).put("Value", value));
        }
        return props;
    }

    private JsonNode document() throws Exception {
        return mapper.readTree(DOCUMENT);
    }

    @Test
    void policyWithAnObjectDocumentSerialisesItAndSetsNameAsPhysicalIdWithArnAndId() throws Exception {
        when(iot.createPolicy("device-policy", DOCUMENT, REGION)).thenReturn(policy("device-policy", DOCUMENT));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props("device-policy", document(), null), ctx());

        assertEquals("device-policy", r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals("device-policy", r.getAttributes().get("Id"));
        verify(iot, never()).tagResource(anyString(), any());
    }

    @Test
    void policyWithAStringDocumentPassesItThroughUnchanged() {
        String document = "{\"Version\": \"2012-10-17\", \"Statement\": []}";
        when(iot.createPolicy("device-policy", document, REGION)).thenReturn(policy("device-policy", document));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props("device-policy", mapper.getNodeFactory().textNode(document), null), ctx());

        verify(iot).createPolicy("device-policy", document, REGION);
        assertEquals(ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void policyRequiresADocument() {
        StackResource r = resource(TYPE, "Policy");
        ObjectNode props = props("device-policy", null, null);

        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(r, props, ctx()));
        verify(iot, never()).createPolicy(anyString(), anyString(), anyString());
    }

    @Test
    void policyWithoutNameGetsAGeneratedName() throws Exception {
        when(iot.createPolicy(anyString(), eq(DOCUMENT), eq(REGION))).thenAnswer(inv -> policy(inv.getArgument(0), DOCUMENT));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props(null, document(), null), ctx());

        assertTrue(r.getPhysicalId().matches("my-stack-Policy-[0-9a-f]{12}"), "generated policy name: " + r.getPhysicalId());
        assertEquals(r.getPhysicalId(), r.getAttributes().get("Id"));
    }

    @Test
    void policyCreateAppliesTheTemplateTags() throws Exception {
        when(iot.createPolicy("device-policy", DOCUMENT, REGION)).thenReturn(policy("device-policy", DOCUMENT));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props("device-policy", document(), Map.of("env", "test")), ctx());

        verify(iot).tagResource(ARN, Map.of("env", "test"));
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void policyCreateLeavesTheResourceOwnedWhenTaggingFailsSoTheRollbackDeletesIt() throws Exception {
        when(iot.createPolicy("device-policy", DOCUMENT, REGION)).thenReturn(policy("device-policy", DOCUMENT));
        doThrow(new AwsException("InternalFailureException", "tagging failed", 500)).when(iot).tagResource(eq(ARN), any());
        StackResource r = resource(TYPE, "Policy");
        ObjectNode props = props("device-policy", document(), Map.of("env", "test"));

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("device-policy", r.getPhysicalId());
        assertEquals("true", r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        verify(iot, never()).deletePolicy(anyString(), anyString());
    }

    @Test
    void policyUpdateWithAChangedDocumentCreatesANewDefaultVersion() throws Exception {
        String changed = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        when(iot.getPolicy("device-policy", REGION)).thenReturn(policy("device-policy", DOCUMENT));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props("device-policy", mapper.readTree(changed), null), ctx("device-policy"));

        verify(iot).createPolicyVersion("device-policy", changed, true, REGION);
        verify(iot, never()).createPolicy(anyString(), anyString(), anyString());
        verify(iot, never()).deletePolicy(anyString(), anyString());
        assertEquals("device-policy", r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals("device-policy", r.getAttributes().get("Id"));
    }

    @Test
    void policyUpdateWithTheSameDocumentDoesNotCreateAVersion() throws Exception {
        when(iot.getPolicy("device-policy", REGION)).thenReturn(policy("device-policy", DOCUMENT));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props("device-policy", document(), null), ctx("device-policy"));

        verify(iot, never()).createPolicyVersion(anyString(), anyString(), anyBoolean(), anyString());
        assertEquals(ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void policyUpdateDrivesTheTagsToTheTemplate() throws Exception {
        when(iot.getPolicy("device-policy", REGION)).thenReturn(policy("device-policy", DOCUMENT));
        when(iot.listTagsForResource(ARN)).thenReturn(Map.of("stale", "1", "env", "old"));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props("device-policy", document(), Map.of("env", "test")), ctx("device-policy"));

        verify(iot).untagResource(ARN, List.of("stale"));
        verify(iot).tagResource(ARN, Map.of("env", "test"));
    }

    @Test
    void policyUpdateWithoutTagsRemovesEveryStoredTag() throws Exception {
        when(iot.getPolicy("device-policy", REGION)).thenReturn(policy("device-policy", DOCUMENT));
        when(iot.listTagsForResource(ARN)).thenReturn(Map.of("stale", "1"));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props("device-policy", document(), null), ctx("device-policy"));

        verify(iot).untagResource(ARN, List.of("stale"));
        verify(iot, never()).tagResource(anyString(), any());
    }

    @Test
    void policyRenameCreatesTheReplacementThenDetachesAndDeletesThePriorPolicy() throws Exception {
        when(iot.createPolicy("renamed", DOCUMENT, REGION)).thenReturn(policy("renamed", DOCUMENT));
        when(iot.listTargetsForPolicy("device-policy", REGION)).thenReturn(Set.of("arn:aws:iot:us-east-1:000000000000:cert/abc"));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props("renamed", document(), null), ctx("device-policy"));

        InOrder inOrder = inOrder(iot);
        inOrder.verify(iot).createPolicy("renamed", DOCUMENT, REGION);
        inOrder.verify(iot).detachPolicy("device-policy", "arn:aws:iot:us-east-1:000000000000:cert/abc", REGION);
        inOrder.verify(iot).deletePolicy("device-policy", REGION);
        verify(iot, never()).createPolicyVersion(anyString(), anyString(), anyBoolean(), anyString());
        assertEquals("renamed", r.getPhysicalId());
        assertEquals(RENAMED_ARN, r.getAttributes().get("Arn"));
        assertEquals("renamed", r.getAttributes().get("Id"));
    }

    @Test
    void policyRenameKeepsTheUpdateWhenThePriorPolicyCannotBeDeleted() throws Exception {
        // As in CloudFormation's cleanup phase: the new policy exists and the stack points at it,
        // so a failed removal of the old one is logged and the update completes.
        when(iot.createPolicy("renamed", DOCUMENT, REGION)).thenReturn(policy("renamed", DOCUMENT));
        doThrow(new AwsException("InternalFailureException", "storage", 500)).when(iot).deletePolicy("device-policy", REGION);
        StackResource r = resource(TYPE, "Policy");

        assertDoesNotThrow(() -> provisioner.provision(r, props("renamed", document(), null), ctx("device-policy")));

        assertEquals("renamed", r.getPhysicalId());
        verify(iot, never()).deletePolicy("renamed", REGION);
    }

    @Test
    void policyReplacementRemovesTheNewPolicyWhenTaggingItFails() throws Exception {
        when(iot.createPolicy("renamed", DOCUMENT, REGION)).thenReturn(policy("renamed", DOCUMENT));
        doThrow(new AwsException("InternalFailureException", "tagging failed", 500)).when(iot).tagResource(eq(RENAMED_ARN), any());
        StackResource r = resource(TYPE, "Policy");
        ObjectNode props = props("renamed", document(), Map.of("env", "test"));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx("device-policy")));

        assertEquals("InternalFailureException", e.getErrorCode());
        verify(iot).deletePolicy("renamed", REGION);
        verify(iot, never()).deletePolicy("device-policy", REGION);
    }

    @Test
    void policyReplacementReportsARollbackFailureWhenTheNewPolicyCannotBeRemoved() throws Exception {
        when(iot.createPolicy("renamed", DOCUMENT, REGION)).thenReturn(policy("renamed", DOCUMENT));
        doThrow(new AwsException("InternalFailureException", "tagging failed", 500)).when(iot).tagResource(eq(RENAMED_ARN), any());
        doThrow(new AwsException("InternalFailureException", "storage", 500)).when(iot).deletePolicy("renamed", REGION);
        StackResource r = resource(TYPE, "Policy");
        ObjectNode props = props("renamed", document(), Map.of("env", "test"));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx("device-policy")));

        assertEquals("InternalFailureException", e.getErrorCode());
        assertEquals(1, e.getSuppressed().length);
        assertTrue(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR).contains("renamed"));
    }

    @Test
    void deletePolicyDetachesEveryTargetBeforeDeleting() {
        when(iot.listTargetsForPolicy("device-policy", REGION)).thenReturn(new TreeSet<>(List.of(
                "arn:aws:iot:us-east-1:000000000000:cert/abc", "arn:aws:iot:us-east-1:000000000000:cert/def")));

        provisioner.delete(TYPE, "device-policy", REGION);

        InOrder inOrder = inOrder(iot);
        inOrder.verify(iot).detachPolicy("device-policy", "arn:aws:iot:us-east-1:000000000000:cert/abc", REGION);
        inOrder.verify(iot).detachPolicy("device-policy", "arn:aws:iot:us-east-1:000000000000:cert/def", REGION);
        inOrder.verify(iot).deletePolicy("device-policy", REGION);
    }

    @Test
    void deletePolicyToleratesOnlyNotFound() {
        doThrow(notFound("Policy")).when(iot).listTargetsForPolicy("gone", REGION);
        doThrow(new AwsException("InternalFailureException", "storage", 500)).when(iot).deletePolicy("stuck", REGION);

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "gone", REGION));
        verify(iot, never()).deletePolicy("gone", REGION);
        AwsException e = assertThrows(AwsException.class, () -> provisioner.delete(TYPE, "stuck", REGION));
        assertEquals("InternalFailureException", e.getErrorCode());
    }

    private static IotPolicy.PolicyVersion version(String id) {
        IotPolicy.PolicyVersion version = new IotPolicy.PolicyVersion();
        version.setVersionId(id);
        return version;
    }

    private static AwsException versionsLimit() {
        return new AwsException("VersionsLimitExceededException", "The number of policy versions exceeds the limit", 409);
    }

    @Test
    void policyUpdateDeletesTheOldestVersionWhenTheServiceRefusesASixthAndCreatesAgain() throws Exception {
        String changed = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        when(iot.getPolicy("device-policy", REGION)).thenReturn(policy("device-policy", DOCUMENT));
        when(iot.createPolicyVersion("device-policy", changed, true, REGION))
                .thenThrow(versionsLimit())
                .thenReturn(version("6"));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props("device-policy", mapper.readTree(changed), null), ctx("device-policy"));

        InOrder inOrder = inOrder(iot);
        inOrder.verify(iot, calls(1)).createPolicyVersion("device-policy", changed, true, REGION);
        inOrder.verify(iot).makeRoomForPolicyVersion("device-policy", REGION);
        inOrder.verify(iot, calls(1)).createPolicyVersion("device-policy", changed, true, REGION);
        verify(iot, times(1)).makeRoomForPolicyVersion(anyString(), anyString());
        verify(iot, never()).deletePolicyVersion(anyString(), anyString(), anyString());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals("device-policy", r.getAttributes().get("Id"));
    }

    @Test
    void policyUpdatePassesOtherVersionErrorsThroughWithoutPruning() throws Exception {
        String changed = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        when(iot.getPolicy("device-policy", REGION)).thenReturn(policy("device-policy", DOCUMENT));
        when(iot.createPolicyVersion("device-policy", changed, true, REGION))
                .thenThrow(new AwsException("InternalFailureException", "storage", 500));
        StackResource r = resource(TYPE, "Policy");
        ObjectNode props = props("device-policy", mapper.readTree(changed), null);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx("device-policy")));

        assertEquals("InternalFailureException", e.getErrorCode());
        verify(iot, never()).makeRoomForPolicyVersion(anyString(), anyString());
    }

    @Test
    void policyUpdatePrunesAgainWhenAnotherVersionRefilledTheSlotBeforeTheRetry() throws Exception {
        // Another client created a version between the prune and the retry.
        String changed = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        when(iot.getPolicy("device-policy", REGION)).thenReturn(policy("device-policy", DOCUMENT));
        when(iot.createPolicyVersion("device-policy", changed, true, REGION))
                .thenThrow(versionsLimit())
                .thenThrow(versionsLimit())
                .thenReturn(version("7"));
        StackResource r = resource(TYPE, "Policy");

        provisioner.provision(r, props("device-policy", mapper.readTree(changed), null), ctx("device-policy"));

        verify(iot, times(2)).makeRoomForPolicyVersion("device-policy", REGION);
        verify(iot, times(3)).createPolicyVersion("device-policy", changed, true, REGION);
        assertEquals(ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void policyUpdateGivesUpAfterAsManyPrunesAsAPolicyHasVersions() throws Exception {
        String changed = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        when(iot.getPolicy("device-policy", REGION)).thenReturn(policy("device-policy", DOCUMENT));
        when(iot.createPolicyVersion("device-policy", changed, true, REGION))
                .thenAnswer(inv -> { throw versionsLimit(); });
        StackResource r = resource(TYPE, "Policy");
        ObjectNode props = props("device-policy", mapper.readTree(changed), null);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx("device-policy")));

        assertEquals("VersionsLimitExceededException", e.getErrorCode());
        verify(iot, times(IotService.MAX_POLICY_VERSIONS - 1)).makeRoomForPolicyVersion("device-policy", REGION);
        verify(iot, times(IotService.MAX_POLICY_VERSIONS)).createPolicyVersion("device-policy", changed, true, REGION);
    }
}
