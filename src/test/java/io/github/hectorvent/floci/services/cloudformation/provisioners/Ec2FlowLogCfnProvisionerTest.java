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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
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
    void updateReconcilesTheTagsOfTheReusedFlowLog() {
        StackResource r = resource();
        r.setPhysicalId("fl-0abc");
        FlowLog existing = flowLog("fl-0abc");
        existing.setResourceId("vpc-123");
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-0abc"))).thenReturn(List.of(existing));
        when(ec2.describeTags("us-east-1", Map.of("resource-id", List.of("fl-0abc"))))
                .thenReturn(List.of(Map.of("key", "old", "value", "gone")));
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");

        provisioner.provision(r, props, ctx());

        // Tags are the one property an update changes in place, so the log is kept, not replaced.
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt());
        verify(ec2).deleteTags(eq("us-east-1"), eq(List.of("fl-0abc")), tags("old", "gone"));
        verify(ec2).createTags(eq("us-east-1"), eq(List.of("fl-0abc")), tags("team", "core"));
    }

    @Test
    void aReservedTagKeyCreatesNoFlowLog() {
        ObjectNode props = mapper.createObjectNode().put("ResourceId", "vpc-123");
        props.putArray("Tags").addObject().put("Key", "aws:reserved").put("Value", "x");

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, ctx()));

        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), any(), anyInt());
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
