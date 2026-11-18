package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.InvalidParameterCombinationException;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PutMetricAlarm refuses {@code EvaluationInterval} on a MetricName alarm, so a stack that sets it rolls back. */
@QuarkusTest
class CloudWatchPutMetricAlarmEvaluationIntervalIntegrationTest {

    private static final String SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20261117/us-east-1/monitoring/aws4_request";
    private static final String COMBINATION =
            "EvaluationInterval cannot be specified for alarms configured with MetricName or Metrics.";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private CloudWatchClient cloudWatch;
    private CloudFormationClient cfn;
    private String suffix;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() {
        cloudWatch = AwsTestClients.create(CloudWatchClient.builder(), "test", Region.US_EAST_1);
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        suffix = Long.toString(System.nanoTime(), 36);
    }

    @AfterEach
    void closeClients() {
        cloudWatch.close();
        cfn.close();
    }

    @ParameterizedTest
    @CsvSource({"false, InvalidParameterCombination", "true, InvalidParameterCombinationException"})
    void bothProtocolsRefuseTheIntervalOnAMetricNameAlarm(boolean json, String code) throws Exception {
        String name = "wire-interval-" + suffix;
        cleanup.register(() -> cloudWatch.deleteAlarms(r -> r.alarmNames(name)));
        Map<String, Object> body = new LinkedHashMap<>(Map.of("AlarmName", name, "Namespace", "MyApp",
                "MetricName", "MyMetric", "Statistic", "Sum", "Period", 60, "EvaluationPeriods", 1, "Threshold", 1,
                "ComparisonOperator", "GreaterThanOrEqualToThreshold", "EvaluationInterval", 60));

        Response refused = CloudWatchTestRequests.call(baseUri, SCOPE, json, "PutMetricAlarm", body);

        refused.then().statusCode(400);
        assertEquals(code, json ? refused.jsonPath().getString("__type")
                : refused.xmlPath().getString("ErrorResponse.Error.Code"));
        assertEquals(COMBINATION, json ? refused.jsonPath().getString("message")
                : refused.xmlPath().getString("ErrorResponse.Error.Message"));
        assertEquals(0, cloudWatch.describeAlarms(r -> r.alarmNames(name)).metricAlarms().size());
    }

    @Test
    void theSdkRaisesTheTypedExceptionForTheIntervalOnAMetricNameAlarm() {
        InvalidParameterCombinationException refused = assertThrows(InvalidParameterCombinationException.class,
                () -> cloudWatch.putMetricAlarm(r -> r.alarmName("sdk-interval-" + suffix).metricName("MyMetric")
                        .evaluationInterval(60)));

        assertEquals(COMBINATION, refused.awsErrorDetails().errorMessage());
    }

    @Test
    void anAlarmWithEvaluationCriteriaTakesAnIntervalAndDoesNotReturnIt() {
        String name = "sdk-interval-criteria-" + suffix;
        cleanup.register(() -> cloudWatch.deleteAlarms(r -> r.alarmNames(name)));

        cloudWatch.putMetricAlarm(r -> r.alarmName(name).evaluationInterval(60)
                .evaluationCriteria(c -> c.promQLCriteria(p -> p.query("up"))));

        MetricAlarm stored = cloudWatch.describeAlarms(r -> r.alarmNames(name)).metricAlarms().getFirst();
        assertEquals(name, stored.alarmName());
        assertNull(stored.evaluationInterval());
    }

    @ParameterizedTest
    @ValueSource(strings = {"60", "99999999999"})
    void aStackWhoseAlarmSetsTheIntervalRollsBackWithTheReason(String interval) {
        String name = "stack-interval-" + suffix;
        String stack = "interval-create-" + suffix;
        String reason = "60".equals(interval) ? COMBINATION : "Value '" + interval + "' at 'evaluationInterval' "
                + "failed to satisfy constraint: Member must have value less than or equal to 3600";

        CfnStackWaits.StackState state = deploy(stack, alarmTemplate(name, 1, interval));

        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertEquals(0, cloudWatch.describeAlarms(r -> r.alarmNames(name)).metricAlarms().size());
        assertTrue(cfn.describeStackEvents(r -> r.stackName(stack)).stackEvents().stream()
                .anyMatch(e -> "CREATE_FAILED".equals(e.resourceStatusAsString())
                        && e.resourceStatusReason().contains(reason)), "no CREATE_FAILED event with: " + reason);
    }

    @Test
    void anUpdateThatAddsTheIntervalRollsBackAndLeavesTheAlarmAsItWas() {
        String name = "stack-interval-update-" + suffix;
        String stack = "interval-update-" + suffix;
        CfnStackWaits.StackState created = deploy(stack, alarmTemplate(name, 1, null));
        assertEquals("CREATE_COMPLETE", created.status(), created.reason());

        CfnStackWaits.updateStack(cfn, stack, alarmTemplate(name, 5, "60"), "UPDATE_ROLLBACK_COMPLETE");

        assertTrue(CfnStackWaits.failedResourceReason(stack).contains(COMBINATION));
        assertEquals(1.0, cloudWatch.describeAlarms(r -> r.alarmNames(name)).metricAlarms().getFirst().threshold());
    }

    private CfnStackWaits.StackState deploy(String stack, String template) {
        cleanup.register(() -> cfn.deleteStack(r -> r.stackName(stack)));
        cfn.createStack(r -> r.stackName(stack).templateBody(template));
        return CfnStackWaits.awaitTerminal(stack);
    }

    private static String alarmTemplate(String name, int threshold, String interval) {
        return """
                {"Resources": {"Alarm": {"Type": "AWS::CloudWatch::Alarm", "Properties": {
                   "AlarmName": "%s", "Namespace": "MyApp", "MetricName": "MyMetric",
                   "Statistic": "Sum", "Period": 60, "EvaluationPeriods": 1, "Threshold": %d,
                   "ComparisonOperator": "GreaterThanOrEqualToThreshold"%s}}}}""".formatted(name, threshold,
                interval == null ? "" : ", \"EvaluationInterval\": " + interval);
    }
}
