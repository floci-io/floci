package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The AutoScaling CFN provisioner in isolation: {@code AWS::AutoScaling::ScalingPolicy} (issue #17
 * aws-bench gap batch).
 */
class AutoScalingPolicyCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ARN =
            "arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:my-asg:my-policy";

    private final AutoScalingService autoScalingService = mock(AutoScalingService.class);
    private final AutoScalingPolicyCfnProvisioner provisioner =
            new AutoScalingPolicyCfnProvisioner(autoScalingService);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("MyPolicy");
        r.setResourceType("AWS::AutoScaling::ScalingPolicy");
        r.setAttributes(new HashMap<>());
        return r;
    }

    @Test
    void createSetsPhysicalIdAndGetAttArn() {
        ScalingPolicy policy = new ScalingPolicy();
        policy.setPolicyArn(ARN);
        when(autoScalingService.putScalingPolicy(eq(REGION), eq("my-asg"), eq("my-policy"),
                eq("SimpleScaling"), eq("ChangeInCapacity"), eq(1), eq(60), isNull(), isNull(), isNull(),
                isNull(), isNull()))
                .thenReturn(policy);
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "my-asg")
                .put("PolicyName", "my-policy")
                .put("PolicyType", "SimpleScaling")
                .put("AdjustmentType", "ChangeInCapacity")
                .put("ScalingAdjustment", 1)
                .put("Cooldown", 60);

        provisioner.provision(r, props, ctx());

        assertEquals(ARN, r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals("my-policy", r.getAttributes().get("PolicyName"));
    }

    @Test
    void unnamedPolicyKeepsGeneratedNameStableAcrossUpdate() {
        ScalingPolicy created = new ScalingPolicy();
        created.setPolicyArn(ARN);
        when(autoScalingService.putScalingPolicy(eq(REGION), eq("my-asg"), anyString(),
                any(), any(), anyInt(), any(), any(), any(), any(), any(), any())).thenReturn(created);
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode().put("AutoScalingGroupName", "my-asg");

        provisioner.provision(r, props, ctx());
        String generatedName = r.getAttributes().get("PolicyName");

        // UpdateStack re-invokes provision on the same StackResource; without a declared
        // PolicyName, the name generated at create time must be reused, not regenerated.
        provisioner.provision(r, props, ctx());

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(autoScalingService, times(2)).putScalingPolicy(eq(REGION), eq("my-asg"),
                nameCaptor.capture(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
        assertEquals(generatedName, nameCaptor.getAllValues().get(0));
        assertEquals(generatedName, nameCaptor.getAllValues().get(1));
    }

    @Test
    void targetTrackingConfigurationIsParsed() {
        ScalingPolicy policy = new ScalingPolicy();
        policy.setPolicyArn(ARN);
        when(autoScalingService.putScalingPolicy(any(), any(), any(), any(), any(), anyInt(), any(), any(), any(),
                any(), any(), any()))
                .thenReturn(policy);
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "my-asg")
                .put("PolicyName", "my-policy")
                .put("PolicyType", "TargetTrackingScaling");
        ObjectNode ttc = mapper.createObjectNode().put("TargetValue", 50.0);
        ttc.putObject("PredefinedMetricSpecification").put("PredefinedMetricType", "ASGAverageCPUUtilization");
        props.set("TargetTrackingConfiguration", ttc);

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<ScalingPolicy.TargetTrackingConfiguration> captor =
                ArgumentCaptor.forClass(ScalingPolicy.TargetTrackingConfiguration.class);
        verify(autoScalingService).putScalingPolicy(any(), any(), any(), any(), any(), anyInt(), any(), any(), any(),
                captor.capture(), any(), any());
        assertEquals(50.0, captor.getValue().getTargetValue());
        assertEquals("ASGAverageCPUUtilization",
                captor.getValue().getPredefinedMetricSpecification().getPredefinedMetricType());
    }

    @Test
    void deleteDelegatesToServiceWithNullAsgName() {
        provisioner.delete("AWS::AutoScaling::ScalingPolicy", ARN, REGION);

        verify(autoScalingService).deletePolicy(eq(REGION), isNull(), eq(ARN));
    }
}
