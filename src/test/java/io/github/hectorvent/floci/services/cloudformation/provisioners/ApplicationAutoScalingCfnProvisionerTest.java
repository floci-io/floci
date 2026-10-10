package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.applicationautoscaling.ApplicationAutoScalingService;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Application Auto Scaling CFN provisioner in isolation. The cases that matter are the
 * composite target id, since that is both {@code Ref} and the link a policy follows to its target,
 * and that the policy configuration blocks survive the trip from template to service unchanged.
 */
class ApplicationAutoScalingCfnProvisionerTest {

    private static final String TARGET_ID = "service/my-cluster/my-service|ecs:service:DesiredCount|ecs";

    private final ApplicationAutoScalingService service = mock(ApplicationAutoScalingService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationAutoScalingCfnProvisioner provisioner =
            new ApplicationAutoScalingCfnProvisioner(service, mapper);

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isNull() ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode targetProps() {
        return mapper.createObjectNode()
                .put("ServiceNamespace", "ecs")
                .put("ResourceId", "service/my-cluster/my-service")
                .put("ScalableDimension", "ecs:service:DesiredCount")
                .put("MinCapacity", 1)
                .put("MaxCapacity", 4);
    }

    private ScalingPolicy aPolicy(String arn) {
        ScalingPolicy policy = new ScalingPolicy();
        policy.setPolicyArn(arn);
        policy.setPolicyName("cpu-target-tracking");
        policy.setServiceNamespace("ecs");
        policy.setResourceId("service/my-cluster/my-service");
        policy.setScalableDimension("ecs:service:DesiredCount");
        return policy;
    }

    @Test
    void aScalableTargetRegistersAndRefsAsTheCompositeId() {
        StackResource r = resource(ApplicationAutoScalingCfnProvisioner.SCALABLE_TARGET, "Target");

        provisioner.provision(r, targetProps(), ctx());

        verify(service).registerScalableTarget("ecs", "service/my-cluster/my-service",
                "ecs:service:DesiredCount", 1, 4, null, null, java.util.Map.of(), "us-east-1");
        // CloudFormation's Ref for a scalable target is resourceId|scalableDimension|serviceNamespace.
        assertEquals(TARGET_ID, r.getPhysicalId());
        assertEquals(TARGET_ID, r.getAttributes().get("Id"));
    }

    @Test
    void aPolicyFollowsScalingTargetIdBackToItsTargetsTriple() {
        when(service.putScalingPolicy(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(aPolicy("arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:abc"));
        StackResource r = resource(ApplicationAutoScalingCfnProvisioner.SCALING_POLICY, "Policy");
        ObjectNode props = mapper.createObjectNode()
                .put("PolicyName", "cpu-target-tracking")
                .put("PolicyType", "TargetTrackingScaling")
                // What `Ref: ServerpublicapiScalableTarget` resolves to.
                .put("ScalingTargetId", TARGET_ID);
        props.putObject("TargetTrackingScalingPolicyConfiguration")
                .put("TargetValue", 60)
                .putObject("PredefinedMetricSpecification")
                .put("PredefinedMetricType", "ECSServiceAverageCPUUtilization");

        provisioner.provision(r, props, ctx());

        verify(service).putScalingPolicy(eq("cpu-target-tracking"), eq("TargetTrackingScaling"),
                eq("ecs"), eq("service/my-cluster/my-service"), eq("ecs:service:DesiredCount"),
                any(), isNull(), eq("us-east-1"));
        // Ref on a scaling policy is its ARN, not its name.
        assertEquals("arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:abc", r.getPhysicalId());
        assertEquals("arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:abc", r.getAttributes().get("Arn"));
    }

    @Test
    void theTargetTrackingConfigurationReachesTheServiceIntact() {
        when(service.putScalingPolicy(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(aPolicy("arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:abc"));
        StackResource r = resource(ApplicationAutoScalingCfnProvisioner.SCALING_POLICY, "Policy");
        ObjectNode props = mapper.createObjectNode()
                .put("PolicyName", "cpu")
                .put("PolicyType", "TargetTrackingScaling")
                .put("ScalingTargetId", TARGET_ID);
        props.putObject("TargetTrackingScalingPolicyConfiguration")
                .put("TargetValue", 60)
                .put("ScaleInCooldown", 300)
                .putObject("PredefinedMetricSpecification")
                .put("PredefinedMetricType", "ECSServiceAverageCPUUtilization");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<TargetTrackingConfiguration> captor =
                ArgumentCaptor.forClass(TargetTrackingConfiguration.class);
        verify(service).putScalingPolicy(any(), any(), any(), any(), any(), captor.capture(), isNull(), any());
        assertEquals(60.0, captor.getValue().getTargetValue());
        assertEquals(300, captor.getValue().getScaleInCooldown());
        assertEquals("ECSServiceAverageCPUUtilization",
                captor.getValue().getPredefinedMetricSpecification().getPredefinedMetricType());
    }

    @Test
    void aPolicyMayNameItsTargetDirectlyInsteadOfReferencingIt() {
        when(service.putScalingPolicy(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(aPolicy("arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:abc"));
        StackResource r = resource(ApplicationAutoScalingCfnProvisioner.SCALING_POLICY, "Policy");
        ObjectNode props = targetProps().put("PolicyName", "step").put("PolicyType", "StepScaling");
        props.putObject("StepScalingPolicyConfiguration").put("AdjustmentType", "ChangeInCapacity");

        provisioner.provision(r, props, ctx());

        verify(service).putScalingPolicy(eq("step"), eq("StepScaling"), eq("ecs"),
                eq("service/my-cluster/my-service"), eq("ecs:service:DesiredCount"),
                isNull(), any(), eq("us-east-1"));
    }

    @Test
    void aScalingTargetIdThatIsNotTheCompositeIsRefused() {
        StackResource r = resource(ApplicationAutoScalingCfnProvisioner.SCALING_POLICY, "Policy");
        ObjectNode props = mapper.createObjectNode()
                .put("PolicyName", "cpu")
                .put("PolicyType", "TargetTrackingScaling")
                .put("ScalingTargetId", "just-a-service-name");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertTrue(e.getMessage().contains("resourceId|scalableDimension|serviceNamespace"), e.getMessage());
    }

    @Test
    void deletingTheTargetDeregistersItsTriple() {
        provisioner.delete(ApplicationAutoScalingCfnProvisioner.SCALABLE_TARGET, TARGET_ID, "us-east-1");

        // Order matters: the composite is resourceId|dimension|namespace, the call is (namespace, id, dimension).
        verify(service).deregisterScalableTarget("ecs", "service/my-cluster/my-service",
                "ecs:service:DesiredCount", "us-east-1");
    }

    @Test
    void deletingAPolicyResolvesItsArnBackToTheNameAndTriple() {
        String arn = "arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:abc";
        when(service.findPolicyByArn(arn, "us-east-1")).thenReturn(Optional.of(aPolicy(arn)));

        provisioner.delete(ApplicationAutoScalingCfnProvisioner.SCALING_POLICY, arn, "us-east-1");

        verify(service).deleteScalingPolicy("cpu-target-tracking", "ecs",
                "service/my-cluster/my-service", "ecs:service:DesiredCount", "us-east-1");
    }

    @Test
    void aPolicyAlreadyGoneIsNotAnError() {
        String arn = "arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:gone";
        when(service.findPolicyByArn(arn, "us-east-1")).thenReturn(Optional.empty());

        // Deregistering a target takes its policies with it, so the stack's own policy delete that
        // follows finds nothing. That is a normal teardown ordering, not a failure.
        provisioner.delete(ApplicationAutoScalingCfnProvisioner.SCALING_POLICY, arn, "us-east-1");
    }

    @Test
    void aTargetWithoutItsCapacityBoundsIsRefused() {
        // MinCapacity and MaxCapacity are required by the resource schema. Registering without them
        // would leave a target that never scales, which is worse than refusing the template.
        for (String missing : new String[] {"MinCapacity", "MaxCapacity"}) {
            ObjectNode props = targetProps();
            props.remove(missing);
            StackResource r = resource(ApplicationAutoScalingCfnProvisioner.SCALABLE_TARGET, "Target");

            AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
            assertTrue(e.getMessage().contains(missing), e.getMessage());
        }
    }

    @Test
    void aPolicyWithoutItsNameOrTypeIsRefused() {
        for (String missing : new String[] {"PolicyName", "PolicyType"}) {
            ObjectNode props = mapper.createObjectNode()
                    .put("PolicyName", "cpu")
                    .put("PolicyType", "TargetTrackingScaling")
                    .put("ScalingTargetId", TARGET_ID);
            props.remove(missing);
            StackResource r = resource(ApplicationAutoScalingCfnProvisioner.SCALING_POLICY, "Policy");

            AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
            assertTrue(e.getMessage().contains(missing), e.getMessage());
        }
    }

    @Test
    void aTargetAlreadyDeregisteredIsNotAnError() {
        doThrow(new AwsException("ObjectNotFoundException", "No scalable target found", 400))
                .when(service).deregisterScalableTarget(any(), any(), any(), any());

        // Its own policies deregister with it, so a stack teardown can reach this twice.
        provisioner.delete(ApplicationAutoScalingCfnProvisioner.SCALABLE_TARGET, TARGET_ID, "us-east-1");
    }
}
