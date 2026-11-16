package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScheduledActionsResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.InvalidNextTokenException;
import software.amazon.awssdk.services.applicationautoscaling.model.ObjectNotFoundException;
import software.amazon.awssdk.services.applicationautoscaling.model.PutScheduledActionRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableDimension;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTarget;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalingActivity;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalingActivityStatusCode;
import software.amazon.awssdk.services.applicationautoscaling.model.ScheduledAction;
import software.amazon.awssdk.services.applicationautoscaling.model.ServiceNamespace;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ClusterContainsTasksException;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DesiredStatus;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Application Auto Scaling one-off ECS scheduled actions")
class ApplicationAutoScalingTest {
    private static final ServiceNamespace NAMESPACE = ServiceNamespace.ECS;
    private static final ScalableDimension DIMENSION = ScalableDimension.ECS_SERVICE_DESIRED_COUNT;
    private static final DateTimeFormatter SCHEDULE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private ApplicationAutoScalingClient scaling;
    private String name;
    private String resourceId;
    private boolean targetRegistered;

    @BeforeEach
    void registerTarget() {
        name = TestFixtures.uniqueName("sdk-scheduled");
        resourceId = "service/" + name + "/" + name;
        scaling = TestFixtures.applicationAutoScalingClient();
        scaling.registerScalableTarget(r -> r.serviceNamespace(NAMESPACE).resourceId(resourceId)
                .scalableDimension(DIMENSION).minCapacity(0).maxCapacity(4));
        targetRegistered = true;
    }

    @AfterEach
    void cleanup() {
        if (scaling != null) {
            try {
                if (targetRegistered) {
                    scaling.deregisterScalableTarget(r -> r.serviceNamespace(NAMESPACE).resourceId(resourceId)
                            .scalableDimension(DIMENSION));
                }
            } finally {
                scaling.close();
            }
        }
    }

    @Test
    @DisplayName("Create, replace supplied bounds, preserve omitted fields, and delete")
    void scheduledActionLifecycle() {
        String schedule = at(Instant.now().plusSeconds(3600));
        scaling.putScheduledAction(action(name).schedule(schedule)
                .scalableTargetAction(b -> b.minCapacity(1).maxCapacity(3)).build());
        ScheduledAction created = actionNamed(name);
        assertThat(created.scheduledActionARN()).contains(":autoscaling:",
                ":resource/ecs/" + resourceId + ":scheduledActionName/" + name);
        assertThat(created.creationTime()).isNotNull();
        assertThat(created.serviceNamespace()).isEqualTo(NAMESPACE);
        assertThat(created.scalableDimension()).isEqualTo(DIMENSION);
        assertThat(created.resourceId()).isEqualTo(resourceId);

        scaling.putScheduledAction(action(name).scalableTargetAction(b -> b.minCapacity(2)).build());
        ScheduledAction minimumOnly = actionNamed(name);
        assertThat(minimumOnly.schedule()).isEqualTo(schedule);
        assertThat(minimumOnly.scalableTargetAction().minCapacity()).isEqualTo(2);
        assertThat(minimumOnly.scalableTargetAction().maxCapacity()).isNull();
        scaling.putScheduledAction(action(name).scalableTargetAction(b -> b.maxCapacity(3)).build());
        ScheduledAction maximumOnly = actionNamed(name);
        assertThat(maximumOnly.scalableTargetAction().minCapacity()).isNull();
        assertThat(maximumOnly.scalableTargetAction().maxCapacity()).isEqualTo(3);

        String replacementSchedule = at(Instant.now().plusSeconds(7200));
        scaling.putScheduledAction(action(name).schedule(replacementSchedule).build());
        scaling.putScheduledAction(action(name).build());
        ScheduledAction updated = actionNamed(name);
        assertThat(updated.schedule()).isEqualTo(replacementSchedule);
        assertThat(updated.scalableTargetAction()).isEqualTo(maximumOnly.scalableTargetAction());
        assertIdentity(updated, created);
        scaling.deleteScheduledAction(r -> r.serviceNamespace(NAMESPACE).resourceId(resourceId)
                .scalableDimension(DIMENSION).scheduledActionName(name));
        assertThat(actions()).isEmpty();
        assertThatThrownBy(() -> scaling.deleteScheduledAction(r -> r.serviceNamespace(NAMESPACE)
                .resourceId(resourceId).scalableDimension(DIMENSION).scheduledActionName(name)))
                .isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    @DisplayName("Pages stay within the selected resource and names")
    void pagingAndResourceIsolation() {
        String otherResource = resourceId + "-other";
        scaling.registerScalableTarget(r -> r.serviceNamespace(NAMESPACE).resourceId(otherResource)
                .scalableDimension(DIMENSION).minCapacity(0).maxCapacity(4));
        try {
            String schedule = at(Instant.now().plusSeconds(3600));
            for (String actionName : List.of("first", "second", "third")) {
                scaling.putScheduledAction(action(actionName).schedule(schedule)
                        .scalableTargetAction(b -> b.minCapacity(0)).build());
            }
            scaling.putScheduledAction(action("foreign").resourceId(otherResource).schedule(schedule)
                    .scalableTargetAction(b -> b.minCapacity(0)).build());
            DescribeScheduledActionsResponse first = scaling.describeScheduledActions(r -> r
                    .serviceNamespace(NAMESPACE).resourceId(resourceId).scalableDimension(DIMENSION)
                    .scheduledActionNames("first", "third").maxResults(1));
            assertThat(first.scheduledActions()).hasSize(1);
            assertThat(first.nextToken()).isNotBlank();
            DescribeScheduledActionsResponse second = scaling.describeScheduledActions(r -> r
                    .serviceNamespace(NAMESPACE).resourceId(resourceId).scalableDimension(DIMENSION)
                    .scheduledActionNames("first", "third").maxResults(1).nextToken(first.nextToken()));
            assertThat(second.scheduledActions()).hasSize(1);
            assertThat(second.nextToken()).isNullOrEmpty();
            assertThat(Stream.concat(first.scheduledActions().stream(), second.scheduledActions().stream())
                    .map(ScheduledAction::scheduledActionName).toList()).containsExactlyInAnyOrder("first", "third");
            assertThat(actions()).extracting(ScheduledAction::scheduledActionName)
                    .containsExactlyInAnyOrder("first", "second", "third");
            assertThatThrownBy(() -> scaling.describeScheduledActions(r -> r.serviceNamespace(NAMESPACE)
                    .resourceId(otherResource).scalableDimension(DIMENSION).maxResults(1)
                    .nextToken(first.nextToken()))).isInstanceOf(InvalidNextTokenException.class);
            assertThat(scaling.describeScheduledActions(r -> r.serviceNamespace(NAMESPACE)
                    .resourceId(resourceId).scalableDimension(DIMENSION).scheduledActionNames("missing"))
                    .scheduledActions()).isEmpty();
        } finally {
            scaling.deregisterScalableTarget(r -> r.serviceNamespace(NAMESPACE).resourceId(otherResource)
                    .scalableDimension(DIMENSION));
        }
    }

    @Test
    @DisplayName("Autonomously execute bounds and retain completion with stable identity")
    void autonomouslyExecuteAndRetainCompletedAction() throws InterruptedException {
        try (EcsClient ecs = TestFixtures.ecsClient()) {
            ecs.createCluster(r -> r.clusterName(name));
            try {
                TaskDefinition definition = ecs.registerTaskDefinition(r -> r.family(name)
                        .containerDefinitions(ContainerDefinition.builder().name("app").image("alpine:3")
                                .memory(128).build())).taskDefinition();
                try {
                    ecs.createService(r -> r.cluster(name).serviceName(name).launchType(LaunchType.EC2)
                            .taskDefinition(definition.taskDefinitionArn()).desiredCount(2));
                    try {
                        scaling.registerScalableTarget(r -> r.serviceNamespace(NAMESPACE).resourceId(resourceId)
                                .scalableDimension(DIMENSION).minCapacity(2).maxCapacity(4)
                                .suspendedState(s -> s.dynamicScalingInSuspended(true)
                                        .dynamicScalingOutSuspended(true).scheduledScalingSuspended(false)));
                        scaling.putScheduledAction(action(name).schedule(at(Instant.now().plusSeconds(2)))
                                .scalableTargetAction(b -> b.minCapacity(0).maxCapacity(1)).build());
                        ScheduledAction created = actionNamed(name);
                        awaitExecution(ecs, 1, 0, 1, 1);
                        assertBounds(0, 1);
                        assertThat(ecs.describeServices(r -> r.cluster(name).services(name))
                                .services().get(0).desiredCount()).isEqualTo(1);
                        ScheduledAction completed = actionNamed(name);
                        assertIdentity(completed, created);
                        assertThat(completed.scalableTargetAction().minCapacity()).isZero();
                        assertThat(completed.scalableTargetAction().maxCapacity()).isEqualTo(1);
                    } finally {
                        ecs.deleteService(r -> r.cluster(name).service(name).force(true));
                    }
                } finally {
                    ecs.deregisterTaskDefinition(r -> r.taskDefinition(definition.taskDefinitionArn()));
                }
            } finally {
                // An in-flight service reconciliation can add tasks after DeleteService returns.
                TestFixtures.await(() -> {
                    List<String> tasks = Stream.of(DesiredStatus.RUNNING, DesiredStatus.STOPPED)
                            .flatMap(status -> ecs.listTasks(r -> r.cluster(name).desiredStatus(status)).taskArns().stream())
                            .distinct().toList();
                    for (String task : tasks) {
                        ecs.stopTask(r -> r.cluster(name).task(task));
                    }
                    try {
                        ecs.deleteCluster(r -> r.cluster(name));
                        return true;
                    } catch (ClusterContainsTasksException expected) {
                        // Re-list on the next pass to include tasks created after this snapshot.
                        return false;
                    }
                }, name, "cluster deleted", Duration.ofSeconds(60).toNanos(), 200);
            }
        }
    }

    private PutScheduledActionRequest.Builder action(String actionName) {
        return PutScheduledActionRequest.builder().serviceNamespace(NAMESPACE).resourceId(resourceId)
                .scalableDimension(DIMENSION).scheduledActionName(actionName);
    }

    private List<ScheduledAction> actions() {
        return scaling.describeScheduledActions(r -> r.serviceNamespace(NAMESPACE).resourceId(resourceId)
                .scalableDimension(DIMENSION)).scheduledActions();
    }

    private ScheduledAction actionNamed(String actionName) {
        List<ScheduledAction> found = scaling.describeScheduledActions(r -> r.serviceNamespace(NAMESPACE)
                .resourceId(resourceId).scalableDimension(DIMENSION).scheduledActionNames(actionName))
                .scheduledActions();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private void awaitExecution(EcsClient ecs, int activities, int minimum, int maximum, int desired)
            throws InterruptedException {
        TestFixtures.await(() -> {
            List<ScalableTarget> targets = scaling.describeScalableTargets(r -> r.serviceNamespace(NAMESPACE)
                    .resourceIds(resourceId).scalableDimension(DIMENSION)).scalableTargets();
            long successful = scaling.describeScalingActivities(r -> r.serviceNamespace(NAMESPACE)
                    .resourceId(resourceId).scalableDimension(DIMENSION)).scalingActivities().stream()
                    .filter(a -> a.statusCode() == ScalingActivityStatusCode.SUCCESSFUL)
                    .map(ScalingActivity::activityId).distinct().count();
            return successful >= activities && targets.size() == 1
                    && targets.get(0).minCapacity() == minimum && targets.get(0).maxCapacity() == maximum
                    && ecs.describeServices(r -> r.cluster(name).services(name)).services().get(0).desiredCount() == desired;
        }, resourceId, "scheduled bounds and desired count", Duration.ofSeconds(30).toNanos(), 200);
    }

    private void assertBounds(int minimum, int maximum) {
        List<ScalableTarget> targets = scaling.describeScalableTargets(r -> r.serviceNamespace(NAMESPACE)
                .resourceIds(resourceId).scalableDimension(DIMENSION)).scalableTargets();
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).minCapacity()).isEqualTo(minimum);
        assertThat(targets.get(0).maxCapacity()).isEqualTo(maximum);
    }

    private static void assertIdentity(ScheduledAction actual, ScheduledAction original) {
        assertThat(actual.scheduledActionARN()).isEqualTo(original.scheduledActionARN());
        assertThat(actual.creationTime()).isEqualTo(original.creationTime());
    }

    private static String at(Instant instant) {
        return "at(" + SCHEDULE_TIME.format(instant) + ")";
    }
}
