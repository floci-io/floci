package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The REST (v1) half: a method with {@code authorizationType: AWS_IAM} was recorded and
 * returned by {@code GetMethod}, but v1 dispatch only ever invoked a CUSTOM authorizer, so an
 * unsigned request ran the integration as if the method were {@code NONE}.
 *
 * <p>A MOCK integration stands in for the backend: it needs no Lambda runtime, and a {@code 200}
 * from it is proof the request got past authorization, which is all these assertions are about.
 * Both the {@code /restapis/…/_user_request_/…} and {@code /execute-api/…} entry points are
 * exercised, because they reach {@code dispatch} by different routes.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayIamAuthorizationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";
    private static final String STAGE = "test";

    private static String apiId;
    private static String iamResourceId;

    @Test
    @Order(0)
    void setup() {
        apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"rest-iam-auth-test\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");

        iamResourceId = createMockMethod(rootId, "iam", "AWS_IAM");
        createMockMethod(rootId, "open", "NONE");

        String deploymentId = given().contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"" + STAGE + "\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    private static String createMockMethod(String rootId, String pathPart, String authorizationType) {
        String resourceId = given().contentType(ContentType.JSON)
                .body("{\"pathPart\":\"" + pathPart + "\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        String methodPath = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"" + authorizationType + "\"}")
                .when().put(methodPath)
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put(methodPath + "/responses/200")
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put(methodPath + "/integration")
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":"
                        + "{\"application/json\":\"{\\\"reached\\\":\\\"integration\\\"}\"}}")
                .when().put(methodPath + "/integration/responses/200")
                .then().statusCode(201);
        return resourceId;
    }

    @Test
    @Order(1)
    void methodStillReportsAwsIamAuthorization() {
        given().when().get("/restapis/" + apiId + "/resources/" + iamResourceId + "/methods/GET")
                .then().statusCode(200)
                .body("authorizationType", equalTo("AWS_IAM"));
    }

    @Test
    @Order(10)
    void unsignedUserRequestIsRejectedWithMissingAuthenticationToken() {
        given().when().get(userRequestPath("iam"))
                .then().statusCode(403)
                .header("x-amzn-ErrorType", "MissingAuthenticationTokenException")
                .body("message", equalTo("Missing Authentication Token"));
    }

    @Test
    @Order(11)
    void unsignedExecuteApiRequestIsRejectedToo() {
        given().when().get("/execute-api/" + apiId + "/" + STAGE + "/iam")
                .then().statusCode(403)
                .body("message", equalTo("Missing Authentication Token"));
    }

    @Test
    @Order(12)
    void validlySignedRequestReachesTheIntegration() throws Exception {
        given().headers(signedHeaders(userRequestPath("iam"), Instant.now()))
                .when().get(userRequestPath("iam"))
                .then().statusCode(200)
                .body("reached", equalTo("integration"));
    }

    @Test
    @Order(13)
    void signatureForAnotherPathIsRejected() throws Exception {
        given().headers(signedHeaders(userRequestPath("open"), Instant.now()))
                .when().get(userRequestPath("iam"))
                .then().statusCode(403)
                .header("x-amzn-ErrorType", "InvalidSignatureException");
    }

    @Test
    @Order(14)
    void staleSignatureIsRejectedAsExpired() throws Exception {
        given().headers(signedHeaders(userRequestPath("iam"), Instant.now().minus(2, ChronoUnit.HOURS)))
                .when().get(userRequestPath("iam"))
                .then().statusCode(403)
                .header("x-amzn-ErrorType", "InvalidSignatureException")
                .body("message", equalTo("Signature expired"));
    }

    @Test
    @Order(15)
    void unregisteredAccessKeyIsRejectedAsAnInvalidToken() throws Exception {
        Map<String, String> headers = ExecuteApiRequestSigner.signedHeaders(
                "GET", userRequestPath("iam"), Map.of(), host(), null,
                "AKIANOTREGISTERED", "AKIANOTREGISTERED", REGION, Instant.now());

        given().headers(headers)
                .when().get(userRequestPath("iam"))
                .then().statusCode(403)
                .header("x-amzn-ErrorType", "UnrecognizedClientException");
    }

    @Test
    @Order(16)
    void methodWithoutIamAuthorizationStillAcceptsUnsignedRequests() {
        given().when().get(userRequestPath("open"))
                .then().statusCode(200)
                .body("reached", equalTo("integration"));
    }

    @Test
    @Order(999)
    void cleanup() {
        if (apiId != null) {
            given().when().delete("/restapis/" + apiId);
        }
    }

    private static String userRequestPath(String pathPart) {
        return "/restapis/" + apiId + "/" + STAGE + "/_user_request_/" + pathPart;
    }

    private static String host() {
        return "localhost:" + RestAssured.port;
    }

    private static Map<String, String> signedHeaders(String path, Instant signedAt) throws Exception {
        return ExecuteApiRequestSigner.signedHeaders(
                "GET", path, Map.of(), host(), null, ACCESS_KEY, SECRET_KEY, REGION, signedAt);
    }
}
