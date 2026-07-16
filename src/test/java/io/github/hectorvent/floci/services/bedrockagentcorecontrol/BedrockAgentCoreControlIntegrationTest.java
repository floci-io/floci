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
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreControlIntegrationTest {

    private static final String CREATE_BODY = """
            {
              "agentRuntimeName": "itAgent",
              "agentRuntimeArtifact": {"containerConfiguration": {"containerUri": "x:latest"}},
              "networkConfiguration": {"networkMode": "PUBLIC"},
              "roleArn": "arn:aws:iam::000000000000:role/agent",
              "description": "v1"
            }""";

    private static final String UPDATE_BODY = """
            {
              "agentRuntimeArtifact": {"containerConfiguration": {"containerUri": "x:v2"}},
              "networkConfiguration": {"networkMode": "PUBLIC"},
              "roleArn": "arn:aws:iam::000000000000:role/agent",
              "description": "v2"
            }""";

    private static String runtimeId;

    @Test
    @Order(1)
    void createReturns202WithArn() {
        runtimeId = given().contentType("application/json").body(CREATE_BODY)
                .when().put("/runtimes/")
                .then().statusCode(202)
                .body("agentRuntimeId", matchesPattern("itAgent-[a-zA-Z0-9]{10}"))
                .body("agentRuntimeVersion", equalTo("1"))
                .body("status", equalTo("READY"))
                .body("agentRuntimeArn", containsString(":bedrock-agentcore:"))
                .body("agentRuntimeArn", containsString(":agent/"))
                .body("workloadIdentityDetails.workloadIdentityArn", notNullValue())
                .extract().path("agentRuntimeId");
    }

    @Test
    @Order(2)
    void getReturnsRuntime() {
        given().contentType("application/json")
                .when().get("/runtimes/" + runtimeId + "/")
                .then().statusCode(200)
                .body("agentRuntimeName", equalTo("itAgent"))
                .body("agentRuntimeVersion", equalTo("1"))
                .body("description", equalTo("v1"))
                .body("agentRuntimeArtifact.containerConfiguration.containerUri", equalTo("x:latest"));
    }

    @Test
    @Order(3)
    void listReturnsRuntime() {
        given().contentType("application/json")
                .when().post("/runtimes/")
                .then().statusCode(200)
                .body("agentRuntimes.agentRuntimeId", hasItem(runtimeId));
    }

    @Test
    @Order(4)
    void updateBumpsVersion() {
        given().contentType("application/json").body(UPDATE_BODY)
                .when().put("/runtimes/" + runtimeId + "/")
                .then().statusCode(202)
                .body("agentRuntimeVersion", equalTo("2"))
                .body("lastUpdatedAt", notNullValue());
    }

    @Test
    @Order(5)
    void getOldVersionReturnsSnapshot() {
        given().contentType("application/json")
                .when().get("/runtimes/" + runtimeId + "/?version=1")
                .then().statusCode(200)
                .body("agentRuntimeVersion", equalTo("1"))
                .body("description", equalTo("v1"))
                .body("agentRuntimeArtifact.containerConfiguration.containerUri", equalTo("x:latest"));
    }

    @Test
    @Order(6)
    void listVersionsReturnsBoth() {
        given().contentType("application/json")
                .when().post("/runtimes/" + runtimeId + "/versions/")
                .then().statusCode(200)
                .body("agentRuntimes.agentRuntimeVersion", hasItem("1"))
                .body("agentRuntimes.agentRuntimeVersion", hasItem("2"));
    }

    @Test
    @Order(7)
    void deleteReturns202Deleting() {
        given().contentType("application/json")
                .when().delete("/runtimes/" + runtimeId + "/")
                .then().statusCode(202)
                .body("agentRuntimeId", equalTo(runtimeId))
                .body("status", equalTo("DELETING"));
    }

    @Test
    @Order(8)
    void getAfterDeleteReturns404() {
        given().contentType("application/json")
                .when().get("/runtimes/" + runtimeId + "/")
                .then().statusCode(404);
    }

    @Test
    @Order(9)
    void createWithInvalidNameReturns400() {
        given().contentType("application/json")
                .body("{\"agentRuntimeName\":\"1bad\",\"agentRuntimeArtifact\":{},\"networkConfiguration\":{},\"roleArn\":\"r\"}")
                .when().put("/runtimes/")
                .then().statusCode(400);
    }
}
