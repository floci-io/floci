package io.floci.aws.services.applicationautoscaling;

import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScheduledActionsResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.PutScheduledActionRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTargetAction;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Shared SDK requests and UTC schedule formatting for scheduled-action tests. */
final class ScheduledActionFixtures {
    static final String DIMENSION = "ecs:service:DesiredCount";

    private ScheduledActionFixtures() {}

    static String schedule(Instant instant) {
        return "at(" + DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC).format(instant) + ")";
    }

    static void register(ApplicationAutoScalingClient client, String resource, int min, int max) {
        client.registerScalableTarget(b -> b.serviceNamespace("ecs").resourceId(resource).scalableDimension(DIMENSION)
                .minCapacity(min).maxCapacity(max));
    }

    static PutScheduledActionRequest.Builder put(String resource, String name) {
        return PutScheduledActionRequest.builder().serviceNamespace("ecs").resourceId(resource)
                .scalableDimension(DIMENSION).scheduledActionName(name);
    }

    static ScalableTargetAction bounds(Integer min, Integer max) {
        return ScalableTargetAction.builder().minCapacity(min).maxCapacity(max).build();
    }

    static DescribeScheduledActionsResponse describe(ApplicationAutoScalingClient client, String resource) {
        return client.describeScheduledActions(b -> b.serviceNamespace("ecs").resourceId(resource).scalableDimension(DIMENSION));
    }

    static void deregister(ApplicationAutoScalingClient client, String resource) {
        client.deregisterScalableTarget(b -> b.serviceNamespace("ecs").resourceId(resource).scalableDimension(DIMENSION));
    }
}
