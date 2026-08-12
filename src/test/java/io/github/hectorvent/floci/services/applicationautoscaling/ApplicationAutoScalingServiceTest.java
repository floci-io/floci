package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.applicationautoscaling.model.PredefinedMetricSpecification;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalableTarget;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ApplicationAutoScalingServiceTest {

    private static final String REGION = "us-east-1";
    private static final String NAMESPACE = "ecs";
    private static final String RESOURCE_ID = "service/my-cluster/my-service";
    private static final String DIMENSION = "ecs:service:DesiredCount";

    private ApplicationAutoScalingService service;

    /** Alarm names currently registered in the stubbed CloudWatch. */
    private final Set<String> alarmsInCloudWatch = new LinkedHashSet<>();

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        alarmsInCloudWatch.clear();

        CloudWatchMetricsService cloudWatch = mock(CloudWatchMetricsService.class);
        doAnswer(invocation -> {
            MetricAlarm alarm = invocation.getArgument(0);
            alarm.setAlarmArn("arn:aws:cloudwatch:" + REGION + ":000000000000:alarm:" + alarm.getAlarmName());
            alarmsInCloudWatch.add(alarm.getAlarmName());
            return null;
        }).when(cloudWatch).putMetricAlarm(any(MetricAlarm.class), anyString());
        doAnswer(invocation -> {
            alarmsInCloudWatch.removeAll(invocation.<List<String>>getArgument(0));
            return null;
        }).when(cloudWatch).deleteAlarms(any(), anyString());

        service = new ApplicationAutoScalingService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), regionResolver, cloudWatch);
    }

    private ScalableTarget register() {
        return service.registerScalableTarget(NAMESPACE, RESOURCE_ID, DIMENSION, 1, 10,
                null, null, Map.of("Environment", "dev"), REGION);
    }

    private TargetTrackingConfiguration targetTracking(double value, String metricType, String resourceLabel) {
        TargetTrackingConfiguration config = new TargetTrackingConfiguration();
        config.setTargetValue(value);
        PredefinedMetricSpecification spec = new PredefinedMetricSpecification();
        spec.setPredefinedMetricType(metricType);
        spec.setResourceLabel(resourceLabel);
        config.setPredefinedMetricSpecification(spec);
        return config;
    }

    @Test
    void registerCreatesTargetWithArnAndServiceLinkedRole() {
        ScalableTarget target = register();

        assertTrue(target.getScalableTargetArn()
                .matches("^arn:aws:application-autoscaling:us-east-1:000000000000:scalable-target/[a-zA-Z0-9-]+$"),
                "ARN must satisfy the AWS pattern, got: " + target.getScalableTargetArn());
        assertEquals("arn:aws:iam::000000000000:role/aws-service-role/"
                + "ecs.application-autoscaling.amazonaws.com/AWSServiceRoleForApplicationAutoScaling_ECSService",
                target.getRoleArn());
        assertEquals(1, target.getMinCapacity());
        assertEquals(10, target.getMaxCapacity());
    }

    @Test
    void registerRequiresCapacityOnlyForNewTargets() {
        assertThrows(AwsException.class, () -> service.registerScalableTarget(
                NAMESPACE, RESOURCE_ID, DIMENSION, null, null, null, null, null, REGION));
    }

    @Test
    void registerIsUpsertOnTheTripleAndLeavesUnsuppliedFieldsUnchanged() {
        String originalArn = register().getScalableTargetArn();

        ScalableTarget updated = service.registerScalableTarget(
                NAMESPACE, RESOURCE_ID, DIMENSION, null, 40, null, null, null, REGION);

        assertEquals(originalArn, updated.getScalableTargetArn(), "upsert must not mint a new ARN");
        assertEquals(40, updated.getMaxCapacity());
        assertEquals(1, updated.getMinCapacity(), "unsupplied min capacity must be preserved");
        assertEquals(1, service.describeScalableTargets(NAMESPACE, null, null, REGION).size());
    }

    @Test
    void describeFiltersByResourceIdAndDimension() {
        register();
        service.registerScalableTarget(NAMESPACE, "service/other/other", DIMENSION, 1, 5,
                null, null, null, REGION);

        assertEquals(2, service.describeScalableTargets(NAMESPACE, null, null, REGION).size());
        assertEquals(1, service.describeScalableTargets(NAMESPACE, List.of(RESOURCE_ID), DIMENSION, REGION).size());
        assertEquals(0, service.describeScalableTargets("kafka", null, null, REGION).size());
    }

    @Test
    void describeRejectsDimensionWithoutResourceId() {
        register();
        assertThrows(AwsException.class,
                () -> service.describeScalableTargets(NAMESPACE, null, DIMENSION, REGION));
    }

    @Test
    void invalidNamespaceAndDimensionAreRejected() {
        AwsException namespace = assertThrows(AwsException.class, () -> service.registerScalableTarget(
                "bogus", RESOURCE_ID, DIMENSION, 1, 2, null, null, null, REGION));
        assertEquals("ValidationException", namespace.getErrorCode());

        AwsException dimension = assertThrows(AwsException.class, () -> service.registerScalableTarget(
                NAMESPACE, RESOURCE_ID, "ecs:service:Bogus", 1, 2, null, null, null, REGION));
        assertEquals("ValidationException", dimension.getErrorCode());
    }

    @Test
    void putScalingPolicyRequiresRegisteredTarget() {
        AwsException e = assertThrows(AwsException.class, () -> service.putScalingPolicy(
                "p", "TargetTrackingScaling", NAMESPACE, RESOURCE_ID, DIMENSION,
                targetTracking(50.0, "ECSServiceAverageCPUUtilization", null), null, REGION));
        assertEquals("ObjectNotFoundException", e.getErrorCode());
    }

    @Test
    void policyArnUsesAutoscalingServiceNameNotApplicationAutoscaling() {
        register();
        ScalingPolicy policy = service.putScalingPolicy("cpu", "TargetTrackingScaling",
                NAMESPACE, RESOURCE_ID, DIMENSION,
                targetTracking(65.0, "ECSServiceAverageCPUUtilization", null), null, REGION);

        assertTrue(policy.getPolicyArn().startsWith("arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:"),
                "policy ARN must use the autoscaling service name, got: " + policy.getPolicyArn());
        assertTrue(policy.getPolicyArn().contains(":resource/ecs/" + RESOURCE_ID + ":policyName/cpu"),
                "policy ARN must embed the resource path and policy name, got: " + policy.getPolicyArn());
    }

    @Test
    void targetTrackingConfigurationRoundTripsEveryField() {
        register();
        service.putScalingPolicy("alb", "TargetTrackingScaling", NAMESPACE, RESOURCE_ID, DIMENSION,
                withCooldowns(targetTracking(1000.0, "ALBRequestCountPerTarget", "app/lb/abc/targetgroup/tg/def")),
                null, REGION);

        TargetTrackingConfiguration stored = service
                .describeScalingPolicies(NAMESPACE, RESOURCE_ID, DIMENSION, null, REGION)
                .get(0)
                .getTargetTrackingConfiguration();

        assertEquals(1000.0, stored.getTargetValue());
        assertEquals("ALBRequestCountPerTarget", stored.getPredefinedMetricSpecification().getPredefinedMetricType());
        assertEquals("app/lb/abc/targetgroup/tg/def",
                stored.getPredefinedMetricSpecification().getResourceLabel());
        assertEquals(240, stored.getScaleInCooldown());
        assertEquals(60, stored.getScaleOutCooldown());
    }

    @Test
    void unsuppliedOptionalsStayNullSoTerraformSeesNoInventedValues() {
        register();
        service.putScalingPolicy("cpu", "TargetTrackingScaling", NAMESPACE, RESOURCE_ID, DIMENSION,
                targetTracking(65.0, "ECSServiceAverageCPUUtilization", null), null, REGION);

        TargetTrackingConfiguration stored = service
                .describeScalingPolicies(NAMESPACE, RESOURCE_ID, DIMENSION, null, REGION)
                .get(0)
                .getTargetTrackingConfiguration();

        assertNull(stored.getScaleInCooldown());
        assertNull(stored.getScaleOutCooldown());
        assertNull(stored.getDisableScaleIn());
        assertNull(stored.getPredefinedMetricSpecification().getResourceLabel());
    }

    @Test
    void targetTrackingPolicyCreatesRealCloudWatchAlarms() {
        register();
        ScalingPolicy policy = service.putScalingPolicy("cpu", "TargetTrackingScaling",
                NAMESPACE, RESOURCE_ID, DIMENSION,
                targetTracking(65.0, "ECSServiceAverageCPUUtilization", null), null, REGION);

        assertEquals(2, policy.getAlarms().size());
        assertEquals(2, alarmsInCloudWatch.size(),
                "alarms must be registered in CloudWatch, not merely synthesized");
        assertTrue(policy.getAlarms().get(0).getAlarmName().startsWith("TargetTracking-" + RESOURCE_ID));
        assertNotNull(policy.getAlarms().get(0).getAlarmArn());
    }

    @Test
    void deregisterCascadesToPoliciesAndAlarms() {
        register();
        service.putScalingPolicy("cpu", "TargetTrackingScaling", NAMESPACE, RESOURCE_ID, DIMENSION,
                targetTracking(65.0, "ECSServiceAverageCPUUtilization", null), null, REGION);

        service.deregisterScalableTarget(NAMESPACE, RESOURCE_ID, DIMENSION, REGION);

        assertEquals(0, service.describeScalableTargets(NAMESPACE, null, null, REGION).size());
        assertEquals(0, service.describeScalingPolicies(NAMESPACE, null, null, null, REGION).size(),
                "policies must be deleted with their scalable target");
        assertEquals(0, alarmsInCloudWatch.size(),
                "alarms owned by deleted policies must be removed");
    }

    @Test
    void deregisterUnknownTargetThrowsObjectNotFound() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.deregisterScalableTarget(NAMESPACE, RESOURCE_ID, DIMENSION, REGION));
        assertEquals("ObjectNotFoundException", e.getErrorCode());
    }

    @Test
    void deleteScalingPolicyRemovesItsAlarms() {
        register();
        service.putScalingPolicy("cpu", "TargetTrackingScaling", NAMESPACE, RESOURCE_ID, DIMENSION,
                targetTracking(65.0, "ECSServiceAverageCPUUtilization", null), null, REGION);

        service.deleteScalingPolicy("cpu", NAMESPACE, RESOURCE_ID, DIMENSION, REGION);

        assertEquals(0, service.describeScalingPolicies(NAMESPACE, null, null, null, REGION).size());
        assertEquals(0, alarmsInCloudWatch.size());
    }

    @Test
    void replacingATargetTrackingPolicyDoesNotLeakAlarms() {
        register();
        service.putScalingPolicy("cpu", "TargetTrackingScaling", NAMESPACE, RESOURCE_ID, DIMENSION,
                targetTracking(65.0, "ECSServiceAverageCPUUtilization", null), null, REGION);
        service.putScalingPolicy("cpu", "TargetTrackingScaling", NAMESPACE, RESOURCE_ID, DIMENSION,
                targetTracking(80.0, "ECSServiceAverageCPUUtilization", null), null, REGION);

        assertEquals(1, service.describeScalingPolicies(NAMESPACE, null, null, null, REGION).size());
        assertEquals(2, alarmsInCloudWatch.size(),
                "the superseded policy's alarms must be replaced, not accumulated");
    }

    @Test
    void tagLifecycleIsDrivenByTheScalableTargetArn() {
        ScalableTarget target = register();
        String arn = target.getScalableTargetArn();

        assertEquals(Map.of("Environment", "dev"), service.listTagsForResource(arn, REGION));

        service.tagResource(arn, Map.of("Team", "platform"), REGION);
        assertEquals(2, service.listTagsForResource(arn, REGION).size());

        service.untagResource(arn, List.of("Environment"), REGION);
        assertEquals(Map.of("Team", "platform"), service.listTagsForResource(arn, REGION));
    }

    @Test
    void listedTagsCannotBeMutatedThroughTheReturnedMap() {
        String arn = register().getScalableTargetArn();

        Map<String, String> listed = service.listTagsForResource(arn, REGION);

        assertThrows(UnsupportedOperationException.class, () -> listed.put("Sneaky", "yes"),
                "tags must not be mutable through the map handed back to callers");
        assertEquals(Map.of("Environment", "dev"), service.listTagsForResource(arn, REGION));
    }

    @Test
    void tagsOnUnknownArnThrowResourceNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.listTagsForResource(
                "arn:aws:application-autoscaling:us-east-1:000000000000:scalable-target/missing", REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void targetsAreIsolatedByRegion() {
        register();
        assertEquals(1, service.describeScalableTargets(NAMESPACE, null, null, REGION).size());
        assertEquals(0, service.describeScalableTargets(NAMESPACE, null, null, "eu-west-1").size());
    }

    private TargetTrackingConfiguration withCooldowns(TargetTrackingConfiguration config) {
        config.setScaleInCooldown(240);
        config.setScaleOutCooldown(60);
        return config;
    }
}
