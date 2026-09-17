package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Metric filters over the wire: PutMetricFilter, DescribeMetricFilters, TestMetricFilter and
 * DeleteMetricFilter as the Logs API takes them, the metric a filter publishes when PutLogEvents
 * stores matching events, read back through CloudWatch GetMetricStatistics, and the filters going
 * with their log group.
 */
@QuarkusTest
class CloudWatchLogsMetricFilterIntegrationTest {

    private static final String LOGS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String LOGS_AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260908/us-east-1/logs/aws4_request";
    private static final String CW_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String CW_AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260908/us-east-1/monitoring/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response logs(String action, String body) {
        return given().contentType(LOGS_CONTENT_TYPE).header("Authorization", LOGS_AUTH)
                .header("X-Amz-Target", "Logs_20140328." + action).body(body).post("/");
    }

    private static Response cloudWatch(String action, String body) {
        return given().contentType(CW_CONTENT_TYPE).header("Authorization", CW_AUTH)
                .header("X-Amz-Target", "GraniteServiceVersion20100801." + action).body(body).post("/");
    }

    private static void createGroupAndStream(String group, String stream) {
        logs("CreateLogGroup", "{\"logGroupName\":\"" + group + "\"}").then().statusCode(200);
        logs("CreateLogStream", "{\"logGroupName\":\"" + group + "\",\"logStreamName\":\"" + stream + "\"}")
                .then().statusCode(200);
    }

    private static void putLogEvents(String group, String stream, long timestamp, List<String> messages) {
        StringBuilder events = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            events.append(i > 0 ? "," : "").append("{\"timestamp\":").append(timestamp + i)
                    .append(",\"message\":").append(quote(messages.get(i))).append("}");
        }
        logs("PutLogEvents", "{\"logGroupName\":\"" + group + "\",\"logStreamName\":\"" + stream
                + "\",\"logEvents\":[" + events + "]}").then().statusCode(200);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Response statistics(String namespace, String metric, String dimensionName, String dimensionValue,
                                       Instant from, Instant to) {
        String dimensions = dimensionName == null ? ""
                : ",\"Dimensions\":[{\"Name\":\"" + dimensionName + "\",\"Value\":\"" + dimensionValue + "\"}]";
        return cloudWatch("GetMetricStatistics", "{\"Namespace\":\"" + namespace + "\",\"MetricName\":\"" + metric
                + "\",\"Period\":60,\"StartTime\":" + from.getEpochSecond() + ",\"EndTime\":" + to.getEpochSecond()
                + ",\"Statistics\":[\"Sum\",\"SampleCount\"]" + dimensions + "}");
    }

    private static double sum(Response statistics) {
        List<Float> sums = statistics.then().statusCode(200).extract().jsonPath().getList("Datapoints.Sum", Float.class);
        return sums.stream().mapToDouble(Float::doubleValue).sum();
    }

    @Test
    void aFilterPublishesTheFieldValueWithDimensionsForMatchingEvents() {
        String group = "/it/metric-filter/access-" + System.nanoTime();
        createGroupAndStream(group, "web");
        logs("PutMetricFilter", """
                {"logGroupName":"%s","filterName":"volume","filterPattern":"[..., status_code, size]",
                 "metricTransformations":[{"metricName":"Volume","metricNamespace":"%s","metricValue":"$size",
                   "dimensions":{"Status":"$status_code"},"unit":"Bytes"}]}
                """.formatted(group, group)).then().statusCode(200);

        long now = System.currentTimeMillis();
        putLogEvents(group, "web", now, List.of(
                "127.0.0.1 - frank [10/Oct/2000:13:25:15 -0700] \"GET /index.html HTTP/1.0\" 200 1534",
                "127.0.0.1 - frank [10/Oct/2000:13:35:22 -0700] \"GET /index.html HTTP/1.0\" 500 5324",
                "127.0.0.1 - frank [10/Oct/2000:13:50:35 -0700] \"GET /index.html HTTP/1.0\" 200 4355",
                "not an access log line"));

        Instant from = Instant.ofEpochMilli(now).minusSeconds(120);
        Instant to = Instant.ofEpochMilli(now).plusSeconds(120);
        assertEquals(1534 + 4355, sum(statistics(group, "Volume", "Status", "200", from, to)), 0.001);
        assertEquals(5324, sum(statistics(group, "Volume", "Status", "500", from, to)), 0.001);
        assertEquals(0, sum(statistics(group, "Volume", "Status", "404", from, to)), 0.001);
    }

    @Test
    void nonmatchDefaultsPublishPerEventWithoutSyntheticClosingEvents() {
        String group = "/it/metric-filter/quiet-" + System.nanoTime();
        createGroupAndStream(group, "app");
        logs("PutMetricFilter", """
                {"logGroupName":"%s","filterName":"quiet","filterPattern":"ERROR",
                 "metricTransformations":[{"metricName":"ErrorCount","metricNamespace":"%s","metricValue":"3",
                   "defaultValue":7}]}
                """.formatted(group, group)).then().statusCode(200);

        // Newly ingested historical events belong to their event minute, even before filter creation.
        long time = (System.currentTimeMillis() / 60_000 - 10) * 60_000;
        putLogEvents(group, "app", time, List.of("INFO one", "INFO two", "INFO three"));
        Instant from = Instant.ofEpochMilli(time);
        Instant to = from.plusSeconds(59);
        for (int read = 0; read < 2; read++) {
            Response result = statistics(group, "ErrorCount", null, null, from, to);
            assertEquals(21, sum(result), 0.001);
            result.then().body("Datapoints", hasSize(1)).body("Datapoints[0].SampleCount", equalTo(3.0f));
        }
        statistics(group, "ErrorCount", null, null, to.plusSeconds(1), to.plusSeconds(60)).then()
                .statusCode(200).body("Datapoints", hasSize(0));
        logs("DeleteLogGroup", "{\"logGroupName\":\"" + group + "\"}").then().statusCode(200);
    }

    @Test
    void putDescribeTestAndDeleteFollowTheApi() {
        String group = "/it/metric-filter/api-" + System.nanoTime();
        createGroupAndStream(group, "s");
        logs("PutMetricFilter", """
                {"logGroupName":"%s","filterName":"latency","filterPattern":"{ $.latency = * }",
                 "metricTransformations":[{"metricName":"Latency","metricNamespace":"App","metricValue":"$.latency",
                   "unit":"Milliseconds"}]}
                """.formatted(group)).then().statusCode(200);

        logs("DescribeMetricFilters", "{\"logGroupName\":\"" + group + "\"}").then()
                .statusCode(200)
                .body("metricFilters", hasSize(1))
                .body("metricFilters[0].filterName", equalTo("latency"))
                .body("metricFilters[0].logGroupName", equalTo(group))
                .body("metricFilters[0].filterPattern", equalTo("{ $.latency = * }"))
                .body("metricFilters[0].metricTransformations[0].metricName", equalTo("Latency"))
                .body("metricFilters[0].metricTransformations[0].metricNamespace", equalTo("App"))
                .body("metricFilters[0].metricTransformations[0].metricValue", equalTo("$.latency"))
                .body("metricFilters[0].metricTransformations[0].unit", equalTo("Milliseconds"))
                .body("nextToken", nullValue());
        logs("DescribeMetricFilters", "{\"metricName\":\"Latency\",\"metricNamespace\":\"App\"}").then()
                .statusCode(200)
                .body("metricFilters.logGroupName", org.hamcrest.Matchers.hasItem(group));
        logs("DescribeLogGroups", "{\"logGroupNamePrefix\":\"" + group + "\"}").then()
                .statusCode(200)
                .body("logGroups[0].metricFilterCount", equalTo(1));

        logs("TestMetricFilter", """
                {"filterPattern":"{ $.latency = * }","logEventMessages":["{\\"latency\\": 50}","plain text"]}
                """).then()
                .statusCode(200)
                .body("matches", hasSize(1))
                .body("matches[0].eventNumber", equalTo(1))
                .body("matches[0].eventMessage", equalTo("{\"latency\": 50}"))
                .body("matches[0].extractedValues", equalTo(Map.of()));

        logs("PutMetricFilter", """
                {"logGroupName":"%s","filterName":"broken","filterPattern":"{ $.a = }",
                 "metricTransformations":[{"metricName":"A","metricNamespace":"App","metricValue":"1"}]}
                """.formatted(group)).then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
        logs("PutMetricFilter", """
                {"logGroupName":"/it/metric-filter/missing","filterName":"x","filterPattern":"ERROR",
                 "metricTransformations":[{"metricName":"A","metricNamespace":"App","metricValue":"1"}]}
                """).then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        logs("DeleteMetricFilter", "{\"logGroupName\":\"" + group + "\",\"filterName\":\"latency\"}").then()
                .statusCode(200);
        logs("DeleteMetricFilter", "{\"logGroupName\":\"" + group + "\",\"filterName\":\"latency\"}").then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        logs("DescribeMetricFilters", "{\"logGroupName\":\"" + group + "\"}").then()
                .statusCode(200)
                .body("metricFilters", hasSize(0));
    }

    @Test
    void deletingTheLogGroupDeletesItsFilters() {
        String group = "/it/metric-filter/cascade-" + System.nanoTime();
        createGroupAndStream(group, "s");
        logs("PutMetricFilter", """
                {"logGroupName":"%s","filterName":"errors","filterPattern":"ERROR",
                 "metricTransformations":[{"metricName":"Errors","metricNamespace":"Cascade","metricValue":"1"}]}
                """.formatted(group)).then().statusCode(200);

        logs("DeleteLogGroup", "{\"logGroupName\":\"" + group + "\"}").then().statusCode(200);

        logs("DescribeMetricFilters", "{\"metricName\":\"Errors\",\"metricNamespace\":\"Cascade\"}").then()
                .statusCode(200)
                .body("metricFilters.logGroupName", not(hasItem(group)));
        logs("DescribeMetricFilters", "{\"logGroupName\":\"" + group + "\"}").then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeRequiresMetricNameAndNamespaceTogether() {
        String message = "Describe Metric Filters request must contain both MetricName and MetricNamespace";

        logs("DescribeMetricFilters", "{\"metricNamespace\":\"ProbeNs\"}").then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(message));
        logs("DescribeMetricFilters", "{\"metricName\":\"ProbeMetric\"}").then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(message));
        logs("DescribeMetricFilters", "{\"metricName\":\"ProbeMetric\",\"metricNamespace\":\"ProbeNs\"}").then()
                .statusCode(200)
                .body("metricFilters", hasSize(0));
    }
}
