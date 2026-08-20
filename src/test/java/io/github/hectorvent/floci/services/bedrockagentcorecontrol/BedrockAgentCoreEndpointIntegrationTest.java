package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreEndpointIntegrationTest {

    private static final String CREATE_RUNTIME = """
            {
              "agentRuntimeName": "epAgent",
              "agentRuntimeArtifact": {"containerConfiguration": {"containerUri": "x:latest"}},
              "networkConfiguration": {"networkMode": "PUBLIC"},
              "roleArn": "arn:aws:iam::000000000000:role/agent"
            }""";

    private static String runtimeId;

    @Test
    @Order(1)
    void setupRuntimeHasDefaultEndpoint() {
        runtimeId = given().contentType("application/json").body(CREATE_RUNTIME)
                .when().put("/runtimes/")
                .then().statusCode(202)
                .extract().path("agentRuntimeId");

        // The DEFAULT endpoint is auto-created with the runtime.
        given().contentType("application/json")
                .when().post("/runtimes/" + runtimeId + "/runtime-endpoints/")
                .then().statusCode(200)
                .body("runtimeEndpoints.name", hasItem("DEFAULT"));
    }

    @Test
    @Order(2)
    void createEndpoint() {
        given().contentType("application/json")
                .body("{\"name\":\"prod\",\"agentRuntimeVersion\":\"1\",\"description\":\"prod ep\"}")
                .when().put("/runtimes/" + runtimeId + "/runtime-endpoints/")
                .then().statusCode(202)
                .body("endpointName", equalTo("prod"))
                .body("targetVersion", equalTo("1"))
                .body("status", equalTo("READY"))
                .body("agentRuntimeEndpointArn", containsString(":agentEndpoint/"));
    }

    @Test
    @Order(2)
    void createEndpointOnAnUnpublishedVersionIsRejected() {
        // Storing the version unchecked produced a READY endpoint whose generated runtime ARN
        // resolves to nothing.
        given().contentType("application/json")
                .body("{\"name\":\"ghost\",\"agentRuntimeVersion\":\"99\"}")
                .when().put("/runtimes/" + runtimeId + "/runtime-endpoints/")
                .then().statusCode(400);

        given().contentType("application/json")
                .when().get("/runtimes/" + runtimeId + "/runtime-endpoints/ghost/")
                .then().statusCode(404);
    }

    @Test
    @Order(3)
    void getEndpoint() {
        given().contentType("application/json")
                .when().get("/runtimes/" + runtimeId + "/runtime-endpoints/prod/")
                .then().statusCode(200)
                .body("name", equalTo("prod"))
                .body("targetVersion", equalTo("1"))
                .body("description", equalTo("prod ep"));
    }

    @Test
    @Order(4)
    void listEndpoints() {
        given().contentType("application/json")
                .when().post("/runtimes/" + runtimeId + "/runtime-endpoints/")
                .then().statusCode(200)
                .body("runtimeEndpoints.name", hasItem("prod"))
                .body("runtimeEndpoints.name", hasItem("DEFAULT"));
    }

    @Test
    @Order(5)
    void updateEndpointToAnUnpublishedVersionIsRejected() {
        given().contentType("application/json").body("{\"agentRuntimeVersion\":\"99\"}")
                .when().put("/runtimes/" + runtimeId + "/runtime-endpoints/prod/")
                .then().statusCode(400);

        // The endpoint keeps the version it had rather than pointing at one that does not exist.
        given().contentType("application/json")
                .when().get("/runtimes/" + runtimeId + "/runtime-endpoints/prod/")
                .then().statusCode(200)
                .body("targetVersion", equalTo("1"));
    }

    @Test
    @Order(5)
    void updateEndpointRetargetsVersion() {
        given().contentType("application/json").body("{\"description\":\"prod v2\"}")
                .when().put("/runtimes/" + runtimeId + "/runtime-endpoints/prod/")
                .then().statusCode(202);

        given().contentType("application/json")
                .when().get("/runtimes/" + runtimeId + "/runtime-endpoints/prod/")
                .then().statusCode(200)
                .body("description", equalTo("prod v2"));
    }

    @Test
    @Order(6)
    void deleteEndpoint() {
        given().contentType("application/json")
                .when().delete("/runtimes/" + runtimeId + "/runtime-endpoints/prod/")
                .then().statusCode(202)
                .body("status", equalTo("DELETING"));

        given().contentType("application/json")
                .when().get("/runtimes/" + runtimeId + "/runtime-endpoints/prod/")
                .then().statusCode(404);
    }

    @Test
    @Order(7)
    void createEndpointOnMissingRuntime404() {
        given().contentType("application/json").body("{\"name\":\"x\"}")
                .when().put("/runtimes/missing-abcdefghij/runtime-endpoints/")
                .then().statusCode(404);
    }
}
