package io.floci.aws.services.cloudwatch.metrics;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.config;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class CloudWatchConsolePutMetricDataIntegrationTest {

    private static final String JSON = "application/x-amz-json-1.0";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261115/us-east-1/monitoring/aws4_request";

    @BeforeAll
    static void registerParser() {
        RestAssured.registerParser(JSON, Parser.JSON);
    }

    private static RequestSpecification request(String target) {
        return given().header("Authorization", AUTH)
                .contentType(JSON)
                .config(config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(JSON, ContentType.TEXT)))
                .header("X-Amz-Target", target);
    }

    private static RequestSpecification put() {
        return request("CloudWatchVersion20130116.PutMetricData");
    }

    private static String namespace() {
        return "LegacyPut/" + UUID.randomUUID();
    }

    private static void assertEmpty(String namespace) {
        request("GraniteServiceVersion20100801.ListMetrics")
                .body("{\"Namespace\":\"" + namespace + "\"}")
                .when().post("/")
                .then().statusCode(200).body("Metrics", empty());
    }

    private static void assertSum(String namespace, String metric, long timestamp, float sum) {
        request("GraniteServiceVersion20100801.GetMetricStatistics")
                .body("""
                        {"Namespace":"%s","MetricName":"%s","StartTime":%d,"EndTime":%d,
                         "Period":60,"Statistics":["Sum"]}
                        """.formatted(namespace, metric, timestamp - 120, timestamp + 120))
                .when().post("/")
                .then().statusCode(200).body("Datapoints.Sum", hasItem(sum));
    }

    @Test
    void sharedDatumShapesAreStoredAndReadableThroughTheStandardProtocol() {
        String namespace = namespace();
        long timestamp = Instant.now().getEpochSecond();
        put().body("""
                {"Namespace":"%s","MetricData":[
                  {"MetricName":"Single","Value":7,"Unit":"Count","Timestamp":%d},
                   {"MetricName":"Statistics","Timestamp":%d,
                    "StatisticValues":{"SampleCount":2,"Sum":6,"Minimum":2,"Maximum":4}}]}
                """.formatted(namespace, timestamp, timestamp))
                .when().post("/")
                .then().statusCode(200);

        assertSum(namespace, "Single", timestamp, 7);
        assertSum(namespace, "Statistics", timestamp, 6);
        request("GraniteServiceVersion20100801.GetMetricStatistics")
                .body("""
                        {"Namespace":"%s","MetricName":"Statistics","StartTime":%d,"EndTime":%d,
                         "Period":60,"Statistics":["Minimum","Maximum","SampleCount"]}
                        """.formatted(namespace, timestamp - 120, timestamp + 120))
                .when().post("/")
                .then().statusCode(200)
                .body("Datapoints.Minimum", hasItem(2f))
                .body("Datapoints.Maximum", hasItem(4f))
                .body("Datapoints.SampleCount", hasItem(2f));
    }

    @Test
    void unsupportedTopLevelShapeIsRejectedWithoutStoringTheValidDatum() {
        String namespace = namespace();
        put().body("""
                {"Namespace":"%s","MetricData":[{"MetricName":"Valid","Value":7}],
                 "EntityMetricData":[{"MetricData":[{"MetricName":"Entity","Value":2}]}]}
                """.formatted(namespace))
                .when().post("/")
                .then().statusCode(400).body("__type", equalTo("UnsupportedOperation"));
        assertEmpty(namespace);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"StorageResolution\":1", "\"Values\":[2,5]", "\"Counts\":[2,1]"})
    void unsupportedDatumShapeRejectsTheEntireRequestBeforeAnyMetricIsStored(String unsupported) {
        String namespace = namespace();
        put().body("""
                {"Namespace":"%s","MetricData":[{"MetricName":"Valid","Value":7},
                  {"MetricName":"Unsupported","Value":2,%s}]}
                """.formatted(namespace, unsupported))
                .when().post("/")
                .then().statusCode(400).body("__type", equalTo("UnsupportedOperation"));
        assertEmpty(namespace);
    }

    @Test
    void unsetUnsupportedDatumMembersKeepTheExistingLegacyNullAndFalseSemantics() {
        String namespace = namespace();
        long timestamp = Instant.now().getEpochSecond();
        put().body("""
                {"Namespace":"%s","MetricData":[
                  {"MetricName":"NullMember","Value":3,"Timestamp":%d,"Unsupported":null,
                   "StatisticValues":null,"Dimensions":null,"Unit":null},
                  {"MetricName":"FalseMember","Value":4,"Timestamp":%d,"Unsupported":false}]}
                """.formatted(namespace, timestamp, timestamp))
                .when().post("/")
                .then().statusCode(200);
        assertSum(namespace, "NullMember", timestamp, 3);
        assertSum(namespace, "FalseMember", timestamp, 4);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "\"wrong\"",
            "null",
            "[]",
            "[7]",
            "[{\"MetricName\":\"Bad\",\"Value\":[1,2]}]",
            "[{\"MetricName\":\"Bad\",\"Value\":{}}]",
            "[{\"MetricName\":\"Bad\",\"Value\":\"2\"}]",
            "[{\"MetricName\":\"Bad\",\"Value\":true}]"
    })
    void malformedBatchIsRejectedWithoutPublishingMetrics(String data) {
        String namespace = namespace();
        put().body("{\"Namespace\":\"" + namespace + "\",\"MetricData\":" + data + "}")
                .when().post("/")
                .then().statusCode(400).body("__type", equalTo("UnsupportedOperation"));
        assertEmpty(namespace);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"Value\":[1,2]",
            "\"Value\":1,\"Dimensions\":{}",
            "\"Value\":1,\"Dimensions\":[7]",
            "\"Value\":1,\"Dimensions\":[{\"Name\":12,\"Value\":\"x\"}]",
            "\"Value\":1,\"Dimensions\":[{\"Name\":\"x\",\"Value\":12}]",
            "\"Value\":1,\"Dimensions\":[{\"Name\":\"x\"}]",
            "\"Value\":1,\"Dimensions\":[{\"Name\":\"x\",\"Value\":null}]",
            "\"StatisticValues\":{\"SampleCount\":2,\"Sum\":6,\"Minimum\":2}",
            "\"StatisticValues\":{\"Sum\":6,\"Minimum\":2,\"Maximum\":4}",
            "\"StatisticValues\":{\"SampleCount\":2,\"Minimum\":2,\"Maximum\":4}",
            "\"StatisticValues\":{\"SampleCount\":2,\"Sum\":6,\"Maximum\":4}",
            "\"StatisticValues\":{\"SampleCount\":2,\"Sum\":6,\"Minimum\":2,\"Maximum\":\"4\"}",
            "\"StatisticValues\":[]",
            "\"StatisticValues\":{\"SampleCount\":0,\"Sum\":0,\"Minimum\":2,\"Maximum\":4}",
            "\"Value\":7,\"StatisticValues\":{\"SampleCount\":2,\"Sum\":6,\"Minimum\":2,\"Maximum\":4}",
            "\"Value\":1e1000",
            "\"Value\":1e109",
            "\"Value\":-1e109",
            "\"StatisticValues\":{\"SampleCount\":2,\"Sum\":1e109,\"Minimum\":2,\"Maximum\":4}",
            "\"Value\":1,\"Timestamp\":[]",
            "\"Value\":1,\"Timestamp\":\"wrong\"",
            "\"Value\":1,\"Timestamp\":1e30",
            "\"Value\":1,\"Unit\":7"
    })
    void malformedDatumRejectsTheWholeBatchBeforePublishingTheValidDatum(String fields) {
        String namespace = namespace();
        put().body("""
                {"Namespace":"%s","MetricData":[{"MetricName":"Valid","Value":7},
                  {"MetricName":"Bad",%s}]}
                """.formatted(namespace, fields))
                .when().post("/")
                .then().statusCode(400).body("__type", equalTo("UnsupportedOperation"));
        assertEmpty(namespace);
    }

    @Test
    void stringDimensionsKeepTheirIdentity() {
        String namespace = namespace();
        long timestamp = Instant.now().getEpochSecond();
        put().body("""
                {"Namespace":"%s","MetricData":[{"MetricName":"Dimensioned","Value":5,"Timestamp":"%s",
                  "Dimensions":[{"Name":"Worker","Value":"12"}]}]}
                """.formatted(namespace, Instant.ofEpochSecond(timestamp)))
                .when().post("/")
                .then().statusCode(200);
        request("GraniteServiceVersion20100801.GetMetricStatistics")
                .body("""
                        {"Namespace":"%s","MetricName":"Dimensioned","StartTime":%d,"EndTime":%d,
                         "Period":60,"Statistics":["Sum"],"Dimensions":[{"Name":"Worker","Value":"12"}]}
                        """.formatted(namespace, timestamp - 120, timestamp + 120))
                .when().post("/")
                .then().statusCode(200).body("Datapoints.Sum", hasItem(5f));
    }
}
