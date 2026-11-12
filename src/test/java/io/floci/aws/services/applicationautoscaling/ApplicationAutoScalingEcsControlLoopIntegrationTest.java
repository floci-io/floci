package io.floci.aws.services.applicationautoscaling;

import io.floci.aws.services.cloudwatch.metrics.AlarmEvaluator;
import io.floci.aws.services.cloudwatch.metrics.AlarmEvaluatorTestAccess;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTarget;
import software.amazon.awssdk.services.applicationautoscaling.model.ScheduledAction;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end coverage for the control loop that closes floci-io/floci#2565: a
 * TargetTrackingScaling policy's CloudWatch alarm actually gets evaluated, and a breach
 * changes the ECS service's {@code desiredCount} via {@code UpdateService}.
 *
 * <p>The alarm's period is fixed at 60s with 3 evaluation periods for the high alarm
 * (matching real AWS's target-tracking defaults), and the evaluator's lookback window is
 * period-aligned. Datapoints are pushed with explicit historical {@code Timestamp}s spanning
 * six consecutive periods rather than exactly three, so whichever period boundary the
 * evaluator's tick lands on, its 3-period aligned window is still fully covered — without a
 * 3-minute real wait for fresh data to accrue.</p>
 *
 * <p>Because the pushed metric never responds to the resulting capacity change (unlike a real
 * AWS metric), and AWS's own documented cooldown semantics let a scale-out that computes to a
 * <em>larger</em> capacity than the last one proceed immediately, the service converges across
 * several ticks — 2 desiredCount to 4, 4 to 8, 8 to 10 (clamped at {@code MaxCapacity}) — rather
 * than settling after a single action. It stops there because the next recompute
 * ({@code ceil(10 * 90 / 50) = 18}, clamped back to 10) no longer differs from the current
 * capacity.</p>
 */
@QuarkusTest
class ApplicationAutoScalingEcsControlLoopIntegrationTest {

    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String AAS_TARGET = "AnyScaleFrontendService.";
    private static final String CW_TARGET = "GraniteServiceVersion20100801.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String CLOUDWATCH_JSON_CONTENT_TYPE = "application/x-amz-json-1.0";

    private static final String CLUSTER = "aas-loop-cluster";
    private static final String SERVICE = "aas-loop-svc";
    private static final String RESOURCE_ID = "service/" + CLUSTER + "/" + SERVICE;

    @Inject
    AlarmEvaluator alarmEvaluator;

    @Inject
    ApplicationAutoScalingScheduledActionScheduler scheduledActionScheduler;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String target, String action, String body) {
        return call(target, action, body, CT);
    }

    private static Response call(String target, String action, String body, String contentType) {
        RequestSpecification spec = given().contentType(contentType).header("X-Amz-Target", target + action);
        if (body != null) {
            spec = spec.body(body);
        }
        return spec.when().post("/").then().statusCode(200).extract().response();
    }

    @Test
    void breachingMetricAdjustsEcsDesiredCount() {
        call(ECS_TARGET, "CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}");
        call(ECS_TARGET, "RegisterTaskDefinition", "{\"family\":\"aas-loop-td\","
                + "\"containerDefinitions\":[{\"name\":\"web\",\"image\":\"nginx\",\"memory\":128}]}");
        call(ECS_TARGET, "CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"" + SERVICE + "\","
                + "\"taskDefinition\":\"aas-loop-td\",\"desiredCount\":2}");

        call(AAS_TARGET, "RegisterScalableTarget", """
                {
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "ecs:service:DesiredCount",
                  "MinCapacity": 1,
                  "MaxCapacity": 10
                }
                """.formatted(RESOURCE_ID));

        call(AAS_TARGET, "PutScalingPolicy", """
                {
                  "PolicyName": "aas-loop-cpu",
                  "PolicyType": "TargetTrackingScaling",
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "ecs:service:DesiredCount",
                  "TargetTrackingScalingPolicyConfiguration": {
                    "TargetValue": 50.0,
                    "PredefinedMetricSpecification": {
                      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
                    },
                    "ScaleOutCooldown": 300
                  }
                }
                """.formatted(RESOURCE_ID));

        long now = Instant.now().getEpochSecond();
        StringBuilder metricData = new StringBuilder("[");
        for (int i = 5; i >= 0; i--) {
            if (i != 5) {
                metricData.append(",");
            }
            metricData.append("""
                    {
                      "MetricName": "CPUUtilization",
                      "Value": 90.0,
                      "Unit": "Percent",
                      "Timestamp": %d,
                      "Dimensions": [
                        {"Name": "ClusterName", "Value": "%s"},
                        {"Name": "ServiceName", "Value": "%s"}
                      ]
                    }
                    """.formatted(now - (60L * i), CLUSTER, SERVICE));
        }
        metricData.append("]");
        call(CW_TARGET, "PutMetricData",
                "{\"Namespace\":\"AWS/ECS\",\"MetricData\":" + metricData + "}", CLOUDWATCH_JSON_CONTENT_TYPE);

        int maxCapacity = 10;
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            AlarmEvaluatorTestAccess.evaluateNow(alarmEvaluator);
            call(ECS_TARGET, "DescribeServices",
                    "{\"cluster\":\"" + CLUSTER + "\",\"services\":[\"" + SERVICE + "\"]}")
                    .then().body("services[0].desiredCount", equalTo(maxCapacity));
        });

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    AlarmEvaluatorTestAccess.evaluateNow(alarmEvaluator);
                    call(ECS_TARGET, "DescribeServices",
                            "{\"cluster\":\"" + CLUSTER + "\",\"services\":[\"" + SERVICE + "\"]}")
                            .then().body("services[0].desiredCount", equalTo(maxCapacity));
                });

        call(AAS_TARGET, "DescribeScalingActivities", """
                { "ServiceNamespace": "ecs", "ResourceId": "%s" }
                """.formatted(RESOURCE_ID))
                .then()
                .body("ScalingActivities", hasSize(greaterThan(0)))
                .body("ScalingActivities[0].StatusCode", equalTo("Successful"))
                .body("ScalingActivities[0].ResourceId", equalTo(RESOURCE_ID));
    }
    @Test
    void scheduledActionClampsEcsAndRetainsCompletion() {
        String cluster = "aas-scheduled-cluster";
        String serviceName = "aas-scheduled-service";
        String resource = "service/" + cluster + "/" + serviceName;
        try (ApplicationAutoScalingClient scaling = AwsTestClients.create(ApplicationAutoScalingClient.builder(),
                "000000000000", Region.US_EAST_1);
                EcsClient ecs = AwsTestClients.create(EcsClient.builder(), "000000000000", Region.US_EAST_1)) {
            ecs.createCluster(b -> b.clusterName(cluster));
            ecs.registerTaskDefinition(b -> b.family("aas-scheduled-task")
                    .containerDefinitions(ContainerDefinition.builder().name("web").image("nginx").memory(128).build()));
            ecs.createService(b -> b.cluster(cluster).serviceName(serviceName).taskDefinition("aas-scheduled-task").desiredCount(2));
            ScheduledActionFixtures.register(scaling, resource, 1, 10);
            try {
                scaling.registerScalableTarget(b -> b.serviceNamespace("ecs").resourceId(resource)
                        .scalableDimension(ScheduledActionFixtures.DIMENSION)
                        .suspendedState(state -> state.scheduledScalingSuspended(true)));
                Instant suspendedDeadline = Instant.now().plusSeconds(60);
                scaling.putScheduledAction(ScheduledActionFixtures.put(resource, "clamp")
                        .timezone("UTC").schedule(ScheduledActionFixtures.schedule(suspendedDeadline))
                        .scalableTargetAction(ScheduledActionFixtures.bounds(0, 1)).build());
                ScheduledAction identity = ScheduledActionFixtures.describe(scaling, resource).scheduledActions().getFirst();
                assertEquals("UTC", identity.timezone());
                ScheduledActionFixtures.register(scaling, resource, 1, 10);
                ScalableTarget suspended = scaling.describeScalableTargets(b -> b.serviceNamespace("ecs")
                        .resourceIds(resource)).scalableTargets().getFirst();
                assertEquals(true, suspended.suspendedState().scheduledScalingSuspended());
                scheduledActionScheduler.tick(suspendedDeadline.plusSeconds(1));
                assertScheduledCapacityUnchanged(scaling, ecs, resource, cluster, serviceName);
                scaling.registerScalableTarget(b -> b.serviceNamespace("ecs").resourceId(resource)
                        .scalableDimension(ScheduledActionFixtures.DIMENSION)
                        .suspendedState(state -> state.scheduledScalingSuspended(false)));
                scheduledActionScheduler.tick(suspendedDeadline.plusSeconds(2));
                assertScheduledCapacityUnchanged(scaling, ecs, resource, cluster, serviceName);
                Instant clampDeadline = Instant.now().plusSeconds(60);
                scaling.putScheduledAction(ScheduledActionFixtures.put(resource, "clamp")
                        .schedule(ScheduledActionFixtures.schedule(clampDeadline)).build());
                scheduledActionScheduler.tick(clampDeadline);
                assertEquals(1, ecs.describeServices(b -> b.cluster(cluster).services(serviceName)).services().getFirst().desiredCount());
                assertEquals("Successful", scaling.describeScalingActivities(b -> b.serviceNamespace("ecs").resourceId(resource))
                        .scalingActivities().getFirst().statusCodeAsString());
                assertEquals("UTC", ScheduledActionFixtures.describe(scaling, resource).scheduledActions().getFirst().timezone());
                scaling.putScheduledAction(ScheduledActionFixtures.put(resource, "clamp")
                        .scalableTargetAction(ScheduledActionFixtures.bounds(0, 0)).build());
                scheduledActionScheduler.tick(Instant.now().plusSeconds(60));
                assertEquals(1, ecs.describeServices(b -> b.cluster(cluster).services(serviceName)).services().getFirst().desiredCount());
                Instant rescheduledDeadline = Instant.now().plusSeconds(60);
                scaling.putScheduledAction(ScheduledActionFixtures.put(resource, "clamp")
                        .schedule(ScheduledActionFixtures.schedule(rescheduledDeadline)).build());
                scheduledActionScheduler.tick(rescheduledDeadline);
                assertEquals(0, ecs.describeServices(b -> b.cluster(cluster).services(serviceName)).services().getFirst().desiredCount());
                ScheduledAction completed = ScheduledActionFixtures.describe(scaling, resource).scheduledActions().getFirst();
                assertEquals(identity.scheduledActionARN(), completed.scheduledActionARN());
                assertEquals(identity.creationTime(), completed.creationTime());
                scaling.putScheduledAction(ScheduledActionFixtures.put(resource, "cancel")
                        .schedule(ScheduledActionFixtures.schedule(Instant.now().plusSeconds(60)))
                        .scalableTargetAction(ScheduledActionFixtures.bounds(4, 4)).build());
                scaling.deleteScheduledAction(b -> b.serviceNamespace("ecs").resourceId(resource)
                        .scalableDimension(ScheduledActionFixtures.DIMENSION).scheduledActionName("cancel"));
                scheduledActionScheduler.tick(Instant.now().plusSeconds(120));
                assertEquals(0, ecs.describeServices(b -> b.cluster(cluster).services(serviceName)).services().getFirst().desiredCount());
            } finally {
                ScheduledActionFixtures.deregister(scaling, resource);
                ecs.deleteService(b -> b.cluster(cluster).service(serviceName).force(true));
                ecs.deleteCluster(b -> b.cluster(cluster));
            }
        }
    }

    private static void assertScheduledCapacityUnchanged(ApplicationAutoScalingClient scaling, EcsClient ecs,
                                                         String resource, String cluster, String serviceName) {
        ScalableTarget target = scaling.describeScalableTargets(b -> b.serviceNamespace("ecs").resourceIds(resource))
                .scalableTargets().getFirst();
        assertEquals(1, target.minCapacity());
        assertEquals(10, target.maxCapacity());
        assertEquals(2, ecs.describeServices(b -> b.cluster(cluster).services(serviceName)).services().getFirst().desiredCount());
        assertEquals(0, scaling.describeScalingActivities(b -> b.serviceNamespace("ecs").resourceId(resource))
                .scalingActivities().size());
    }

}
