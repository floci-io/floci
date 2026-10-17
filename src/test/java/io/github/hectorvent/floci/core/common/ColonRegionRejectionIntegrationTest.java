package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(PartitionUnknownRegionAllowedIntegrationTest.AllowUnknownRegionsProfile.class)
class ColonRegionRejectionIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "custom:222222222222";
    private static final String DEFAULT_REGION = "us-east-1";

    @Inject
    CloudWatchLogsService logs;

    @Inject
    SqsService sqs;

    @Inject
    CloudWatchMetricsService metrics;

    @Inject
    S3Service s3;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void contentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jsonCredentialsCannotCreateAColonRegionGroup(boolean query) {
        String name = "colon-group-" + UUID.randomUUID();
        cleanup.register(() -> RequestScopes.runAs(ACCOUNT, REGION, () -> logs.describeLogGroups(name, REGION)
                .forEach(group -> logs.deleteLogGroup(group.getLogGroupName(), REGION))));
        assertTrue(RequestScopes.callAs(ACCOUNT, REGION, () -> logs.describeLogGroups(name, REGION)).isEmpty());
        request("logs", query).contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Logs_20140328.CreateLogGroup").body(Map.of("logGroupName", name))
                .when().post("/").then().statusCode(400).body("__type", equalTo("InvalidSignatureException"));
        assertTrue(RequestScopes.callAs(ACCOUNT, REGION, () -> logs.describeLogGroups(name, REGION)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queryProtocolCannotAllocateAColonRegionQueue(boolean query) {
        String name = "colon-queue-" + UUID.randomUUID();
        for (String region : List.of(REGION, DEFAULT_REGION)) {
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, region, () -> sqs.listQueues(name, region)
                    .forEach(queue -> sqs.deleteQueue(queue.getQueueUrl(), region))));
            assertTrue(RequestScopes.callAs(ACCOUNT, region, () -> sqs.listQueues(name, region)).isEmpty());
        }
        request("sqs", query).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue").formParam("QueueName", name)
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidSignatureException"));
        for (String region : List.of(REGION, DEFAULT_REGION)) {
            assertTrue(RequestScopes.callAs(ACCOUNT, region, () -> sqs.listQueues(name, region)).isEmpty());
        }
    }

    @Test
    void s3HeaderCredentialsCannotWriteAnObject() {
        String bucket = bucket();
        request("s3", false).body("rejected").when().put("/" + bucket + "/new").then().statusCode(400)
                .body("Error.Code", equalTo("AuthorizationHeaderMalformed"));
        assertObjectsUnchanged(bucket);
    }

    @Test
    void s3QueryCredentialsCannotWriteAnObject() {
        String bucket = bucket();
        request("s3", true, DEFAULT_REGION).body("control").when().put("/" + bucket + "/control")
                .then().statusCode(200);
        given().header("Authorization", PartitionMatrix.sigV4Auth(DEFAULT_REGION, "s3"))
                .when().get("/" + bucket + "/control").then().statusCode(200).body(equalTo("control"));
        request("s3", true).body("rejected").when().put("/" + bucket + "/new").then().statusCode(400)
                .body("Error.Code", equalTo("AuthorizationHeaderMalformed"));
        assertObjectsUnchanged(bucket);
    }

    @Test
    void multipartCredentialsCannotWriteAnObject() {
        String bucket = bucket();
        given().multiPart("key", "new")
                .multiPart("x-amz-credential", "AKID/20261015/" + REGION + "/s3/aws4_request")
                .multiPart("file", "new", "rejected".getBytes(StandardCharsets.UTF_8), "text/plain")
                .when().post("/" + bucket).then().statusCode(400)
                .body("Error.Code", equalTo("AuthorizationHeaderMalformed"));
        assertObjectsUnchanged(bucket);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedDeleteKeepsHistoricalColonRegionGroups(boolean query) {
        String name = "colon-history-" + UUID.randomUUID();
        logs.createLogGroupForAccount(ACCOUNT, name, null, Map.of("retained", "true"), REGION);
        cleanup.register(() -> RequestScopes.runAs(ACCOUNT, REGION, () -> logs.describeLogGroups(name, REGION)
                .forEach(group -> logs.deleteLogGroup(group.getLogGroupName(), REGION))));
        assertEquals(1, RequestScopes.callAs(ACCOUNT, REGION, () -> logs.describeLogGroups(name, REGION)).size());
        request("logs", query).contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Logs_20140328.DeleteLogGroup").body(Map.of("logGroupName", name))
                .when().post("/").then().statusCode(400).body("__type", equalTo("InvalidSignatureException"));
        assertEquals(Map.of("retained", "true"), RequestScopes.callAs(ACCOUNT, REGION,
                () -> logs.describeLogGroups(name, REGION).getFirst().getTags()));
    }

    @Test
    void anUnrecognizedSchemeCannotSmuggleAParsedColonScope() {
        String name = "colon-unrecognized-" + UUID.randomUUID();
        for (String region : List.of(REGION, DEFAULT_REGION)) {
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, region, () -> sqs.listQueues(name, region)
                    .forEach(queue -> sqs.deleteQueue(queue.getQueueUrl(), region))));
        }
        given().header("Authorization", "Bearer Credential=AKID/20261015/" + REGION + "/sqs/aws4_request")
                .contentType("application/x-www-form-urlencoded").formParam("Action", "CreateQueue")
                .formParam("QueueName", name).when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidSignatureException"));
        for (String region : List.of(REGION, DEFAULT_REGION)) {
            assertTrue(RequestScopes.callAs(ACCOUNT, region, () -> sqs.listQueues(name, region)).isEmpty());
        }
    }

    @Test
    void aCloudWatchGetCannotUseAColonCredentialWithoutAlgorithm() {
        assertCloudWatchGetRefused("AKID/20261015/" + REGION + "/monitoring/aws4_request");
    }

    @Test
    void aNestedDateCannotHideTheRegionParsedByQueryDispatch() {
        String credential = "AKID/20261015/" + DEFAULT_REGION + "/20261015/" + REGION + "/monitoring/aws4_request";
        RegionResolver resolver = new RegionResolver(DEFAULT_REGION, ACCOUNT);
        assertEquals(DEFAULT_REGION, resolver.resolveRegionFromPresignedCredential(credential));
        assertEquals(REGION, resolver.resolveRegionFromAuth("Credential=" + credential));
        assertCloudWatchGetRefused(credential);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void colonRefusalsRetainTheCborWireFormat(boolean rpcV2) throws Exception {
        String name = "colon-cbor-alarm-" + UUID.randomUUID();
        for (String region : List.of(REGION, DEFAULT_REGION)) {
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, region, () -> metrics.deleteAlarms(List.of(name), region)));
            assertTrue(RequestScopes.callAs(ACCOUNT, region, () -> metrics.describeAlarms(List.of(name), null, region))
                    .isEmpty());
        }
        ObjectMapper cbor = new ObjectMapper(new CBORFactory());
        String mediaType = rpcV2 ? "application/cbor" : "application/x-amz-cbor-1.1";
        RequestSpecification request = given().contentType(mediaType).accept(mediaType)
                .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "monitoring"))
                .body(cbor.writeValueAsBytes(Map.of("AlarmName", name, "MetricName", "metric", "Namespace", "colon",
                        "ComparisonOperator", "GreaterThanThreshold", "EvaluationPeriods", 1,
                        "Period", 60, "Statistic", "Average", "Threshold", 1)));
        if (rpcV2) {
            request.header("smithy-protocol", "rpc-v2-cbor");
        } else {
            request.header("X-Amz-Target", "GraniteServiceVersion20100801.PutMetricAlarm");
        }
        byte[] response = request.when().post(rpcV2
                ? "/service/GraniteServiceVersion20100801/operation/PutMetricAlarm" : "/").then().statusCode(400)
                .contentType(mediaType).header("x-amzn-query-error", equalTo("InvalidSignatureException;Sender"))
                .extract().asByteArray();
        JsonNode error = cbor.readTree(response);
        assertEquals("InvalidSignatureException", error.path("__type").asText());
        for (String region : List.of(REGION, DEFAULT_REGION)) {
            assertTrue(RequestScopes.callAs(ACCOUNT, region, () -> metrics.describeAlarms(List.of(name), null, region))
                    .isEmpty());
        }
    }

    private void assertCloudWatchGetRefused(String credential) {
        String name = "colon-alarm-" + UUID.randomUUID();
        for (String region : List.of(REGION, DEFAULT_REGION)) {
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, region, () -> metrics.deleteAlarms(List.of(name), region)));
            assertTrue(RequestScopes.callAs(ACCOUNT, region, () -> metrics.describeAlarms(List.of(name), null, region))
                    .isEmpty());
        }
        metricRequest(name, credential).when().get("/").then().statusCode(400)
                .contentType("application/xml")
                .body("ErrorResponse.Error.Code", equalTo("InvalidSignatureException"));
        for (String region : List.of(REGION, DEFAULT_REGION)) {
            assertTrue(RequestScopes.callAs(ACCOUNT, region, () -> metrics.describeAlarms(List.of(name), null, region))
                    .isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"logs,header-wins", "sqs,header-wins", "logs,colon-header", "sqs,colon-header",
            "logs,blank-header", "sqs,blank-header"})
    void headerPrecedenceStillSelectsWhichRegionIsChecked(String service, String mode) {
        String name = "colon-precedence-" + UUID.randomUUID();
        for (String region : List.of(REGION, DEFAULT_REGION)) {
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, region, () -> {
                if (service.equals("logs")) {
                    logs.describeLogGroups(name, region).forEach(group -> logs.deleteLogGroup(group.getLogGroupName(), region));
                } else {
                    sqs.listQueues(name, region).forEach(queue -> sqs.deleteQueue(queue.getQueueUrl(), region));
                }
            }));
        }
        boolean allowed = mode.equals("header-wins");
        String queryRegion = mode.equals("colon-header") ? DEFAULT_REGION : REGION;
        RequestSpecification request = request(service, true, queryRegion).header("Authorization",
                mode.equals("blank-header") ? "" : PartitionMatrix.sigV4Auth(allowed ? DEFAULT_REGION : REGION, service));
        if (service.equals("logs")) {
            request.contentType("application/x-amz-json-1.1").header("X-Amz-Target", "Logs_20140328.CreateLogGroup")
                    .body(Map.of("logGroupName", name));
        } else {
            request.contentType("application/x-www-form-urlencoded").formParam("Action", "CreateQueue")
                    .formParam("QueueName", name);
        }
        request.when().post("/").then().statusCode(allowed ? 200 : 400);
        for (String region : List.of(REGION, DEFAULT_REGION)) {
            int expected = allowed && region.equals(DEFAULT_REGION) ? 1 : 0;
            int actual = RequestScopes.callAs(ACCOUNT, region, () -> service.equals("logs")
                    ? logs.describeLogGroups(name, region).size() : sqs.listQueues(name, region).size());
            assertEquals(expected, actual);
        }
    }

    @Test
    void nonColonNestedCredentialsRetainTheirExistingAdmission() {
        String region = "custom-nested-region";
        String name = "noncolon-alarm-" + UUID.randomUUID();
        cleanup.register(() -> RequestScopes.runAs(ACCOUNT, region, () -> metrics.deleteAlarms(List.of(name), region)));
        metricRequest(name, "AKID/20261015/" + DEFAULT_REGION + "/20261015/" + region + "/monitoring/aws4_request")
                .when().get("/").then().statusCode(200);
        assertEquals(1, RequestScopes.callAs(ACCOUNT, region,
                () -> metrics.describeAlarms(List.of(name), null, region)).size());
    }

    private String bucket() {
        String name = "colon-bucket-" + UUID.randomUUID();
        RequestScopes.runAs(ACCOUNT, DEFAULT_REGION, () -> {
            s3.createBucket(name, DEFAULT_REGION);
            s3.putObject(name, "retained", "kept".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        });
        cleanup.register(() -> RequestScopes.runAs(ACCOUNT, DEFAULT_REGION, () -> {
            s3.deleteObject(name, "new");
            s3.deleteObject(name, "control");
            s3.deleteObject(name, "retained");
            s3.deleteBucket(name);
        }));
        given().header("Authorization", PartitionMatrix.sigV4Auth(DEFAULT_REGION, "s3"))
                .when().get("/" + name + "/retained").then().statusCode(200).body(equalTo("kept"));
        return name;
    }

    private void assertObjectsUnchanged(String bucket) {
        RequestScopes.runAs(ACCOUNT, DEFAULT_REGION, () -> {
            AwsException missing = assertThrows(AwsException.class, () -> s3.getObject(bucket, "new"));
            assertEquals("NoSuchKey", missing.getErrorCode());
        });
        given().header("Authorization", PartitionMatrix.sigV4Auth(DEFAULT_REGION, "s3"))
                .when().get("/" + bucket + "/retained").then().statusCode(200).body(equalTo("kept"));
    }

    private static RequestSpecification metricRequest(String name, String credential) {
        return given().queryParam("Action", "PutMetricAlarm").queryParam("Version", "2010-08-01")
                .queryParam("X-Amz-Credential", credential)
                .queryParam("AlarmName", name).queryParam("ComparisonOperator", "GreaterThanThreshold")
                .queryParam("EvaluationPeriods", "1").queryParam("MetricName", "QueueDepth")
                .queryParam("Namespace", "ColonScope").queryParam("Threshold", "10")
                .queryParam("Period", "60").queryParam("Statistic", "Average");
    }

    private static RequestSpecification request(String service, boolean query) {
        return request(service, query, REGION);
    }

    private static RequestSpecification request(String service, boolean query, String region) {
        RequestSpecification request = given();
        if (query) {
            String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
                    .format(Instant.now());
            request.queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                    .queryParam("X-Amz-Credential", "AKID/" + date.substring(0, 8) + "/" + region
                            + "/" + service + "/aws4_request")
                    .queryParam("X-Amz-Date", date).queryParam("X-Amz-Expires", "3600")
                    .queryParam("X-Amz-SignedHeaders", "host").queryParam("X-Amz-Signature", "abc");
        } else {
            request.header("Authorization", PartitionMatrix.sigV4Auth(region, service));
        }
        return request;
    }
}
