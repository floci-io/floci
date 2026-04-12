package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ServiceEnablementIntegrationTest.DisabledServicesProfile.class)
class ServiceEnablementIntegrationTest {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void acmTargetedRequestsAreRejected() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "CertificateManager.ListCertificates")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .body("__type", equalTo("ServiceNotAvailableException"))
            .body("message", equalTo("Service acm is not enabled."));
    }

    @Test
    void ecsTargetedRequestsAreRejected() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.ListClusters")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .body("__type", equalTo("ServiceNotAvailableException"))
            .body("message", equalTo("Service ecs is not enabled."));
    }

    @Test
    void sqsQueueUrlJsonRequestsAreRejectedWhenServiceDisabled() {
        given()
            .contentType("application/x-amz-json-1.0")
            .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
            .body("""
                {"QueueUrl":"http://localhost:4566/000000000000/disabled-queue","AttributeNames":["All"]}
                """)
        .when()
            .post("/000000000000/disabled-queue")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .body("__type", equalTo("ServiceNotAvailableException"))
            .body("message", equalTo("Service sqs is not enabled."));
    }

    @Test
    void sqsQueryRequestsReturnXmlWhenServiceDisabled() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", authorization("sqs"))
            .formParam("Action", "ListQueues")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .contentType("application/xml")
            .body(containsString("<Code>ServiceNotAvailableException</Code>"))
            .body(containsString("<Message>Service sqs is not enabled.</Message>"));
    }

    @Test
    void dynamodbTargetedCborRequestsReturnCborErrors() throws Exception {
        JsonNode body = cborBody(
                given()
                    .contentType("application/cbor")
                    .accept("application/cbor")
                    .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
                    .body(CBOR_MAPPER.writeValueAsBytes(Map.of()))
                .when()
                    .post("/")
                .then()
                    .statusCode(400)
                    .contentType("application/cbor")
                    .extract().asByteArray()
        );

        assertEquals("ServiceNotAvailableException", body.get("__type").asText());
        assertEquals("Service dynamodb is not enabled.", body.get("message").asText());
    }

    @Test
    void dynamodbSmithyCborRequestsReturnCborErrors() throws Exception {
        JsonNode body = cborBody(
                given()
                    .contentType("application/cbor")
                    .accept("application/cbor")
                    .header("Authorization", authorization("dynamodb"))
                    .body(CBOR_MAPPER.writeValueAsBytes(Map.of()))
                .when()
                    .post("/service/DynamoDB/operation/ListTables")
                .then()
                    .statusCode(400)
                    .contentType("application/cbor")
                    .extract().asByteArray()
        );

        assertEquals("ServiceNotAvailableException", body.get("__type").asText());
        assertEquals("Service dynamodb is not enabled.", body.get("message").asText());
    }

    @Test
    void signedLambdaGetRequestsReturnJsonWhenServiceDisabled() {
        given()
            .header("Authorization", authorization("lambda"))
        .when()
            .get("/2015-03-31/functions")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .body("__type", equalTo("ServiceNotAvailableException"))
            .body("message", equalTo("Service lambda is not enabled."));
    }

    @Test
    void signedOpenSearchGetRequestsReturnJsonWhenServiceDisabled() {
        given()
            .header("Authorization", authorization("es"))
        .when()
            .get("/2021-01-01/domain")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .body("__type", equalTo("ServiceNotAvailableException"))
            .body("message", equalTo("Service es is not enabled."));
    }

    private static JsonNode cborBody(byte[] body) throws Exception {
        return CBOR_MAPPER.readTree(body);
    }

    private static String authorization(String service) {
        return "AWS4-HMAC-SHA256 Credential=test/20260411/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=deadbeef";
    }

    public static final class DisabledServicesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.acm.enabled", "false",
                    "floci.services.dynamodb.enabled", "false",
                    "floci.services.ecs.enabled", "false",
                    "floci.services.lambda.enabled", "false",
                    "floci.services.opensearch.enabled", "false",
                    "floci.services.sqs.enabled", "false"
            );
        }
    }
}
