package io.floci.aws.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

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
                .body("agentRuntimeEndpointArn", containsString("/runtime-endpoint/prod"));
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

    @Test
    void endpointTagsFromCreateCanBeListedTaggedAndUntaggedOnTheEndpointArn() {
        String id = given().contentType("application/json").body(CREATE_RUNTIME.replace("epAgent", "epTagAgent"))
                .when().put("/runtimes/").then().statusCode(202).extract().path("agentRuntimeId");
        String runtimeArn = given().contentType("application/json")
                .when().get("/runtimes/" + id + "/")
                .then().statusCode(200).extract().path("agentRuntimeArn");
        String endpointArn = given().contentType("application/json")
                .body("{\"name\":\"tagged\",\"tags\":{\"env\":\"prod\"}}")
                .when().put("/runtimes/" + id + "/runtime-endpoints/")
                .then().statusCode(202).extract().path("agentRuntimeEndpointArn");
        try {
            given().when().get("/tags/" + endpointArn)
                    .then().statusCode(200).body("tags.env", equalTo("prod"));

            given().contentType("application/json").body("{\"tags\":{\"team\":\"ml\"}}")
                    .when().post("/tags/" + endpointArn).then().statusCode(204);
            given().when().delete("/tags/" + endpointArn + "?tagKeys=env").then().statusCode(204);
            given().when().get("/tags/" + endpointArn)
                    .then().statusCode(200)
                    .body("tags.team", equalTo("ml"))
                    .body("tags.env", nullValue());

            given().when().get("/tags/" + runtimeArn)
                    .then().statusCode(200).body("tags.team", nullValue());
        } finally {
            given().when().delete("/runtimes/" + id + "/").then().statusCode(202);
        }
    }

    @Test
    void endpointTagsAreRemovedWithTheEndpointAndRejectForeignOrMissingArns() {
        String id = given().contentType("application/json").body(CREATE_RUNTIME.replace("epAgent", "epTagAgent2"))
                .when().put("/runtimes/").then().statusCode(202).extract().path("agentRuntimeId");
        String endpointArn = given().contentType("application/json")
                .body("{\"name\":\"gone\",\"tags\":{\"env\":\"prod\"}}")
                .when().put("/runtimes/" + id + "/runtime-endpoints/")
                .then().statusCode(202).extract().path("agentRuntimeEndpointArn");
        try {
            for (String foreign : new String[] {endpointArn.replace(":000000000000:", ":222222222222:"),
                    endpointArn.replace(":us-east-1:", ":eu-west-1:"),
                    endpointArn.replace("arn:aws:", "arn:aws-cn:")}) {
                given().when().get("/tags/" + foreign)
                        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
                given().contentType("application/json").body("{\"tags\":{\"env\":\"stolen\"}}")
                        .when().post("/tags/" + foreign).then().statusCode(404)
                        .body("__type", equalTo("ResourceNotFoundException"));
                given().when().delete("/tags/" + foreign + "?tagKeys=env")
                        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            }
            given().when().get("/tags/" + endpointArn).then().statusCode(200).body("tags.env", equalTo("prod"));

            given().when().delete("/runtimes/" + id + "/runtime-endpoints/gone/").then().statusCode(202);
            given().when().get("/tags/" + endpointArn)
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            given().contentType("application/json").body("{\"tags\":{\"a\":\"b\"}}")
                    .when().post("/tags/" + endpointArn).then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
            given().when().delete("/tags/" + endpointArn + "?tagKeys=a")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

            String missingRuntimeArn = endpointArn.replaceAll("runtime/.*", "runtime/missing0000-AbCdEfGhIj");
            String existingRuntimeArn = endpointArn.replaceAll("runtime/.*", "runtime/" + id);
            for (String runtimeTarget : new String[] {missingRuntimeArn,
                    existingRuntimeArn.replace(":000000000000:", ":222222222222:"),
                    existingRuntimeArn.replace(":us-east-1:", ":eu-west-1:"),
                    existingRuntimeArn.replace("arn:aws:", "arn:aws-cn:")}) {
                given().when().get("/tags/" + runtimeTarget)
                        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
                given().contentType("application/json").body("{\"tags\":{\"a\":\"b\"}}")
                        .when().post("/tags/" + runtimeTarget).then().statusCode(404)
                        .body("__type", equalTo("ResourceNotFoundException"));
                given().when().delete("/tags/" + runtimeTarget + "?tagKeys=a")
                        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            }
        } finally {
            given().when().delete("/runtimes/" + id + "/").then().statusCode(202);
        }
    }

    @Test
    void createEndpointRejectsInvalidTags() {
        String id = given().contentType("application/json").body(CREATE_RUNTIME.replace("epAgent", "epTagAgent3"))
                .when().put("/runtimes/").then().statusCode(202).extract().path("agentRuntimeId");
        try {
            given().contentType("application/json").body("{\"name\":\"bad\",\"tags\":{\"k\":\"v!\"}}")
                    .when().put("/runtimes/" + id + "/runtime-endpoints/").then().statusCode(400);
            given().contentType("application/json").body("{\"name\":\"bad\",\"tags\":{\"\":\"v\"}}")
                    .when().put("/runtimes/" + id + "/runtime-endpoints/").then().statusCode(400);
            given().contentType("application/json")
                    .when().get("/runtimes/" + id + "/runtime-endpoints/bad/")
                    .then().statusCode(404);
        } finally {
            given().when().delete("/runtimes/" + id + "/").then().statusCode(202);
        }
    }
}
