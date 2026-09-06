package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Auto Scaling policy CFN provisioner in isolation, against a mocked {@link AutoScalingService}. */
class AutoScalingScalingPolicyCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String TYPE = "AWS::AutoScaling::ScalingPolicy";
    private static final String ARN =
            "arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:my-asg:my-stack-MyPolicy-abc";

    private final AutoScalingService autoScaling = mock(AutoScalingService.class);
    private final AutoScalingScalingPolicyCfnProvisioner provisioner =
            new AutoScalingScalingPolicyCfnProvisioner(autoScaling);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String priorPhysicalId, String priorPolicyName) {
        StackResource r = new StackResource();
        r.setLogicalId("MyPolicy");
        r.setResourceType(TYPE);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        if (priorPolicyName != null) {
            r.getAttributes().put("PolicyName", priorPolicyName);
        }
        return r;
    }

    private static ScalingPolicy policy(String asgName, String name) {
        ScalingPolicy policy = new ScalingPolicy();
        policy.setAutoScalingGroupName(asgName);
        policy.setPolicyName(name);
        policy.setPolicyArn(ARN);
        return policy;
    }

    @Test
    void createSendsThePropertiesAndRecordsArnAndName() {
        when(autoScaling.putScalingPolicy(eq(REGION), eq("my-asg"), anyString(), eq("SimpleScaling"),
                eq("ChangeInCapacity"), eq(1), eq(60), eq(120), isNull()))
                .thenReturn(policy("my-asg", "generated"));
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "my-asg")
                .put("PolicyType", "SimpleScaling")
                .put("AdjustmentType", "ChangeInCapacity")
                .put("ScalingAdjustment", 1)
                .put("Cooldown", 60)
                .put("EstimatedInstanceWarmup", 120);
        StackResource r = resource(null, null);

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(autoScaling).putScalingPolicy(eq(REGION), eq("my-asg"), name.capture(), eq("SimpleScaling"),
                eq("ChangeInCapacity"), eq(1), eq(60), eq(120), isNull());
        assertTrue(name.getValue().startsWith("my-stack-MyPolicy-"), name.getValue());
        assertEquals(ARN, r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals(name.getValue(), r.getAttributes().get("PolicyName"));
    }

    @Test
    void omittedAdjustmentAndCooldownDefaultLikeTheQueryApi() {
        when(autoScaling.putScalingPolicy(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(policy("my-asg", "generated"));

        provisioner.provision(resource(null, null), mapper.createObjectNode().put("AutoScalingGroupName", "my-asg"), ctx());

        verify(autoScaling).putScalingPolicy(eq(REGION), eq("my-asg"), anyString(), isNull(), isNull(),
                eq(0), eq(300), isNull(), isNull());
    }

    @Test
    void targetTrackingConfigurationIsParsed() {
        when(autoScaling.putScalingPolicy(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(policy("my-asg", "generated"));
        ObjectNode props = mapper.createObjectNode()
                .put("AutoScalingGroupName", "my-asg")
                .put("PolicyType", "TargetTrackingScaling");
        ObjectNode tracking = props.putObject("TargetTrackingConfiguration").put("TargetValue", 50.0);
        tracking.putObject("PredefinedMetricSpecification").put("PredefinedMetricType", "ASGAverageCPUUtilization");

        provisioner.provision(resource(null, null), props, ctx());

        ArgumentCaptor<ScalingPolicy.TargetTrackingConfiguration> captor =
                ArgumentCaptor.forClass(ScalingPolicy.TargetTrackingConfiguration.class);
        verify(autoScaling).putScalingPolicy(eq(REGION), eq("my-asg"), anyString(), eq("TargetTrackingScaling"),
                isNull(), eq(0), eq(300), isNull(), captor.capture());
        assertEquals(50.0, captor.getValue().getTargetValue());
        assertEquals("ASGAverageCPUUtilization",
                captor.getValue().getPredefinedMetricSpecification().getPredefinedMetricType());
    }

    @Test
    void updateReusesTheRecordedNameOnTheSameGroup() {
        when(autoScaling.describePolicies(REGION, "my-asg", List.of("my-stack-MyPolicy-abc")))
                .thenReturn(List.of(policy("my-asg", "my-stack-MyPolicy-abc")));
        when(autoScaling.putScalingPolicy(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(policy("my-asg", "my-stack-MyPolicy-abc"));
        StackResource r = resource(ARN, "my-stack-MyPolicy-abc");
        ObjectNode props = mapper.createObjectNode().put("AutoScalingGroupName", "my-asg").put("Cooldown", 120);

        provisioner.provision(r, props, ctx(ARN));

        verify(autoScaling).putScalingPolicy(eq(REGION), eq("my-asg"), eq("my-stack-MyPolicy-abc"), isNull(),
                isNull(), eq(0), eq(120), isNull(), isNull());
        // The region-wide lookup is only for a policy that is not on the template's group.
        verify(autoScaling, never()).describePolicies(eq(REGION), isNull(), anyList());
        assertEquals(ARN, r.getPhysicalId());
        assertEquals("my-stack-MyPolicy-abc", r.getAttributes().get("PolicyName"));
    }

    @Test
    void aSameNamedPolicyOnAnotherGroupDoesNotBlockAnUpdateOnThisOne() {
        when(autoScaling.describePolicies(REGION, "my-asg", List.of("my-stack-MyPolicy-abc")))
                .thenReturn(List.of(policy("my-asg", "my-stack-MyPolicy-abc")));
        when(autoScaling.describePolicies(REGION, null, List.of("my-stack-MyPolicy-abc")))
                .thenReturn(List.of(policy("other-asg", "my-stack-MyPolicy-abc"), policy("my-asg", "my-stack-MyPolicy-abc")));
        when(autoScaling.putScalingPolicy(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(policy("my-asg", "my-stack-MyPolicy-abc"));

        provisioner.provision(resource(ARN, "my-stack-MyPolicy-abc"),
                mapper.createObjectNode().put("AutoScalingGroupName", "my-asg"), ctx(ARN));

        verify(autoScaling).putScalingPolicy(eq(REGION), eq("my-asg"), eq("my-stack-MyPolicy-abc"), isNull(),
                isNull(), eq(0), eq(300), isNull(), isNull());
    }

    @Test
    void updateOfAPolicyGoneFromTheGroupRecreatesItUnderTheSameName() {
        when(autoScaling.describePolicies(eq(REGION), isNull(), anyList())).thenReturn(List.of());
        when(autoScaling.putScalingPolicy(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(policy("my-asg", "my-stack-MyPolicy-abc"));

        provisioner.provision(resource(ARN, "my-stack-MyPolicy-abc"),
                mapper.createObjectNode().put("AutoScalingGroupName", "my-asg"), ctx(ARN));

        verify(autoScaling).putScalingPolicy(eq(REGION), eq("my-asg"), eq("my-stack-MyPolicy-abc"), isNull(),
                isNull(), eq(0), eq(300), isNull(), isNull());
    }

    @Test
    void movingThePolicyToAnotherGroupIsRefusedAsReplacementWorthy() {
        when(autoScaling.describePolicies(REGION, "new-asg", List.of("my-stack-MyPolicy-abc"))).thenReturn(List.of());
        when(autoScaling.describePolicies(REGION, null, List.of("my-stack-MyPolicy-abc")))
                .thenReturn(List.of(policy("old-asg", "my-stack-MyPolicy-abc")));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource(ARN, "my-stack-MyPolicy-abc"),
                mapper.createObjectNode().put("AutoScalingGroupName", "new-asg"), ctx(ARN)));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("AutoScalingGroupName"), e.getMessage());
        verify(autoScaling, never()).putScalingPolicy(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void deleteDelegatesByArn() {
        provisioner.delete(TYPE, ARN, REGION);
        verify(autoScaling).deletePolicy(REGION, null, ARN);
    }

    @Test
    void deleteWithoutAPhysicalIdTouchesNothing() {
        provisioner.delete(TYPE, null, REGION);
        verify(autoScaling, never()).deletePolicy(any(), any(), any());
    }
}
