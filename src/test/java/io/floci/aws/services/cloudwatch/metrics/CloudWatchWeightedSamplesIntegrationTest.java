package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.EntityMetricData;
import software.amazon.awssdk.services.cloudwatch.model.InvalidParameterCombinationException;
import software.amazon.awssdk.services.cloudwatch.model.InvalidParameterValueException;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudWatchWeightedSamplesIntegrationTest {

    private static final String ACCOUNT = "000000000358";
    private static final Dimension DIMENSION = Dimension.builder().name("Route").value("weighted").build();

    @ParameterizedTest
    @CsvSource({"us-east-1,false", "us-east-1,true", "us-gov-west-1,false", "us-gov-west-1,true"})
    void sdkRetainsWeightedAndDefaultCounts(String regionName, boolean entity) {
        Instant now = Instant.now();
        String namespace = "WeightedSamples/" + UUID.randomUUID();
        List<MetricDatum> datums = List.of(
                metric("Weighted", now).values(2.0, 5.0).counts(3.0, 2.0).build(),
                metric("DefaultCounts", now).values(2.0, 5.0).build(),
                metric("ZeroWeight", now).values(7.0, 11.0).counts(0.0, 0.0).build());
        PutMetricDataRequest.Builder request = PutMetricDataRequest.builder().namespace(namespace);
        if (entity) {
            request.entityMetricData(EntityMetricData.builder().metricData(datums).build())
                    .strictEntityValidation(false);
        } else {
            request.metricData(datums);
        }
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), ACCOUNT, Region.of(regionName))) {
            client.putMetricData(request.build());
            assertStatistics(client, namespace, "Weighted", now, 16, 5, 2, 5);
            assertStatistics(client, namespace, "DefaultCounts", now, 7, 2, 2, 5);
            Datapoint zero = statistics(client, namespace, "ZeroWeight", now);
            assertEquals(0, zero.sum());
            assertEquals(0, zero.sampleCount());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queryRetainsWeightedSamplesAndMetadata(boolean entity) {
        Instant now = Instant.now();
        String namespace = "WeightedQuery/" + UUID.randomUUID();
        String prefix = entity ? "EntityMetricData.member.1.MetricData.member.1."
                : "MetricData.member.1.";
        query().formParam("Namespace", namespace)
                .formParam("StrictEntityValidation", "false")
                .formParam(prefix + "MetricName", "Weighted")
                .formParam(prefix + "Timestamp", now.toString())
                .formParam(prefix + "Unit", "Milliseconds")
                .formParam(prefix + "Dimensions.member.1.Name", DIMENSION.name())
                .formParam(prefix + "Dimensions.member.1.Value", DIMENSION.value())
                .formParam(prefix + "Values.member.1", "2")
                .formParam(prefix + "Values.member.2", "5")
                .formParam(prefix + "Counts.member.1", "3")
                .formParam(prefix + "Counts.member.2", "2")
                .when().post("/").then().statusCode(200);
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertStatistics(client, namespace, "Weighted", now, 16, 5, 2, 5);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sdkRejectsInvalidCountsBeforeStoringTheBatch(String regionName) {
        Instant now = Instant.now();
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), ACCOUNT, Region.of(regionName))) {
            for (List<Double> counts : List.of(List.of(1.0), List.of(-1.0, 2.0))) {
                String namespace = "WeightedInvalid/" + UUID.randomUUID();
                InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class,
                        () -> client.putMetricData(request -> request.namespace(namespace).metricData(
                                metric("First", now).value(7.0).build(),
                                metric("Invalid", now).values(2.0, 5.0).counts(counts).build())));
                assertEquals(400, error.statusCode());
                assertTrue(error.getMessage().contains("Counts"));
                assertTrue(client.listMetrics(request -> request.namespace(namespace)).metrics().isEmpty());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,1", "false,3", "true,1", "true,3"})
    void sdkReportsMismatchedCountsAtTheDatumPath(boolean entity, int countLength) {
        Instant now = Instant.now();
        String namespace = "WeightedCountMismatch/" + UUID.randomUUID();
        List<Double> counts = countLength == 1 ? List.of(1.0) : List.of(1.0, 2.0, 3.0);
        List<MetricDatum> datums = List.of(
                metric("First", now).value(7.0).build(),
                metric("Invalid", now).values(2.0, 5.0).counts(counts).build());
        PutMetricDataRequest.Builder request = PutMetricDataRequest.builder().namespace(namespace);
        if (entity) {
            request.entityMetricData(
                    EntityMetricData.builder().metricData(metric("FirstEntity", now).value(1.0).build()).build(),
                    EntityMetricData.builder().metricData(datums).build()).strictEntityValidation(false);
        } else {
            request.metricData(datums);
        }
        String memberPath = entity ? "EntityMetricData.member.2.MetricData.member.2" : "MetricData.member.2";
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class,
                    () -> client.putMetricData(request.build()));
            assertEquals(400, error.statusCode());
            assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());
            assertEquals("The parameters " + memberPath + ".Values and " + memberPath
                    + ".Counts must be of the same size.", error.awsErrorDetails().errorMessage());
            assertTrue(client.listMetrics(list -> list.namespace(namespace)).metrics().isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,1", "false,3", "true,1", "true,3"})
    void queryReportsMismatchedCountsAtTheDatumPath(boolean entity, int countLength) {
        String namespace = "WeightedQueryCountMismatch/" + UUID.randomUUID();
        String listPath = entity ? "EntityMetricData.member.2.MetricData" : "MetricData";
        String memberPath = listPath + ".member.2";
        RequestSpecification request = query().formParam("Namespace", namespace)
                .formParam("StrictEntityValidation", "false")
                .formParam(listPath + ".member.1.MetricName", "First")
                .formParam(listPath + ".member.1.Value", "7")
                .formParam(memberPath + ".MetricName", "Invalid")
                .formParam(memberPath + ".Values.member.1", "2")
                .formParam(memberPath + ".Values.member.2", "5");
        if (entity) {
            request.formParam("EntityMetricData.member.1.MetricData.member.1.MetricName", "FirstEntity")
                    .formParam("EntityMetricData.member.1.MetricData.member.1.Value", "1");
        }
        for (int index = 1; index <= countLength; index++) {
            request.formParam(memberPath + ".Counts.member." + index, Integer.toString(index));
        }
        request.when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
                .body("ErrorResponse.Error.Message", equalTo("The parameters " + memberPath
                        + ".Values and " + memberPath + ".Counts must be of the same size."));
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertTrue(client.listMetrics(list -> list.namespace(namespace)).metrics().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sdkRejectsConflictingValuesBeforeStoringTheBatch(String regionName) {
        Instant now = Instant.now();
        String namespace = "WeightedConflict/" + UUID.randomUUID();
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), ACCOUNT, Region.of(regionName))) {
            InvalidParameterCombinationException error = assertThrows(InvalidParameterCombinationException.class,
                    () -> client.putMetricData(request -> request.namespace(namespace).metricData(
                            metric("First", now).value(7.0).build(),
                            metric("Invalid", now).value(2.0).values(2.0, 5.0).build())));
            assertEquals(400, error.statusCode());
            assertTrue(error.getMessage().contains("Values cannot be combined"));
            assertTrue(client.listMetrics(request -> request.namespace(namespace)).metrics().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queryRejectsMissingValueIndicesBeforeStoringTheBatch(boolean entity) {
        String namespace = "WeightedQueryInvalid/" + UUID.randomUUID();
        String prefix = entity ? "EntityMetricData.member.1.MetricData.member.1."
                : "MetricData.member.2.";
        query().formParam("Namespace", namespace)
                .formParam("MetricData.member.1.MetricName", "First")
                .formParam("MetricData.member.1.Value", "7")
                .formParam(prefix + "MetricName", "Invalid")
                .formParam(prefix + "Values.member.2", "5")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
                .body("ErrorResponse.Error.Message", containsString("Values has a missing member"));
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertTrue(client.listMetrics(request -> request.namespace(namespace)).metrics().isEmpty());
        }
    }

    private static MetricDatum.Builder metric(String name, Instant now) {
        return MetricDatum.builder().metricName(name).timestamp(now)
                .unit("Milliseconds").dimensions(DIMENSION);
    }

    private static Datapoint statistics(CloudWatchClient client, String namespace, String name, Instant now) {
        List<Datapoint> points = client.getMetricStatistics(request -> request.namespace(namespace)
                .metricName(name).dimensions(DIMENSION).unit("Milliseconds")
                .startTime(now.minusSeconds(120)).endTime(now.plusSeconds(120)).period(60)
                .statisticsWithStrings("Sum", "SampleCount", "Minimum", "Maximum", "Average")).datapoints();
        assertEquals(1, points.size());
        return points.getFirst();
    }

    private static void assertStatistics(CloudWatchClient client, String namespace, String name, Instant now,
                                         double sum, double count, double minimum, double maximum) {
        Datapoint point = statistics(client, namespace, name, now);
        assertEquals(sum, point.sum());
        assertEquals(count, point.sampleCount());
        assertEquals(minimum, point.minimum());
        assertEquals(maximum, point.maximum());
        assertEquals(sum / count, point.average());
        assertEquals("Milliseconds", point.unitAsString());
    }

    private static RequestSpecification query() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261119/us-east-1/monitoring/aws4_request")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PutMetricData").formParam("Version", "2010-08-01");
    }
}
