package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Runs a real function to prove the synchronous response limit is AWS's 6,291,556 bytes on every
 * synchronous path: a larger response is a {@code Function.ResponseSizeTooLarge} function error, which
 * the Invoke API returns with {@code X-Amz-Function-Error} and a REST API proxy route turns into 502.
 */
@QuarkusTest
class LambdaResponseSizeLimitDockerIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/lambda-role";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Answers a string of {@code event.size} characters, or behind a proxy route a response with that body. */
    private static final String HANDLER = """
            exports.handler = async (event) => {
              if (event.httpMethod) {
                return { statusCode: 200, body: 'x'.repeat(Number(event.queryStringParameters.size)) };
              }
              return 'x'.repeat(event.size);
            };
            """;

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for Lambda response size integration tests");
    }

    @Test
    void aResponseUnderTheLimitIsReturned() throws Exception {
        String function = createFunction();
        try {
            // The JSON string of 6,291,498 characters is 6,291,500 bytes: over 6 MiB, under AWS's 6,291,556.
            Response response = invoke(function, 6_291_498);

            assertEquals(200, response.statusCode());
            assertNull(response.header("X-Amz-Function-Error"));
            assertEquals(6_291_500, response.asByteArray().length);
        } finally {
            deleteFunction(function);
        }
    }

    @Test
    void aLargerResponseIsAResponseSizeTooLargeFunctionError() throws Exception {
        String function = createFunction();
        try {
            assertResponseSizeTooLarge(invoke(function, 6_999_998));
        } finally {
            deleteFunction(function);
        }
    }

    @Test
    void aResponseLargerThanTheRuntimeApiReadsIsTheSameFunctionError() throws Exception {
        String function = createFunction();
        try {
            assertResponseSizeTooLarge(invoke(function, 13 * 1024 * 1024));
        } finally {
            deleteFunction(function);
        }
    }

    @Test
    void aRestApiProxyRouteAnswers502ForAnOversizedResponse() throws Exception {
        String function = createFunction();
        String apiId = restApiProxyingTo(function);
        try {
            given()
                    .when().get("/restapis/" + apiId + "/prod/_user_request_/big?size=10")
                    .then()
                    .statusCode(200);
            String body = given()
                    .when().get("/restapis/" + apiId + "/prod/_user_request_/big?size=6999990")
                    .then()
                    .statusCode(502)
                    .extract().asString();

            assertFalse(body.contains("xxxxxxxxxx"), "the oversized response must not reach the client");
        } finally {
            given().when().delete("/restapis/" + apiId);
            deleteFunction(function);
        }
    }

    private static void assertResponseSizeTooLarge(Response response) throws Exception {
        assertEquals(200, response.statusCode());
        assertEquals("Unhandled", response.header("X-Amz-Function-Error"));
        JsonNode error = MAPPER.readTree(response.asByteArray());
        assertEquals("Function.ResponseSizeTooLarge", error.path("errorType").asText());
        assertEquals("Response payload size exceeded maximum allowed payload size (6291556 bytes).",
                error.path("errorMessage").asText());
    }

    private static Response invoke(String function, int characters) {
        return given()
                .contentType("application/json")
                .body("{\"size\":" + characters + "}")
                .when()
                .post(BASE_PATH + "/functions/" + function + "/invocations");
    }

    private static String createFunction() throws Exception {
        String name = "response-size-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .contentType("application/json")
                .body("""
                    {
                      "FunctionName": "%s",
                      "Runtime": "nodejs20.x",
                      "Role": "%s",
                      "Handler": "index.handler",
                      "Timeout": 15,
                      "MemorySize": 512,
                      "Code": {"ZipFile": "%s"}
                    }
                    """.formatted(name, ROLE, handlerZipBase64()))
                .when()
                .post(BASE_PATH + "/functions")
                .then()
                .statusCode(201);
        return name;
    }

    private static void deleteFunction(String function) {
        given().when().delete(BASE_PATH + "/functions/" + function);
    }

    private static String restApiProxyingTo(String function) {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"response-size-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");
        String resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"big\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then().statusCode(201);
        String uri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + function + "/invocations";
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"AWS_PROXY\",\"httpMethod\":\"POST\",\"uri\":\"" + uri + "\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then().statusCode(201);
        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"prod\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
        return apiId;
    }

    private static String handlerZipBase64() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(HANDLER.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception expected) {
            // Docker is unreachable, so requireDocker() skips the test rather than failing it.
            return false;
        }
    }
}
