package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.EventRuleQueue;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.config.JsonPathConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.github.hectorvent.floci.testing.EmrRequests.emr;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code aws.emr} events reach an EventBridge rule's target, as the AWS calls that change a
 * cluster or a step make them. Each test runs its clusters in a Region no other EMR test uses, so its
 * rule sees only its own.
 */
@QuarkusTest
class EmrEventsIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String EMR_EVENTS = "{\"source\":[\"aws.emr\"]}";
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final JsonPathConfig DECIMAL_NUMBERS =
            JsonPathConfig.jsonPathConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aClusterKeptAliveReportsEachStepAndItsTermination() throws Exception {
        String region = "ap-northeast-3";
        try (EventRuleQueue queue = EventRuleQueue.route(region, "emr-events-kept", EMR_EVENTS)) {
            String clusterId = emr(region, "RunJobFlow", "{\"Name\":\"kept\","
                    + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true},"
                    + "\"Steps\":[{\"Name\":\"load\",\"ActionOnFailure\":\"CONTINUE\","
                    + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                    .then().statusCode(200).extract().path("JobFlowId");
            String reportId = emr(region, "AddJobFlowSteps", "{\"JobFlowId\":\"" + clusterId + "\","
                    + "\"Steps\":[{\"Name\":\"report\",\"ActionOnFailure\":\"CONTINUE\","
                    + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                    .then().statusCode(200).extract().path("StepIds[0]");
            Instant reportEnded = stepEnd(region, clusterId, reportId);
            emr(region, "TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);

            List<JsonNode> events = checked(queue.drain(), region, clusterId);
            assertThat(events.stream().map(EmrEventsIntegrationTest::state).toList(), containsInAnyOrder(
                    "STARTING", "PENDING load", "RUNNING load", "COMPLETED load", "WAITING",
                    "PENDING report", "RUNNING report", "COMPLETED report", "WAITING", "TERMINATED"));
            // Once after the first step, and again after the one added later.
            List<JsonNode> waiting = withState(events, "WAITING");
            for (JsonNode event : waiting) {
                assertEquals("{\"code\":\"\"}", event.path("detail").path("stateChangeReason").asText());
            }
            String finished = "Amazon EMR cluster " + clusterId + " (kept) finished running all pending steps at ";
            assertThat(waiting.stream().map(event -> event.path("detail").path("message").asText()).toList(),
                    containsInAnyOrder(finished + minute(timeline(region, clusterId, "ReadyDateTime")) + ".",
                            finished + minute(reportEnded) + "."));
            JsonNode completed = withState(events, "COMPLETED report").get(0).path("detail");
            assertEquals("INFO", completed.path("severity").asText());
            assertEquals("CONTINUE", completed.path("actionOnFailure").asText());
            assertTrue(completed.path("message").asText().endsWith(" and took 0 minutes to complete."),
                    completed.path("message").asText());
            JsonNode terminated = withState(events, "TERMINATED").get(0).path("detail");
            assertEquals("INFO", terminated.path("severity").asText());
            assertEquals("{\"code\":\"USER_REQUEST\",\"message\":\"Terminated by user request\"}",
                    terminated.path("stateChangeReason").asText());
            assertEquals("Amazon EMR Cluster " + clusterId + " (kept) has terminated at "
                    + minute(timeline(region, clusterId, "EndDateTime")) + " with a reason of USER_REQUEST.",
                    terminated.path("message").asText());
        }
    }

    @Test
    void aTransientClusterEndsAfterItsStepsWithoutWaiting() throws Exception {
        String region = "ap-southeast-3";
        try (EventRuleQueue queue = EventRuleQueue.route(region, "emr-events-transient", EMR_EVENTS)) {
            String clusterId = emr(region, "RunJobFlow", "{\"Name\":\"transient\",\"Instances\":{},"
                    + "\"Steps\":[{\"Name\":\"only\",\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                    .then().statusCode(200).extract().path("JobFlowId");

            List<JsonNode> events = checked(queue.drain(), region, clusterId);
            assertThat(events.stream().map(EmrEventsIntegrationTest::state).toList(), containsInAnyOrder(
                    "STARTING", "PENDING only", "RUNNING only", "COMPLETED only", "TERMINATED"));
            JsonNode terminated = withState(events, "TERMINATED").get(0).path("detail");
            assertEquals("INFO", terminated.path("severity").asText());
            assertEquals("{\"code\":\"ALL_STEPS_COMPLETED\",\"message\":\"All steps completed.\"}",
                    terminated.path("stateChangeReason").asText());
            assertEquals("TERMINATE_CLUSTER",
                    withState(events, "PENDING only").get(0).path("detail").path("actionOnFailure").asText());
        }
    }

    /**
     * The events, once each is checked to be one of {@code clusterId}'s, sent by EMR to {@code region}'s
     * default bus with no resources.
     */
    private static List<JsonNode> checked(List<JsonNode> events, String region, String clusterId) {
        for (JsonNode event : events) {
            assertEquals("aws.emr", event.path("source").asText());
            assertEquals(ACCOUNT, event.path("account").asText());
            assertEquals(region, event.path("region").asText());
            assertEquals(0, event.path("resources").size());
            assertEquals(clusterId, event.path("detail").path("clusterId").asText());
            assertEquals(event.path("detail").has("stepId") ? "EMR Step Status Change" : "EMR Cluster State Change",
                    event.path("detail-type").asText());
        }
        return events;
    }

    /** The event's state, a step's followed by its name. */
    private static String state(JsonNode event) {
        JsonNode detail = event.path("detail");
        return detail.has("stepId")
                ? detail.path("state").asText() + " " + detail.path("name").asText()
                : detail.path("state").asText();
    }

    private static List<JsonNode> withState(List<JsonNode> events, String state) {
        return events.stream().filter(event -> state.equals(state(event))).toList();
    }

    private static Instant timeline(String region, String clusterId, String field) {
        BigDecimal seconds = emr(region, "DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}").then()
                .statusCode(200).extract().response().jsonPath(DECIMAL_NUMBERS)
                .get("Cluster.Status.Timeline." + field);
        return Instant.ofEpochMilli(seconds.movePointRight(3).longValue());
    }

    private static Instant stepEnd(String region, String clusterId, String stepId) {
        BigDecimal seconds = emr(region, "DescribeStep", "{\"ClusterId\":\"" + clusterId + "\",\"StepId\":\""
                + stepId + "\"}").then().statusCode(200).extract().response().jsonPath(DECIMAL_NUMBERS)
                .get("Step.Status.Timeline.EndDateTime");
        return Instant.ofEpochMilli(seconds.movePointRight(3).longValue());
    }

    private static String minute(Instant instant) {
        return MINUTE.format(instant);
    }
}
