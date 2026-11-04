package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.FlowLogService;
import io.github.hectorvent.floci.services.ec2.model.FlowLog;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The EC2 flow-log CFN provisioner in isolation, with FlowLogService and Ec2Service mocked. */
class Ec2FlowLogCfnProvisionerTest {

    private final FlowLogService flowLogs = mock(FlowLogService.class);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2FlowLogCfnProvisioner provisioner = new Ec2FlowLogCfnProvisioner(flowLogs, ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return new ProvisionContext(engine(), "us-east-1", "000000000000", "my-stack");
    }

    /** An update of the stack resource at {@code priorId} whose previous template declared {@code priorTagKeys}. */
    private ProvisionContext updateCtx(String priorId, String... priorTagKeys) {
        ObjectNode priorProps = mapper.createObjectNode().put("ResourceId", "vpc-123");
        for (String key : priorTagKeys) {
            priorProps.withArray("Tags").addObject().put("Key", key).put("Value", "prior");
        }
        return new ProvisionContext(engine(), "us-east-1", "000000000000", "my-stack", priorId, event -> { },
                priorProps);
    }

    private CloudFormationTemplateEngine engine() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return engine;
    }

    /** Tag has no equals, so a tag list is matched on its one key and value. */
    private static List<Tag> tags(String key, String value) {
        return argThat(tags -> tags.size() == 1 && key.equals(tags.get(0).getKey())
                && value.equals(tags.get(0).getValue()));
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Flow");
        r.setResourceType("AWS::EC2::FlowLog");
        r.setAttributes(new HashMap<>());
        return r;
    }

    /** Shaped like something createFlowLog stored, which defaults these three when omitted. */
    private FlowLog flowLog(String id) {
        FlowLog fl = new FlowLog();
        fl.setFlowLogId(id);
        fl.setResourceType("VPC");
        fl.setTrafficType("ALL");
        fl.setLogDestinationType("s3");
        return fl;
    }

    @Test
    void flowLogSetsPhysicalIdAndIdAttribute() {
        when(flowLogs.createFlowLog(eq("us-east-1"), eq("vpc-123"), eq("VPC"), eq("ALL"),
                eq("cloud-watch-logs"), eq("arn:aws:logs:us-east-1:000000000000:log-group:flows"),
                eq("arn:aws:iam::000000000000:role/flow-logs-role"), eq("${srcaddr}"), eq(60)))
                .thenReturn(flowLog("fl-0abc"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("ResourceId", "vpc-123")
                .put("ResourceType", "VPC")
                .put("TrafficType", "ALL")
                .put("LogDestinationType", "cloud-watch-logs")
                .put("LogDestination", "arn:aws:logs:us-east-1:000000000000:log-group:flows")
                .put("DeliverLogsPermissionArn", "arn:aws:iam::000000000000:role/flow-logs-role")
                .put("LogFormat", "${srcaddr}")
                .put("MaxAggregationInterval", 60);

        provisioner.provision(r, props, ctx());

        assertEquals("fl-0abc", r.getPhysicalId());
        assertEquals("fl-0abc", r.getAttributes().get("Id"));
    }

    @Test
    void omittedAggregationIntervalDefaultsToTenMinutes() {
        when(flowLogs.createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(flowLog("fl-0def"));

        provisioner.provision(resource(), mapper.createObjectNode().put("ResourceId", "vpc-9"), ctx());

        verify(flowLogs).createFlowLog("us-east-1", "vpc-9", null, null, null, null, null, null, 600);
    }

    @Test
    void updateReusesTheFlowLogThisStackAlreadyCreated() {
        StackResource r = resource();
        r.setPhysicalId("fl-0abc");
        // Stamped with what a create from this template would have stored, so the re-apply is a
        // genuine no-op rather than reading as an added ResourceId.
        FlowLog existing = flowLog("fl-0abc");
        existing.setResourceId("vpc-123");
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-0abc")))
                .thenReturn(List.of(existing));

        provisioner.provision(r, mapper.createObjectNode().put("ResourceId", "vpc-123"), ctx());

        // A second flow log here outlives the stack: delete only knows the id recorded last.
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt());
        assertEquals("fl-0abc", r.getPhysicalId());
        assertEquals("fl-0abc", r.getAttributes().get("Id"));
    }

    @Test
    void changingACreateOnlyPropertyReportsAnUnsupportedReplacement() {
        StackResource r = resource();
        r.setPhysicalId("fl-0abc");
        FlowLog existing = flowLog("fl-0abc");
        existing.setResourceId("vpc-123");
        existing.setTrafficType("ALL");
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-0abc"))).thenReturn(List.of(existing));

        // Reusing regardless would report the stack complete while DescribeFlowLogs kept
        // serving ALL for a template that now asks for REJECT.
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r,
                mapper.createObjectNode().put("ResourceId", "vpc-123").put("TrafficType", "REJECT"), ctx()));
        assertEquals("ValidationError", e.getErrorCode());
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void changingTheDeliverLogsPermissionArnReportsAnUnsupportedReplacement() {
        StackResource r = resource();
        r.setPhysicalId("fl-0abc");
        FlowLog existing = flowLog("fl-0abc");
        existing.setResourceId("vpc-123");
        existing.setDeliverLogsPermissionArn("arn:aws:iam::000000000000:role/old-role");
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-0abc"))).thenReturn(List.of(existing));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r,
                mapper.createObjectNode().put("ResourceId", "vpc-123")
                        .put("DeliverLogsPermissionArn", "arn:aws:iam::000000000000:role/new-role"), ctx()));
        assertEquals("ValidationError", e.getErrorCode());
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void droppingACreateOnlyPropertyReportsAnUnsupportedReplacement() {
        StackResource r = resource();
        r.setPhysicalId("fl-0abc");
        FlowLog existing = flowLog("fl-0abc");
        existing.setResourceId("vpc-123");
        existing.setLogFormat("${srcaddr}");
        existing.setMaxAggregationInterval(60);
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-0abc"))).thenReturn(List.of(existing));

        // Removing a property the template used to declare is as much a change as altering it.
        // Skipping the blank request left the stack complete while the log kept the old format.
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r,
                mapper.createObjectNode().put("ResourceId", "vpc-123"), ctx()));
        assertEquals("ValidationError", e.getErrorCode());
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void reapplyingATemplateThatOmitsDefaultedPropertiesIsANoOp() {
        // createFlowLog stores VPC, ALL and s3 when the template omits them, so a re-apply reads
        // null against those defaults. Comparing raw values made an unchanged stack update fail.
        StackResource r = resource();
        r.setPhysicalId("fl-0abc");
        FlowLog existing = flowLog("fl-0abc");
        existing.setResourceId("vpc-123");
        existing.setResourceType("VPC");
        existing.setTrafficType("ALL");
        existing.setLogDestinationType("s3");
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-0abc"))).thenReturn(List.of(existing));

        provisioner.provision(r, mapper.createObjectNode().put("ResourceId", "vpc-123"), ctx());

        assertEquals("fl-0abc", r.getPhysicalId());
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void addingAPropertyTheLogNeverHadReportsAnUnsupportedReplacement() {
        // The stored null used to short-circuit the comparison, so the new value was ignored and
        // the stack reported complete while the log kept no format at all.
        StackResource r = resource();
        r.setPhysicalId("fl-0abc");
        FlowLog existing = flowLog("fl-0abc");
        existing.setResourceId("vpc-123");
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-0abc"))).thenReturn(List.of(existing));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r,
                mapper.createObjectNode().put("ResourceId", "vpc-123").put("LogFormat", "${srcaddr}"), ctx()));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void reapplyingACloudWatchTemplateThatOmitsTheDestinationTypeIsANoOp() {
        // A real FlowLogService, so the update is compared against what create actually stored:
        // a CloudWatch Logs ARN with no LogDestinationType is stored as cloud-watch-logs, and the
        // compare must default the template the same way or an unchanged update is rejected.
        FlowLogService real = new FlowLogService(mock(Ec2Service.class), mock(S3Service.class),
                new InMemoryStorageFactory("000000000000"));
        Ec2FlowLogCfnProvisioner p = new Ec2FlowLogCfnProvisioner(real, ec2);
        ObjectNode props = mapper.createObjectNode()
                .put("ResourceId", "vpc-123")
                .put("LogDestination", "arn:aws:logs:us-east-1:000000000000:log-group:flows")
                .put("DeliverLogsPermissionArn", "arn:aws:iam::000000000000:role/flow-logs-role");
        StackResource r = resource();

        p.provision(r, props, ctx());
        String created = r.getPhysicalId();
        p.provision(r, props, ctx());

        assertEquals(created, r.getPhysicalId());
        List<FlowLog> logs = real.describeFlowLogs("us-east-1", List.of());
        assertEquals(1, logs.size());
        assertEquals("cloud-watch-logs", logs.get(0).getLogDestinationType());
    }

    @Test
    void updateRecreatesAFlowLogRemovedOutOfBand() {
        StackResource r = resource();
        r.setPhysicalId("fl-gone");
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-gone"))).thenReturn(List.of());
        when(flowLogs.createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(flowLog("fl-new"));

        provisioner.provision(r, mapper.createObjectNode().put("ResourceId", "vpc-123"), ctx());

        assertEquals("fl-new", r.getPhysicalId());
        assertEquals("fl-new", r.getAttributes().get("Id"));
    }

    @Test
    void createTagsTheNewFlowLog() {
        when(flowLogs.createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(flowLog("fl-0abc"));
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");

        provisioner.provision(resource(), props, ctx());

        verify(ec2).createTags(eq("us-east-1"), eq(List.of("fl-0abc")), tags("team", "core"));
    }

    @Test
    void updateReplacesTheTemplatesKeysAndKeepsATagAddedOutsideTheStack() {
        StackResource r = reusedFlowLogWithTags(Map.of("old", "gone", "external", "preserve"));
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");

        provisioner.provision(r, props, updateCtx("fl-0abc", "old"));

        // Tags are the one property an update changes in place, so the log is kept, not replaced.
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt());
        // Only the key the previous template owned goes; AWS keeps one added through EC2.
        verify(ec2).deleteTags(eq("us-east-1"), eq(List.of("fl-0abc")), tags("old", "gone"));
        verify(ec2).createTags(eq("us-east-1"), eq(List.of("fl-0abc")), tags("team", "core"));
    }

    @Test
    void aTagKeyFromAChangedParameterRemovesTheKeyTheLastDeploymentWrote() {
        // Key: {Ref: TagKey} with TagKey changed from team to owner. Re-resolving the previous
        // template reads owner on both sides, so the recorded key is what says team is the stack's.
        StackResource r = reusedFlowLogWithTags(Map.of("team", "core", "external", "preserve"));
        r.getAttributes().put(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR, "[\"team\"]");
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "owner").put("Value", "core");

        provisioner.provision(r, props, updateCtx("fl-0abc", "owner"));

        verify(ec2).deleteTags(eq("us-east-1"), eq(List.of("fl-0abc")), tags("team", "core"));
        verify(ec2).createTags(eq("us-east-1"), eq(List.of("fl-0abc")), tags("owner", "core"));
        assertEquals("[\"owner\"]", r.getAttributes().get(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR));
    }

    @Test
    void rollbackPutsBackTheTagOwnershipRecord() {
        StackResource r = reusedFlowLogWithTags(Map.of("team", "core"));
        r.getAttributes().put(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR, "[\"team\"]");
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "owner").put("Value", "core");
        provisioner.provision(r, props, updateCtx("fl-0abc", "owner"));
        assertEquals("[\"owner\"]", r.getAttributes().get(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR));

        provisioner.rollbackUpdate(r);

        // The walker keeps the attempted resource, so the record has to be put back here, or a
        // later update that drops Tags would remove owner and leave team behind.
        assertEquals("[\"team\"]", r.getAttributes().get(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR));
    }

    @Test
    void takingOverAKeyWithoutChangingItsValueIsRolledBack() {
        // owner=core was added outside the stack; declaring it changes no live tag, only ownership.
        StackResource r = reusedFlowLogWithTags(Map.of("team", "core", "owner", "core"));
        r.getAttributes().put(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR, "[\"team\"]");
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");
        props.withArray("Tags").addObject().put("Key", "owner").put("Value", "core");
        provisioner.provision(r, props, updateCtx("fl-0abc", "team", "owner"));
        assertTrue(r.getAttributes().containsKey(CfnRollback.FLOW_LOG_UPDATE_SNAPSHOT_ATTR));

        provisioner.rollbackUpdate(r);

        assertEquals("[\"team\"]", r.getAttributes().get(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR));
    }

    @Test
    void rollingBackALogFromBeforeTheRecordLeavesNoRecord() {
        StackResource r = reusedFlowLogWithTags(Map.of("team", "core"));
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "owner").put("Value", "core");
        provisioner.provision(r, props, updateCtx("fl-0abc", "team"));

        provisioner.rollbackUpdate(r);

        // Without a record the next update falls back to the previous template, as before.
        assertFalse(r.getAttributes().containsKey(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR));
    }

    @Test
    void createRecordsTheTagKeysItWrote() {
        when(flowLogs.createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(flowLog("fl-0abc"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");

        provisioner.provision(r, props, ctx());

        assertEquals("[\"team\"]", r.getAttributes().get(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR));
    }

    @Test
    void droppingTheTemplatesTagsKeepsATagAddedOutsideTheStack() {
        StackResource r = reusedFlowLogWithTags(Map.of("team", "core", "external", "preserve"));

        provisioner.provision(r, mapper.createObjectNode().put("ResourceId", "vpc-123"), updateCtx("fl-0abc", "team"));

        verify(ec2).deleteTags(eq("us-east-1"), eq(List.of("fl-0abc")), tags("team", "core"));
        verify(ec2, never()).createTags(anyString(), any(), any());
    }

    @Test
    void aReservedTagKeyCreatesNoFlowLog() {
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "aws:reserved").put("Value", "x");

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, ctx()));

        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void aTagValueTooLongCreatesNoFlowLog() {
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "v".repeat(257));

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, ctx()));

        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void aFailedTagWriteDeletesTheNewFlowLog() {
        when(flowLogs.createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(flowLog("fl-0abc"));
        doThrow(new AwsException("InternalError", "tag store down", 500))
                .when(ec2).createTags(anyString(), any(), any());
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, ctx()));

        // Otherwise the log outlives the failed stack, which recorded no id to delete.
        verify(flowLogs).deleteFlowLogs("us-east-1", List.of("fl-0abc"));
    }

    @Test
    void rollbackPutsBackTheTagsAnUpdateChanged() {
        StackResource r = reusedFlowLogWithTags(Map.of("old", "gone", "external", "preserve"));
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");
        provisioner.provision(r, props, updateCtx("fl-0abc", "old"));
        assertTrue(r.getAttributes().containsKey(CfnRollback.FLOW_LOG_UPDATE_SNAPSHOT_ATTR));

        // A later resource failed the update, so the stack rolls this one back.
        assertTrue(provisioner.rollbackUpdate(r));

        // The touched keys go back; the external tag was never touched, so it is not rewritten.
        verify(ec2).createTags(eq("us-east-1"), eq(List.of("fl-0abc")), tags("old", "gone"));
        assertFalse(r.getAttributes().containsKey(CfnRollback.FLOW_LOG_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void anUnchangedReuseLeavesNoTagSnapshot() {
        StackResource r = reusedFlowLogWithTags(Map.of("team", "core", "external", "preserve"));
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");

        provisioner.provision(r, props, updateCtx("fl-0abc", "team"));

        assertFalse(r.getAttributes().containsKey(CfnRollback.FLOW_LOG_UPDATE_SNAPSHOT_ATTR));
        assertTrue(provisioner.rollbackUpdate(r));
    }

    @Test
    void aCommittedUpdateDropsTheTagSnapshot() {
        StackResource r = reusedFlowLogWithTags(Map.of("old", "gone", "external", "preserve"));
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");
        provisioner.provision(r, props, updateCtx("fl-0abc", "old"));

        provisioner.completeUpdate(r);

        assertFalse(r.getAttributes().containsKey(CfnRollback.FLOW_LOG_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackDeletesAFlowLogTheUpdateRecreated() {
        StackResource r = resource();
        r.setPhysicalId("fl-gone");
        r.getAttributes().put(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR, "[\"team\"]");
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-gone"))).thenReturn(List.of());
        when(flowLogs.createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(flowLog("fl-new"));
        provisioner.provision(r, mapper.createObjectNode().put("ResourceId", "vpc-123"), updateCtx("fl-gone"));
        assertEquals("fl-new", r.getPhysicalId());

        assertTrue(provisioner.rollbackUpdate(r));

        // Otherwise the rolled-back stack keeps the failed update's log.
        verify(flowLogs).deleteFlowLogs("us-east-1", List.of("fl-new"));
        assertEquals("fl-gone", r.getPhysicalId());
        assertEquals("[\"team\"]", r.getAttributes().get(Ec2FlowLogCfnProvisioner.TAG_KEYS_ATTR));
        assertEquals("fl-gone", r.getAttributes().get("Id"));
        assertFalse(r.getAttributes().containsKey(CfnRollback.FLOW_LOG_UPDATE_SNAPSHOT_ATTR));
    }

    /** A stack resource pointing at a stored flow log that EC2 reports {@code tags} on. */
    private StackResource reusedFlowLogWithTags(Map<String, String> tags) {
        StackResource r = resource();
        r.setPhysicalId("fl-0abc");
        FlowLog existing = flowLog("fl-0abc");
        existing.setResourceId("vpc-123");
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-0abc"))).thenReturn(List.of(existing));
        when(ec2.describeTags("us-east-1", Map.of("resource-id", List.of("fl-0abc"))))
                .thenReturn(new TreeMap<>(tags).entrySet().stream()
                        .map(tag -> Map.of("key", tag.getKey(), "value", tag.getValue())).toList());
        return r;
    }

    @Test
    void deleteRemovesTheFlowLog() {
        provisioner.delete("AWS::EC2::FlowLog", "fl-0abc", "us-east-1");
        verify(flowLogs).deleteFlowLogs("us-east-1", List.of("fl-0abc"));
    }

    @Test
    void deleteWithoutPhysicalIdIsSkipped() {
        provisioner.delete("AWS::EC2::FlowLog", null, "us-east-1");
        provisioner.delete("AWS::EC2::FlowLog", "  ", "us-east-1");
        verifyNoInteractions(flowLogs);
    }
}
