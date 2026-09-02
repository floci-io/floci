package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalableTarget;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.applicationautoscaling.model.StepAdjustment;
import io.github.hectorvent.floci.services.applicationautoscaling.model.StepScalingConfiguration;
import io.github.hectorvent.floci.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScalingPolicyAlarmActionHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String RESOURCE_ID = "service/my-cluster/my-service";
    private static final String POLICY_ARN =
            "arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:x:resource/ecs/y:policyName/z";

    private final ApplicationAutoScalingService service = mock(ApplicationAutoScalingService.class);
    private final CapacityAdjuster adjuster = mock(CapacityAdjuster.class);
    @SuppressWarnings("unchecked")
    private final Instance<CapacityAdjuster> adjusters = mock(Instance.class);
    private ScalingPolicyAlarmActionHandler handler;

    @BeforeEach
    void setUp() {
        when(adjusters.iterator()).thenAnswer(inv -> List.of(adjuster).iterator());
        when(adjuster.supports("ecs:service:DesiredCount")).thenReturn(true);
        handler = new ScalingPolicyAlarmActionHandler(service, adjusters);
    }

    private static ScalingPolicy targetTrackingPolicy(double targetValue, Boolean disableScaleIn) {
        ScalingPolicy policy = new ScalingPolicy();
        policy.setPolicyName("cpu-target-tracking");
        policy.setPolicyArn(POLICY_ARN);
        policy.setPolicyType("TargetTrackingScaling");
        policy.setServiceNamespace("ecs");
        policy.setResourceId(RESOURCE_ID);
        policy.setScalableDimension("ecs:service:DesiredCount");
        TargetTrackingConfiguration config = new TargetTrackingConfiguration();
        config.setTargetValue(targetValue);
        config.setDisableScaleIn(disableScaleIn);
        policy.setTargetTrackingConfiguration(config);
        return policy;
    }

    private static ScalableTarget target(int min, int max) {
        ScalableTarget target = new ScalableTarget();
        target.setServiceNamespace("ecs");
        target.setResourceId(RESOURCE_ID);
        target.setScalableDimension("ecs:service:DesiredCount");
        target.setMinCapacity(min);
        target.setMaxCapacity(max);
        return target;
    }

    private static MetricAlarm alarm(String comparisonOperator, double threshold) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("TargetTracking-svc-AlarmHigh-abc");
        alarm.setComparisonOperator(comparisonOperator);
        alarm.setThreshold(threshold);
        return alarm;
    }

    private void stub(ScalingPolicy policy, ScalableTarget target, int currentCapacity) {
        when(service.findPolicyByArn(POLICY_ARN, REGION)).thenReturn(Optional.of(policy));
        when(service.findTarget(REGION, "ecs", RESOURCE_ID, "ecs:service:DesiredCount"))
                .thenReturn(Optional.of(target));
        when(adjuster.getCurrentCapacity(RESOURCE_ID, REGION)).thenReturn(currentCapacity);
    }

    @Test
    void scalesOutProportionallyToTargetRatio() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        stub(policy, target(1, 10), 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);

        verify(adjuster).setCapacity(RESOURCE_ID, 7, REGION);
        assertTrue(policy.getLastScaleOutTime() > 0);
        verify(service).savePolicy(policy, REGION);
        verify(service).recordScalingActivity(eq(policy), anyString(), anyString(), eq(REGION));
    }

    @Test
    void clampsToMaxCapacity() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        stub(policy, target(1, 5), 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);

        verify(adjuster).setCapacity(RESOURCE_ID, 5, REGION);
    }

    @Test
    void disableScaleInSuppressesScaleInAction() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, true);
        stub(policy, target(1, 10), 4);

        handler.handle(POLICY_ARN, alarm("LessThanThreshold", 50.0), 10.0, REGION);

        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
    }

    @Test
    void disableScaleInChecksTheComputedResultNotTheAlarmDirection() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, true);
        stub(policy, target(1, 20), 10);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 30.0), 40.0, REGION);

        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
    }

    @Test
    void disableScaleInSuppressesADecreaseCausedByMaxCapacityClamp() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, true);
        stub(policy, target(1, 10), 15);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);

        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
    }

    @Test
    void suspendedScaleOutDirectionSkipsAction() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        ScalableTarget target = target(1, 10);
        target.getSuspendedState().setDynamicScalingOutSuspended(true);
        stub(policy, target, 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);

        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
    }

    @Test
    void cooldownSuppressesRepeatedScaleIn() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        policy.getTargetTrackingConfiguration().setScaleInCooldown(300);
        policy.setLastScaleInTime(System.currentTimeMillis() / 1000.0);
        stub(policy, target(1, 10), 8);

        handler.handle(POLICY_ARN, alarm("LessThanThreshold", 50.0), 20.0, REGION);

        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
    }

    @Test
    void interveningScaleInDoesNotCorruptTheScaleOutCooldownReference() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        policy.getTargetTrackingConfiguration().setScaleOutCooldown(300);
        policy.setLastScaleOutCapacity(10);
        policy.setLastAppliedCapacity(6);
        policy.setLastScaleOutTime(System.currentTimeMillis() / 1000.0);
        stub(policy, target(1, 20), 6);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 60.0, REGION);

        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
    }

    @Test
    void continuesScalingOutAcrossCallsAsCapacityConvergesTowardTheCeiling() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        stub(policy, target(1, 10), 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);
        verify(adjuster).setCapacity(RESOURCE_ID, 7, REGION);

        when(adjuster.getCurrentCapacity(RESOURCE_ID, REGION)).thenReturn(7);
        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);
        verify(adjuster).setCapacity(RESOURCE_ID, 10, REGION);

        when(adjuster.getCurrentCapacity(RESOURCE_ID, REGION)).thenReturn(10);
        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);
        verify(adjuster, times(2)).setCapacity(anyString(), anyInt(), anyString());
    }

    @Test
    void firesAgainWhenTheMetricValueChanges() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        stub(policy, target(1, 20), 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);
        verify(adjuster).setCapacity(RESOURCE_ID, 7, REGION);

        when(adjuster.getCurrentCapacity(RESOURCE_ID, REGION)).thenReturn(7);
        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 95.0, REGION);

        verify(adjuster).setCapacity(RESOURCE_ID, 14, REGION);
    }

    @Test
    void deferredScaleInStillFiresLaterOnceCooldownClears() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        policy.getTargetTrackingConfiguration().setScaleInCooldown(300);
        policy.setLastScaleInTime(System.currentTimeMillis() / 1000.0);
        stub(policy, target(1, 10), 8);

        handler.handle(POLICY_ARN, alarm("LessThanThreshold", 50.0), 20.0, REGION);
        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());

        policy.setLastScaleInTime(0);
        handler.handle(POLICY_ARN, alarm("LessThanThreshold", 50.0), 20.0, REGION);

        verify(adjuster).setCapacity(RESOURCE_ID, 4, REGION);
    }

    @Test
    void noAdjusterRegisteredForDimensionIsNoOp() {
        when(adjuster.supports("ecs:service:DesiredCount")).thenReturn(false);
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        stub(policy, target(1, 10), 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);

        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
    }

    @Test
    void stepScalingExactCapacityAppliesMatchingStepDirectly() {
        ScalingPolicy policy = new ScalingPolicy();
        policy.setPolicyName("step-policy");
        policy.setPolicyArn(POLICY_ARN);
        policy.setPolicyType("StepScaling");
        policy.setServiceNamespace("ecs");
        policy.setResourceId(RESOURCE_ID);
        policy.setScalableDimension("ecs:service:DesiredCount");
        StepScalingConfiguration config = new StepScalingConfiguration();
        config.setAdjustmentType("ExactCapacity");
        StepAdjustment step = new StepAdjustment();
        step.setMetricIntervalLowerBound(0.0);
        step.setScalingAdjustment(9);
        config.setStepAdjustments(List.of(step));
        policy.setStepScalingConfiguration(config);
        stub(policy, target(1, 10), 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);

        verify(adjuster).setCapacity(RESOURCE_ID, 9, REGION);
    }

    @Test
    void suspensionChecksTheComputedDirectionNotTheAlarmDirection() {
        ScalingPolicy policy = new ScalingPolicy();
        policy.setPolicyName("step-policy");
        policy.setPolicyArn(POLICY_ARN);
        policy.setPolicyType("StepScaling");
        policy.setServiceNamespace("ecs");
        policy.setResourceId(RESOURCE_ID);
        policy.setScalableDimension("ecs:service:DesiredCount");
        StepScalingConfiguration config = new StepScalingConfiguration();
        config.setAdjustmentType("ChangeInCapacity");
        StepAdjustment step = new StepAdjustment();
        step.setMetricIntervalLowerBound(0.0);
        step.setScalingAdjustment(-2);
        config.setStepAdjustments(List.of(step));
        policy.setStepScalingConfiguration(config);

        ScalableTarget target = target(1, 10);
        target.getSuspendedState().setDynamicScalingInSuspended(true);
        stub(policy, target, 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);

        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
    }

    @Test
    void nanMetricValueNudgesCapacityByOneInTheAlarmsDirection() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        stub(policy, target(1, 10), 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), Double.NaN, REGION);

        verify(adjuster).setCapacity(RESOURCE_ID, 5, REGION);
    }

    @Test
    void nanMetricValueForStepScalingResolvesTheZeroDeltaStep() {
        ScalingPolicy policy = new ScalingPolicy();
        policy.setPolicyName("step-policy");
        policy.setPolicyArn(POLICY_ARN);
        policy.setPolicyType("StepScaling");
        policy.setServiceNamespace("ecs");
        policy.setResourceId(RESOURCE_ID);
        policy.setScalableDimension("ecs:service:DesiredCount");
        StepScalingConfiguration config = new StepScalingConfiguration();
        config.setAdjustmentType("ChangeInCapacity");
        StepAdjustment step = new StepAdjustment();
        step.setMetricIntervalLowerBound(0.0);
        step.setScalingAdjustment(3);
        config.setStepAdjustments(List.of(step));
        policy.setStepScalingConfiguration(config);
        stub(policy, target(1, 10), 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), Double.NaN, REGION);

        verify(adjuster).setCapacity(RESOURCE_ID, 7, REGION);
    }

    @Test
    void reconciliationContinuesWhenCapacityDriftsHigherExternally() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        stub(policy, target(1, 30), 4);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);
        verify(adjuster).setCapacity(RESOURCE_ID, 7, REGION);

        when(adjuster.getCurrentCapacity(RESOURCE_ID, REGION)).thenReturn(9);
        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);

        verify(adjuster).setCapacity(RESOURCE_ID, 15, REGION);
    }

    @Test
    void reconciliationBypassesCooldownWhenCapacityDriftsLowerExternally() {
        ScalingPolicy policy = targetTrackingPolicy(50.0, null);
        policy.getTargetTrackingConfiguration().setScaleOutCooldown(300);
        policy.setLastAppliedCapacity(10);
        policy.setLastScaleOutTime(System.currentTimeMillis() / 1000.0);
        stub(policy, target(1, 20), 3);

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);

        verify(adjuster).setCapacity(RESOURCE_ID, 5, REGION);
    }

    @Test
    void unknownPolicyArnIsNoOp() {
        when(service.findPolicyByArn(POLICY_ARN, REGION)).thenReturn(Optional.empty());

        handler.handle(POLICY_ARN, alarm("GreaterThanThreshold", 50.0), 80.0, REGION);

        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
    }
}
