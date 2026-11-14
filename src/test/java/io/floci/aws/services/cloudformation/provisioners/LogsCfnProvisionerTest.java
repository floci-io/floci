package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Logs::LogGroup}, whose provision body is also its update path: it decides between
 * reconciling in place and replacing, from the prior physical id and the recorded name mode.
 */
class LogsCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String STACK = "my-stack";

    private CloudWatchLogsService logs;
    private LogsCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        logs = mock(CloudWatchLogsService.class);
        provisioner = new LogsCfnProvisioner(logs);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(logs.listTagsLogGroup(anyString(), anyString())).thenReturn(Map.of());
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Provisions as a create (no prior physical id) or an update (prior id and attributes). */
    private StackResource provision(String json, String priorPhysicalId, Map<String, String> attrs) {
        return provision(json, priorPhysicalId, attrs, null);
    }

    private StackResource provision(String json, String priorPhysicalId, Map<String, String> attrs,
                                    String priorJson) {
        StackResource r = new StackResource();
        r.setLogicalId("LogGroup");
        r.setResourceType("AWS::Logs::LogGroup");
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>(attrs));
        provisioner.provision(r, props(json),
                new ProvisionContext(engine, REGION, ACCOUNT_ID, STACK, priorPhysicalId,
                        event -> { }, priorJson == null ? null : props(priorJson)));
        return r;
    }

    @Test
    void createUsesTheExplicitNameAndSetsTheArn() {
        StackResource r = provision("""
                {"LogGroupName": "/aws/app"}
                """, null, Map.of());

        verify(logs).createLogGroup(eq("/aws/app"), isNull(), anyMap(), eq(REGION));
        assertEquals("/aws/app", r.getPhysicalId(), "Ref is the log group name");
        assertEquals("arn:aws:logs:us-east-1:000000000000:log-group:/aws/app:*",
                r.getAttributes().get("Arn"));
    }

    @Test
    void anUnnamedGroupGetsAGeneratedName() {
        StackResource r = provision("{}", null, Map.of());

        assertTrue(r.getPhysicalId().startsWith("my-stack-LogGroup-"), r.getPhysicalId());
    }

    @Test
    void retentionAndTagsReachTheService() {
        ArgumentCaptor<Map<String, String>> tags = ArgumentCaptor.forClass(Map.class);

        provision("""
                {"LogGroupName": "g", "RetentionInDays": "14",
                 "Tags": [{"Key": "env", "Value": "dev"}]}
                """, null, Map.of());

        verify(logs).createLogGroup(eq("g"), eq(14), tags.capture(), eq(REGION));
        assertEquals(Map.of("env", "dev"), tags.getValue());
    }

    /** A non-numeric RetentionInDays leaves retention unset rather than failing the resource. */
    @Test
    void anUnparseableRetentionIsIgnored() {
        provision("""
                {"LogGroupName": "g", "RetentionInDays": "forever"}
                """, null, Map.of());

        verify(logs).createLogGroup(eq("g"), isNull(), anyMap(), eq(REGION));
    }

    @Test
    void anUnchangedNameReconcilesInPlaceInsteadOfRecreating() {
        when(logs.logGroupExists("g", REGION)).thenReturn(true);
        when(logs.listTagsLogGroup("g", REGION)).thenReturn(Map.of("stale", "1"));

        provision("""
                {"LogGroupName": "g", "RetentionInDays": "7"}
                """, "g", Map.of("FlociLogGroupNameMode", "explicit"));

        verify(logs, never()).createLogGroup(anyString(), any(), anyMap(), anyString());
        verify(logs).putRetentionPolicy("g", 7, REGION);
        // A tag dropped from the template is removed from the live group, not just left behind.
        verify(logs).untagLogGroup("g", List.of("stale"), REGION);
    }

    @Test
    void reconcileClearsRetentionWhenTheTemplateDropsIt() {
        when(logs.logGroupExists("g", REGION)).thenReturn(true);

        provision("""
                {"LogGroupName": "g"}
                """, "g", Map.of("FlociLogGroupNameMode", "explicit"));

        verify(logs).deleteRetentionPolicy("g", REGION);
        verify(logs, never()).putRetentionPolicy(anyString(), anyInt(), anyString());
    }

    /** The old group is not deleted inline: the engine deletes it on commit, via the recorded cleanup. */
    @Test
    void aRenameCreatesTheNewGroupAndLeavesTheOldForTheEngineToDelete() {
        when(logs.logGroupExists("old", REGION)).thenReturn(true);

        StackResource r = provision("""
                {"LogGroupName": "new"}
                """, "old", Map.of(), """
                {"LogGroupName": "old"}
                """);

        assertEquals("new", r.getPhysicalId());
        verify(logs).createLogGroup(eq("new"), isNull(), anyMap(), eq(REGION));
        verify(logs, never()).deleteLogGroup("old", REGION);
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("old", provisioner.updateCleanupPhysicalId(r));
    }

    /** If creating the replacement fails, the original is untouched and the failure propagates. */
    @Test
    void aFailedRenameLeavesTheOriginalAndThrows() {
        when(logs.logGroupExists("old", REGION)).thenReturn(true);
        doThrow(new RuntimeException("name taken"))
                .when(logs).createLogGroup(eq("new"), any(), anyMap(), anyString());

        assertThrows(RuntimeException.class, () -> provision("""
                {"LogGroupName": "new"}
                """, "old", Map.of(), """
                {"LogGroupName": "old"}
                """));

        verify(logs, never()).deleteLogGroup("old", REGION);
    }

    /** Dropping an explicit LogGroupName is a replacement on real AWS. */
    @Test
    void removingAnExplicitNameReplacesTheGroup() {
        when(logs.logGroupExists("chosen", REGION)).thenReturn(true);

        StackResource r = provision("{}", "chosen", Map.of(), """
                {"LogGroupName": "chosen"}
                """);

        assertTrue(r.getPhysicalId().startsWith("my-stack-LogGroup-"), r.getPhysicalId());
        verify(logs, never()).deleteLogGroup("chosen", REGION);
        assertEquals("chosen", provisioner.updateCleanupPhysicalId(r));
    }

    /** A generated name is kept across updates so a no-op update does not churn the group. */
    @Test
    void aGeneratedNameIsKeptAcrossUpdates() {
        String generated = "my-stack-LogGroup-abc123def456";
        when(logs.logGroupExists(generated, REGION)).thenReturn(true);

        StackResource r = provision("{}", generated, Map.of("FlociLogGroupNameMode", "generated"));

        assertEquals(generated, r.getPhysicalId());
        verify(logs, never()).createLogGroup(anyString(), any(), anyMap(), anyString());
    }

    @Test
    void deleteReachesTheService() {
        provisioner.delete("AWS::Logs::LogGroup", "g", REGION);
        verify(logs).deleteLogGroup("g", REGION);
    }
}
