package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.FlowLogService;
import io.github.hectorvent.floci.services.ec2.model.FlowLog;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The EC2 flow-log CFN provisioner in isolation, with only FlowLogService mocked. */
class Ec2FlowLogCfnProvisionerTest {

    private final FlowLogService flowLogs = mock(FlowLogService.class);
    private final Ec2FlowLogCfnProvisioner provisioner = new Ec2FlowLogCfnProvisioner(flowLogs);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
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
                eq("s3"), eq("arn:aws:s3:::flow-bucket"), eq("${srcaddr}"), eq(60)))
                .thenReturn(flowLog("fl-0abc"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("ResourceId", "vpc-123")
                .put("ResourceType", "VPC")
                .put("TrafficType", "ALL")
                .put("LogDestinationType", "s3")
                .put("LogDestination", "arn:aws:s3:::flow-bucket")
                .put("LogFormat", "${srcaddr}")
                .put("MaxAggregationInterval", 60);

        provisioner.provision(r, props, ctx());

        assertEquals("fl-0abc", r.getPhysicalId());
        assertEquals("fl-0abc", r.getAttributes().get("Id"));
    }

    @Test
    void omittedAggregationIntervalDefaultsToTenMinutes() {
        when(flowLogs.createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(flowLog("fl-0def"));

        provisioner.provision(resource(), mapper.createObjectNode().put("ResourceId", "vpc-9"), ctx());

        verify(flowLogs).createFlowLog("us-east-1", "vpc-9", null, null, null, null, null, 600);
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
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), anyInt());
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
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), anyInt());
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
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), anyInt());
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
        verify(flowLogs, never()).createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), anyInt());
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
    void updateRecreatesAFlowLogRemovedOutOfBand() {
        StackResource r = resource();
        r.setPhysicalId("fl-gone");
        when(flowLogs.describeFlowLogs("us-east-1", List.of("fl-gone"))).thenReturn(List.of());
        when(flowLogs.createFlowLog(anyString(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(flowLog("fl-new"));

        provisioner.provision(r, mapper.createObjectNode().put("ResourceId", "vpc-123"), ctx());

        assertEquals("fl-new", r.getPhysicalId());
        assertEquals("fl-new", r.getAttributes().get("Id"));
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
