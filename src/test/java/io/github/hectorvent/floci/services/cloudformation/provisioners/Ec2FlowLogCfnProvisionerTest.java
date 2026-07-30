package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.FlowLogService;
import io.github.hectorvent.floci.services.ec2.model.FlowLog;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    private FlowLog flowLog(String id) {
        FlowLog fl = new FlowLog();
        fl.setFlowLogId(id);
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
