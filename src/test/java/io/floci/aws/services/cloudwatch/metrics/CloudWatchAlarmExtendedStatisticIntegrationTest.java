package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** PutMetricAlarm and DescribeAlarms preserve ExtendedStatistic across Query, JSON, and the Java SDK. */
@QuarkusTest
class CloudWatchAlarmExtendedStatisticIntegrationTest {

    private static final String SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20261121/us-east-1/monitoring/aws4_request";
    private static final String XML_ALARM =
            "DescribeAlarmsResponse.DescribeAlarmsResult.MetricAlarms.member[0].";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private CloudWatchClient cloudWatch;
    private String suffix;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() {
        cloudWatch = AwsTestClients.create(CloudWatchClient.builder(), "test", Region.US_EAST_1);
        cleanup.register(cloudWatch::close);
        suffix = Long.toString(System.nanoTime(), 36);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothProtocolsRoundTripExtendedStatistic(boolean json) throws Exception {
        String name = "wire-ext-stat-" + (json ? "json-" : "query-") + suffix;
        cleanup.register(() -> CloudWatchTestRequests.deleteAlarmJson(baseUri, SCOPE, name));

        Map<String, Object> putBody = new LinkedHashMap<>();
        putBody.put("AlarmName", name);
        putBody.put("Namespace", "AWS/ApplicationELB");
        putBody.put("MetricName", "TargetResponseTime");
        putBody.put("ExtendedStatistic", "p99");
        putBody.put("Period", 300);
        putBody.put("EvaluationPeriods", 1);
        putBody.put("Threshold", 1);
        putBody.put("ComparisonOperator", "GreaterThanThreshold");
        CloudWatchTestRequests.call(baseUri, SCOPE, json, "PutMetricAlarm", putBody).then().statusCode(200);

        Response described = CloudWatchTestRequests.call(baseUri, SCOPE, json, "DescribeAlarms",
                Map.of("AlarmNames", List.of(name)));
        described.then().statusCode(200);
        if (json) {
            assertEquals("p99", described.jsonPath().getString("MetricAlarms[0].ExtendedStatistic"));
            assertNull(described.jsonPath().get("MetricAlarms[0].Statistic"));
        } else {
            assertEquals("p99", described.xmlPath().getString(XML_ALARM + "ExtendedStatistic"));
            assertFalse(described.asString().contains("<Statistic>"));
        }

        Map<String, Object> updateBody = new LinkedHashMap<>(putBody);
        updateBody.remove("ExtendedStatistic");
        updateBody.put("Statistic", "Average");
        CloudWatchTestRequests.call(baseUri, SCOPE, json, "PutMetricAlarm", updateBody).then().statusCode(200);

        Response updated = CloudWatchTestRequests.call(baseUri, SCOPE, json, "DescribeAlarms",
                Map.of("AlarmNames", List.of(name)));
        updated.then().statusCode(200);
        if (json) {
            assertEquals("Average", updated.jsonPath().getString("MetricAlarms[0].Statistic"));
            assertNull(updated.jsonPath().get("MetricAlarms[0].ExtendedStatistic"));
        } else {
            assertEquals("Average", updated.xmlPath().getString(XML_ALARM + "Statistic"));
            assertFalse(updated.asString().contains("<ExtendedStatistic>"));
        }
    }

    @Test
    void sdkRoundTripsExtendedStatistic() {
        String name = "sdk-ext-stat-" + suffix;
        cleanup.register(() -> cloudWatch.deleteAlarms(r -> r.alarmNames(name)));

        cloudWatch.putMetricAlarm(r -> r.alarmName(name)
                .namespace("AWS/ApplicationELB")
                .metricName("TargetResponseTime")
                .extendedStatistic("p99")
                .period(300)
                .evaluationPeriods(1)
                .threshold(1.0)
                .comparisonOperator("GreaterThanThreshold"));

        MetricAlarm stored = cloudWatch.describeAlarms(r -> r.alarmNames(name)).metricAlarms().getFirst();
        assertEquals("p99", stored.extendedStatistic());
        assertNull(stored.statisticAsString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothProtocolsRejectStatisticAndExtendedStatisticTogether(boolean json) throws Exception {
        String name = "wire-both-stat-" + (json ? "json-" : "query-") + suffix;
        cleanup.register(() -> CloudWatchTestRequests.deleteAlarmJson(baseUri, SCOPE, name));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("AlarmName", name);
        body.put("Namespace", "AWS/ApplicationELB");
        body.put("MetricName", "TargetResponseTime");
        body.put("Statistic", "Average");
        body.put("ExtendedStatistic", "p99");
        body.put("Period", 300);
        body.put("EvaluationPeriods", 1);
        body.put("Threshold", 1);
        body.put("ComparisonOperator", "GreaterThanThreshold");

        Response refused = CloudWatchTestRequests.call(baseUri, SCOPE, json, "PutMetricAlarm", body);
        refused.then().statusCode(400);
        assertEquals("ValidationError",
                json ? refused.jsonPath().getString("__type")
                        : refused.xmlPath().getString("ErrorResponse.Error.Code"));
        assertEquals("PutMetricAlarm request can not be made with both Statistic and ExtendedStatistic parameters. "
                + "Only one of them should be present",
                json ? refused.jsonPath().getString("message")
                        : refused.xmlPath().getString("ErrorResponse.Error.Message"));
        assertEquals(0, cloudWatch.describeAlarms(r -> r.alarmNames(name)).metricAlarms().size());
    }
}
