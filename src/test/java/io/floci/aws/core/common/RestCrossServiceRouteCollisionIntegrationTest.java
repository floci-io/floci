package io.floci.aws.core.common;

import io.floci.aws.testing.PartitionMatrix;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that REST calls for unimplemented operations signed for a service sharing a path
 * with another service's controller are rejected with 404 UnknownOperationException before
 * reaching the unrelated controller (issue #6426).
 */
@QuarkusTest
class RestCrossServiceRouteCollisionIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void querySignedCollidingRestCallIsSafelyRejectedWith404(String authorization) {
        RequestSpecification spec = given()
            .queryParam("X-Amz-Credential", "test/20260707/us-east-1/appsync/aws4_request")
            .contentType("application/json")
            .body("{}");
        if (authorization != null) {
            spec.header("Authorization", authorization);
        }
        spec.when()
            .post("/v2/apis")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .body("__type", equalTo("UnknownOperationException"))
            .body("message", equalTo("Unknown operation: POST /v2/apis"));
    }

    @ParameterizedTest
    @CsvSource({
            "appsync, POST, /v2/apis",
            "appsync, GET, /v2/apis",
            "appsync, GET, /v2/apis/api-123",
            "appsync, DELETE, /v2/apis/api-123",
            "app-integrations, POST, /applications",
            "app-integrations, GET, /applications",
            "app-integrations, GET, /applications/app-123",
            "app-integrations, DELETE, /applications/app-123",
            "servicecatalog, POST, /applications",
            "servicecatalog, GET, /applications",
            "servicecatalog, GET, /applications/app-123",
            "servicecatalog, DELETE, /applications/app-123",
            "mq, POST, /v1/configurations",
            "mq, GET, /v1/configurations",
            "mq, GET, /v1/configurations/c-123",
            "mq, PUT, /v1/configurations/c-123",
            "mq, DELETE, /v1/configurations/c-123",
            "securityhub, GET, /associations",
            "securityhub, POST, /associations",
            "macie2, GET, /jobs/job-123",
            "connect, POST, /rules/inst-123",
            "connect, GET, /rules/inst-123",
            "connect, GET, /workspaces/inst-123",
            "inspector2, POST, /cluster/get",
            "lakeformation, POST, /CommitTransaction",
            "bedrock, GET, /resourcepolicy/arn:aws:bedrock:us-east-1:123456789012:probe",
            "bedrock, PUT, /resourcepolicy/arn:aws:bedrock:us-east-1:123456789012:probe",
            "bedrock, DELETE, /resourcepolicy/arn:aws:bedrock:us-east-1:123456789012:probe"
    })
    void collidingRestCallsAreSafelyRejectedWith404(String service, String method, String path) {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", service))
            .contentType("application/json")
            .body("{}")
        .when()
            .request(method, path)
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .header("x-amzn-query-error", "UnknownOperationException;Sender")
            .body("__type", equalTo("UnknownOperationException"))
            .body("message", equalTo("Unknown operation: " + method + " " + path));
    }

    @Test
    void legitimateAppConfigCallIsNotRejectedByCollisionGuard() {
        // AppConfig owns /applications, so signed as appconfig it must not be rejected with UnknownOperationException
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "appconfig"))
            .contentType("application/json")
            .body("{\"Name\":\"route-collision-app\"}")
        .when()
            .post("/applications")
        .then()
            .statusCode(201);
    }

    @Test
    void legitimateBedrockResourcePolicyWithHyphenIsNotRejectedByCollisionGuard() {
        // Bedrock owns /resource-policy (with hyphen), must not collide with /resourcepolicy
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "bedrock"))
            .contentType("application/json")
        .when()
            .get("/resource-policy/arn:aws:bedrock:us-east-1:123456789012:test-resource")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void legitimateAppSyncChannelNamespaceCallIsNotRejectedByCollisionGuard() {
        // AppSync owns /v2/apis/{apiId}/channelNamespaces, so signed as appsync it reaches AppSync
        // (returning AppSync's NotFoundException for an unknown apiId, NOT UnknownOperationException)
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "appsync"))
            .contentType("application/json")
            .body("{\"name\":\"test-ns\"}")
        .when()
            .post("/v2/apis/api-123/channelNamespaces")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("NotFoundException"));
    }
}
