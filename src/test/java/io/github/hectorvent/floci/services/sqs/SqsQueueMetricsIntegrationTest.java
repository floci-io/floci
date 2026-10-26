package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.services.cloudwatch.metrics.AlarmEvaluator;
import io.github.hectorvent.floci.services.cloudwatch.metrics.AlarmEvaluatorTestAccess;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * The acceptance of AWS/SQS queue metrics: an alarm on a queue's ApproximateNumberOfMessagesVisible
 * goes to ALARM after one message is sent and back to OK after a purge, with no PutMetricData.
 *
 * <p>The alarm has the 5 minute period of a typical queue-depth alarm. Each publication is the
 * publisher's tick, run 10 and 5 minutes before the queue was created, so the evaluator sees two
 * complete periods at once whatever minute the test starts in. The test configuration turns the
 * scheduled tick off, so these two are the only publications for the queue.
 */
@QuarkusTest
class SqsQueueMetricsIntegrationTest {

    private static final String JSON = "application/x-amz-json-1.0";
    private static final String QUEUE = "metrics-alarm-queue";
    private static final String ALARM = "metrics-alarm-queue-depth";

    @Inject
    SqsMetricsPublisher publisher;

    @Inject
    AlarmEvaluator alarmEvaluator;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void queueDepthAlarmFiresOnASentMessageAndClearsAfterAPurge() {
        Instant created = Instant.now();
        String queueUrl = call("AmazonSQS.CreateQueue", "{\"QueueName\":\"" + QUEUE + "\"}")
                .jsonPath().getString("QueueUrl");
        call("GraniteServiceVersion20100801.PutMetricAlarm", """
                {"AlarmName":"%s","Namespace":"AWS/SQS","MetricName":"ApproximateNumberOfMessagesVisible",
                 "Dimensions":[{"Name":"QueueName","Value":"%s"}],"Statistic":"Maximum","Period":300,
                 "EvaluationPeriods":1,"Threshold":1,"ComparisonOperator":"GreaterThanOrEqualToThreshold"}
                """.formatted(ALARM, QUEUE));

        call("AmazonSQS.SendMessage", "{\"QueueUrl\":\"" + queueUrl + "\",\"MessageBody\":\"held\"}");
        publisher.publish(created.minusSeconds(600));
        awaitAlarmState("ALARM");

        call("AmazonSQS.PurgeQueue", "{\"QueueUrl\":\"" + queueUrl + "\"}");
        publisher.publish(created.minusSeconds(300));
        awaitAlarmState("OK");

        Response data = call("GraniteServiceVersion20100801.GetMetricData", """
                {"StartTime":%d,"EndTime":%d,"ScanBy":"TimestampAscending","MetricDataQueries":[{"Id":"visible",
                 "MetricStat":{"Metric":{"Namespace":"AWS/SQS","MetricName":"ApproximateNumberOfMessagesVisible",
                 "Dimensions":[{"Name":"QueueName","Value":"%s"}]},"Period":300,"Stat":"Maximum"}}]}
                """.formatted(created.minusSeconds(1200).getEpochSecond(), created.getEpochSecond() / 300 * 300 - 1,
                QUEUE));
        assertThat(data.jsonPath().getList("MetricDataResults[0].Values", Double.class), contains(1.0, 0.0));
    }

    /** The scheduled evaluator runs on its own thread and may finish a pass that read the data
     *  before the last publication; the next pass corrects it. */
    private void awaitAlarmState(String expected) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            AlarmEvaluatorTestAccess.evaluateNow(alarmEvaluator);
            call("GraniteServiceVersion20100801.DescribeAlarms", "{\"AlarmNames\":[\"" + ALARM + "\"]}")
                    .then().body("MetricAlarms[0].StateValue", equalTo(expected));
        });
    }

    private static Response call(String target, String body) {
        return given().contentType(JSON).header("X-Amz-Target", target).body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().response();
    }
}
