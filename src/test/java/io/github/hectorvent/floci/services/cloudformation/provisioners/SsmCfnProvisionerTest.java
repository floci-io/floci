package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SSM parameter CFN provisioner in isolation. A create carries the template's tags in a put
 * that never overwrites; an update in place removes only the keys the previous template had,
 * keeping tags added outside the template; and a failed stack update puts back what an update in
 * place or a rename changed.
 */
class SsmCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String NAME = "/app/param";
    private static final String RENAMED = NAME + "-new";
    private static final String TEMPLATE_TAG_KEYS_ATTR = "FlociSsmTemplateTagKeys";
    private static final String ARN = "arn:aws:ssm:us-east-1:000000000000:parameter/app/param";

    private final SsmService ssm = mock(SsmService.class);
    private final SsmCfnProvisioner provisioner = new SsmCfnProvisioner(ssm);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // Scalar properties and a flat tag map only, so a passthrough engine keeps this isolated.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    /** An update of the parameter the stack holds as {@link #NAME}, which SSM holds as {@code prior}. */
    private ProvisionContext updateCtx(Parameter prior) {
        when(ssm.getParameter(NAME, REGION)).thenReturn(prior);
        ProvisionContext create = ctx();
        return new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), NAME);
    }

    private ProvisionContext updateCtx() {
        return updateCtx(stored("old", Map.of()));
    }

    /** SSM now holds what the update in place wrote: its value, with the settings the write resets. */
    private void afterTheUpdate(Map<String, String> tags) {
        when(ssm.getParameter(NAME, REGION)).thenReturn(stored("v", tags));
    }

    private static Parameter stored(String value, Map<String, String> tags) {
        Parameter parameter = new Parameter(NAME, value, "String");
        parameter.setTags(new HashMap<>(tags));
        return parameter;
    }

    /** The resource as the stack holds it: created with value {@code old} and the given template tag keys. */
    private static StackResource parameter(String physicalId, String templateTagKeys) {
        StackResource r = new StackResource();
        r.setLogicalId("MyParameter");
        r.setResourceType("AWS::SSM::Parameter");
        r.setAttributes(new HashMap<>());
        if (physicalId != null) {
            r.setPhysicalId(physicalId);
            r.getAttributes().putAll(Map.of("Name", physicalId, "Type", "String", "Value", "old", "Arn", ARN));
        }
        if (templateTagKeys != null) {
            r.getAttributes().put(TEMPLATE_TAG_KEYS_ATTR, templateTagKeys);
        }
        return r;
    }

    private ObjectNode props(String... keyValues) {
        ObjectNode props = mapper.createObjectNode()
                .put("Name", NAME)
                .put("Type", "String")
                .put("Value", "v");
        if (keyValues.length > 0) {
            ObjectNode tags = props.putObject("Tags");
            for (int i = 0; i < keyValues.length; i += 2) {
                tags.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return props;
    }

    private static Map<String, String> visibleAttributes(StackResource r) {
        return r.getAttributes().entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("__Floci"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Test
    void aCreateCarriesTheTemplateTagsInAPutThatDoesNotOverwrite() {
        StackResource r = parameter(null, null);

        provisioner.provision(r, props("team", "a", "env", "dev"), ctx());

        verify(ssm).putParameter(NAME, "v", "String", null, false, Map.of("team", "a", "env", "dev"), REGION);
        verify(ssm, never()).addTagsToResource(anyString(), anyMap(), anyString());
        verify(ssm, never()).removeTagsFromResource(anyString(), any(), anyString());
        verify(ssm, never()).getParameter(anyString(), anyString());
        assertEquals("env,team", r.getAttributes().get(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void parameterWithoutTagsIsNotTagged() {
        StackResource r = parameter(null, null);

        provisioner.provision(r, props(), ctx());

        verify(ssm).putParameter(NAME, "v", "String", null, false, Map.of(), REGION);
        verify(ssm, never()).addTagsToResource(anyString(), anyMap(), anyString());
        verify(ssm, never()).removeTagsFromResource(anyString(), any(), anyString());
        assertFalse(r.getAttributes().containsKey(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void updateRemovesOnlyTheKeyTheTemplateDropped() {
        StackResource r = parameter(NAME, "env,team");

        provisioner.provision(r, props("team", "b"), updateCtx());

        verify(ssm).putParameter(NAME, "v", "String", null, true, REGION);
        verify(ssm).removeTagsFromResource(NAME, List.of("env"), REGION);
        verify(ssm).addTagsToResource(NAME, Map.of("team", "b"), REGION);
        assertEquals("team", r.getAttributes().get(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void updateWithTheTagsPropertyGoneRemovesOnlyTheTemplateKeys() {
        StackResource r = parameter(NAME, "env,team");

        provisioner.provision(r, props(), updateCtx());

        verify(ssm).removeTagsFromResource(eq(NAME),
                argThat(keys -> keys.size() == 2 && keys.containsAll(List.of("team", "env"))), eq(REGION));
        verify(ssm, never()).addTagsToResource(anyString(), anyMap(), anyString());
        assertFalse(r.getAttributes().containsKey(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void updateWithoutRecordedTemplateKeysRemovesNothing() {
        provisioner.provision(parameter(NAME, null), props("team", "b"), updateCtx());

        verify(ssm, never()).removeTagsFromResource(anyString(), any(), anyString());
        verify(ssm).addTagsToResource(NAME, Map.of("team", "b"), REGION);
    }

    /** The snapshot carries the prior value, so it lives under a key Fn::GetAtt cannot name. */
    @Test
    void anUpdateInPlaceKeepsWhatItChangesBeforeItWrites() {
        StackResource r = parameter(NAME, null);

        provisioner.provision(r, props(), updateCtx());

        InOrder order = inOrder(ssm);
        order.verify(ssm).getParameter(NAME, REGION);
        order.verify(ssm).putParameter(NAME, "v", "String", null, true, REGION);
        assertTrue(provisioner.retainsFailedUpdateState(r));
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertEquals(Map.of("Name", NAME, "Type", "String", "Value", "v", "Arn", ARN), visibleAttributes(r));
    }

    @Test
    void rollingBackAnUpdateInPlaceRestoresTheParameterItsTagsAndItsAttributes() {
        Parameter prior = stored("old", Map.of("team", "a", "env", "dev", "external", "x"));
        prior.setDescription("described outside the stack");
        prior.setAllowedPattern("^[a-z]+$");
        prior.setTier("Advanced");
        List<JsonNode> policies = List.of(mapper.createObjectNode().put("Type", "Expiration"));
        prior.setPolicies(policies);
        StackResource r = parameter(NAME, "env,team");
        provisioner.provision(r, props("team", "b", "added", "y"), updateCtx(prior));
        afterTheUpdate(Map.of("team", "b", "env", "dev", "added", "y", "external", "x"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ssm).putParameter(NAME, "old", "String", "described outside the stack", true, null, null,
                "^[a-z]+$", "Advanced", policies, REGION);
        verify(ssm).removeTagsFromResource(NAME, List.of("added"), REGION);
        verify(ssm).addTagsToResource(NAME, Map.of("env", "dev", "team", "a"), REGION);
        verify(ssm, never()).addTagsToResource(eq(NAME), argThat(tags -> tags.containsKey("external")), eq(REGION));
        assertEquals(NAME, r.getPhysicalId());
        assertEquals(Map.of("Name", NAME, "Type", "String", "Value", "old", "Arn", ARN,
                TEMPLATE_TAG_KEYS_ATTR, "env,team"), visibleAttributes(r));
        assertFalse(provisioner.retainsFailedUpdateState(r));
    }

    @Test
    void aRestoreThatFailsKeepsTheSnapshotForTheRetry() {
        StackResource r = parameter(NAME, null);
        provisioner.provision(r, props(), updateCtx());
        afterTheUpdate(Map.of());
        when(ssm.putParameter(eq(NAME), eq("old"), eq("String"), any(), eq(true), any(), any(), any(), any(),
                any(), eq(REGION)))
                .thenThrow(new AwsException("InternalServerError", "simulated", 500))
                .thenReturn(3L);

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertTrue(provisioner.retainsFailedUpdateState(r));
        assertEquals("v", r.getAttributes().get("Value"));

        assertTrue(provisioner.rollbackUpdate(r));
        assertFalse(provisioner.retainsFailedUpdateState(r));
        assertEquals("old", r.getAttributes().get("Value"));
    }

    /**
     * A snapshot an earlier update left, as a skipped ContinueUpdateRollback does, describes no
     * update in flight: a provision that fails before it writes leaves nothing to restore, and one
     * that writes snapshots the parameter as it is now.
     */
    @Test
    void aSnapshotAnEarlierUpdateLeftIsDroppedBeforeAnythingElse() {
        StackResource r = parameter(NAME, null);
        provisioner.provision(r, props(), updateCtx(stored("stale", Map.of())));
        StackResource skipped = parameter(NAME, null);
        skipped.getAttributes().put(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR,
                r.getAttributes().get(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR));

        StackResource refused = parameter(NAME, null);
        refused.getAttributes().putAll(skipped.getAttributes());
        assertThrows(AwsException.class, () -> provisioner.provision(refused, props("", "x"), updateCtx()));
        assertFalse(provisioner.retainsFailedUpdateState(refused));

        provisioner.provision(skipped, props(), updateCtx(stored("current", Map.of())));
        afterTheUpdate(Map.of());
        assertTrue(provisioner.rollbackUpdate(skipped));
        verify(ssm).putParameter(eq(NAME), eq("current"), eq("String"), any(), eq(true), any(), any(), any(),
                any(), any(), eq(REGION));
        verify(ssm, never()).putParameter(eq(NAME), eq("stale"), anyString(), any(), anyBoolean(), any(), any(),
                any(), any(), any(), anyString());
    }

    /** AWS's handler checks the parameter exists before an update in place writes it. */
    @Test
    void anUpdateInPlaceOfAParameterDeletedOutsideTheStackFailsBeforeItWrites() {
        when(ssm.getParameter(NAME, REGION)).thenThrow(new AwsException("ParameterNotFound", "gone", 400));
        StackResource r = parameter(NAME, null);
        ProvisionContext create = ctx();
        ProvisionContext update = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), NAME);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props(), update));

        assertEquals("ParameterNotFound", failure.getErrorCode());
        verify(ssm, never()).putParameter(anyString(), anyString(), anyString(), any(), anyBoolean(), anyString());
        assertFalse(provisioner.retainsFailedUpdateState(r));
    }

    /** A parameter deleted after the update wrote it is not re-created by the rollback, as on AWS. */
    @Test
    void aRestoreOfAParameterDeletedAfterTheUpdateFailsAndKeepsTheSnapshot() {
        StackResource r = parameter(NAME, null);
        provisioner.provision(r, props(), updateCtx());
        when(ssm.getParameter(NAME, REGION)).thenThrow(new AwsException("ParameterNotFound", "gone", 400));

        assertEquals("ParameterNotFound",
                assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r)).getErrorCode());

        verify(ssm, never()).putParameter(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(),
                any(), any(), any(), anyString());
        assertTrue(provisioner.retainsFailedUpdateState(r));
    }

    /** An update that failed before its write left nothing to write back, so the rollback adds no version. */
    @Test
    void aRestoreWritesNothingWhenTheParameterStillHoldsItsPriorState() {
        StackResource r = parameter(NAME, "team");
        provisioner.provision(r, props("team", "b"), updateCtx(stored("old", Map.of("team", "a"))));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ssm, never()).putParameter(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(),
                any(), any(), any(), anyString());
        verify(ssm).addTagsToResource(NAME, Map.of("team", "a"), REGION);
    }

    /** A stub created nothing, so the parameter that takes over its id is created, not updated. */
    @Test
    void aParameterThatTakesOverAStubIdIsCreated() {
        String stubId = "MyParameter-1a2b3c4d";
        StackResource r = parameter(null, null);
        r.setPhysicalId(stubId);
        r.getAttributes().put("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + "MyParameter");
        ProvisionContext create = ctx();
        ProvisionContext update = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), stubId);
        ObjectNode unnamed = props("team", "a");
        unnamed.remove("Name");

        provisioner.provision(r, unnamed, update);

        verify(ssm, never()).getParameter(anyString(), anyString());
        verify(ssm).putParameter(stubId, "v", "String", null, false, Map.of("team", "a"), REGION);
        assertFalse(provisioner.retainsFailedUpdateState(r));
        assertTrue(provisioner.rollbackUpdate(r));
        verify(ssm).deleteParameter(stubId, REGION);
        assertEquals(stubId, r.getPhysicalId());
    }

    @Test
    void aCommittedUpdateInPlaceSpendsItsSnapshot() {
        StackResource r = parameter(NAME, null);
        provisioner.provision(r, props(), updateCtx());

        assertFalse(provisioner.completeUpdate(r).applicable());

        assertFalse(provisioner.retainsFailedUpdateState(r));
        assertFalse(r.getAttributes().containsKey(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR));
    }

    /** A rename never writes the prior parameter, so it neither reads it nor needs it to exist. */
    @Test
    void aRenameCreatesTheReplacementWithoutReadingOrOverwriting() {
        StackResource r = parameter(NAME, "team");

        provisioner.provision(r, props("team", "b").put("Name", RENAMED), updateCtx());

        verify(ssm, never()).getParameter(anyString(), anyString());
        verify(ssm).putParameter(RENAMED, "v", "String", null, false, Map.of("team", "b"), REGION);
        verify(ssm, never()).removeTagsFromResource(anyString(), any(), anyString());
        assertEquals(RENAMED, r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(NAME, provisioner.updateCleanupPhysicalId(r));
        assertFalse(provisioner.retainsFailedUpdateState(r));
    }

    @Test
    void rollingBackARenameDeletesTheReplacementAndNamesThePriorParameterAgain() {
        StackResource r = parameter(NAME, "team");
        provisioner.provision(r, props("team", "b").put("Name", RENAMED), updateCtx());

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ssm).deleteParameter(RENAMED, REGION);
        verify(ssm, never()).deleteParameter(NAME, REGION);
        assertEquals(NAME, r.getPhysicalId());
        assertEquals(Map.of("Name", NAME, "Type", "String", "Value", "old", "Arn", ARN,
                TEMPLATE_TAG_KEYS_ATTR, "team"), visibleAttributes(r));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void aCommittedRenameDeletesTheDisplacedParameter() {
        StackResource r = parameter(NAME, null);
        provisioner.provision(r, props().put("Name", RENAMED), updateCtx());

        UpdateCleanupResult cleanup = provisioner.completeUpdate(r);
        provisioner.clearUpdate(r);

        assertTrue(cleanup.complete());
        verify(ssm).deleteParameter(NAME, REGION);
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertNull(provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void deleteToleratesOnlyAParameterThatIsAlreadyGone() {
        doThrow(new AwsException("ParameterNotFound", "gone", 400)).when(ssm).deleteParameter(NAME, REGION);
        provisioner.delete("AWS::SSM::Parameter", NAME, REGION);

        doThrow(new AwsException("AccessDeniedException", "denied", 400)).when(ssm).deleteParameter(NAME, REGION);
        assertThrows(AwsException.class, () -> provisioner.delete("AWS::SSM::Parameter", NAME, REGION));
    }

    @Test
    void aResourceWithNothingRecordedHasNoRollback() {
        StackResource r = parameter(NAME, null);

        assertFalse(provisioner.rollbackUpdate(r));
        assertEquals(Set.of("Name", "Type", "Value", "Arn"), r.getAttributes().keySet());
    }
}
