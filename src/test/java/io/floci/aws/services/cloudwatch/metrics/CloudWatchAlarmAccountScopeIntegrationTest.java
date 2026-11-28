package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * The alarm evaluator runs outside any request, so it has to evaluate every account's alarms in
 * that account: an alarm of a non-default account reads that account's metric, changes state
 * there and notifies that account's SNS topic.
 */
@QuarkusTest
class CloudWatchAlarmAccountScopeIntegrationTest {

    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-east-1";
    private static final String JSON = "application/x-amz-json-1.0";

    @Inject
    AlarmEvaluator alarmEvaluator;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void alarmOfANonDefaultAccountChangesStateOnThatAccountsMetricAndNotifiesThatAccountsTopic() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String alarm = "account-scope-alarm-" + suffix;
        String namespace = "Custom/AccountScope" + suffix;
        String topicArn = sns("CreateTopic", "Name", "account-scope-topic-" + suffix)
                .xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        cleanup.register(() -> sns("DeleteTopic", "TopicArn", topicArn));
        assertThat(topicArn, containsString(":" + ACCOUNT + ":"));
        String queueUrl = sqs("CreateQueue", "{\"QueueName\":\"account-scope-queue-" + suffix + "\"}")
                .jsonPath().getString("QueueUrl");
        cleanup.register(() -> sqs("DeleteQueue", "{\"QueueUrl\":\"" + queueUrl + "\"}"));
        String queueArn = sqs("GetQueueAttributes", "{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"QueueArn\"]}")
                .jsonPath().getString("Attributes.QueueArn");
        sns("Subscribe", "TopicArn", topicArn, "Protocol", "sqs", "Endpoint", queueArn);

        long now = Instant.now().getEpochSecond();
        StringBuilder data = new StringBuilder();
        for (int minutesAgo = 1; minutesAgo <= 3; minutesAgo++) {
            if (!data.isEmpty()) {
                data.append(',');
            }
            data.append("{\"MetricName\":\"Load\",\"Value\":90,\"Timestamp\":").append(now - 60L * minutesAgo).append('}');
        }
        cloudWatch("PutMetricData", "{\"Namespace\":\"" + namespace + "\",\"MetricData\":[" + data + "]}");
        cloudWatch("PutMetricAlarm", """
                {"AlarmName":"%s","Namespace":"%s","MetricName":"Load","Statistic":"Maximum","Period":60,
                 "EvaluationPeriods":1,"Threshold":50,"ComparisonOperator":"GreaterThanThreshold",
                 "AlarmActions":["%s"]}
                """.formatted(alarm, namespace, topicArn));
        cleanup.register(() -> cloudWatch("DeleteAlarms", "{\"AlarmNames\":[\"" + alarm + "\"]}"));

        // evaluateNow runs one pass synchronously, so the state has changed when it returns.
        AlarmEvaluatorTestAccess.evaluateNow(alarmEvaluator);
        cloudWatch("DescribeAlarms", "{\"AlarmNames\":[\"" + alarm + "\"]}")
                .then().body("MetricAlarms[0].StateValue", equalTo("ALARM"));

        // The evaluator re-sends an alarm's actions on every pass while it stays in ALARM, so a
        // scheduled pass may add a message: wait for this account's notification, not a count.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            List<String> bodies = sqs("ReceiveMessage", "{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10}")
                    .jsonPath().getList("Messages.Body", String.class);
            assertThat(bodies, hasItem(allOf(containsString(alarm), containsString(ACCOUNT))));
        });
    }

    private static Response cloudWatch(String operation, String body) {
        return given().contentType(JSON)
                .header("X-Amz-Target", "GraniteServiceVersion20100801." + operation)
                .header("Authorization", auth(ACCOUNT, REGION, "monitoring"))
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().response();
    }

    private static Response sqs(String operation, String body) {
        return given().contentType(JSON)
                .header("X-Amz-Target", "AmazonSQS." + operation)
                .header("Authorization", auth(ACCOUNT, REGION, "sqs"))
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().response();
    }

    private static Response sns(String action, String... params) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(ACCOUNT, REGION, "sns"))
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then().statusCode(200).extract().response();
    }
}
