package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The AgentCore harness control plane over its REST routes: {@code CreateHarness},
 * {@code GetHarness}, {@code UpdateHarness}, {@code DeleteHarness}, {@code ListHarnesses},
 * {@code ListHarnessVersions} and tags on a harness ARN.
 */
@QuarkusTest
class BedrockAgentCoreHarnessControlIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/harness-role";

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void deleteHarnesses() {
        created.forEach(id -> given().delete("/harnesses/" + id));
    }

    @Test
    void aHarnessCreatedWithTheMinimumGetsAwsDefaults() {
        String name = name();
        String id = create("""
                {"harnessName": "%s", "executionRoleArn": "%s",
                 "model": {"bedrockModelConfig": {"modelId": "us.anthropic.claude-sonnet-4-6"}},
                 "systemPrompt": [{"text": "Be brief."}]}
                """.formatted(name, ROLE))
                .body("harness.harnessName", equalTo(name))
                .body("harness.harnessId", matchesPattern(name + "-[a-zA-Z0-9]{10}"))
                .body("harness.arn", matchesPattern(
                        "arn:aws:bedrock-agentcore:us-east-1:000000000000:harness/" + name + "-[a-zA-Z0-9]{10}"))
                .body("harness.status", equalTo("READY"))
                .body("harness.harnessVersion", equalTo("1"))
                .body("harness.executionRoleArn", equalTo(ROLE))
                .body("harness.createdAt", notNullValue())
                .body("harness.model.bedrockModelConfig.apiFormat", equalTo("converse_stream"))
                .body("harness.systemPrompt[0].text", equalTo("Be brief."))
                .body("harness.tools", empty())
                .body("harness.skills", empty())
                .body("harness.allowedTools", empty())
                .body("harness.truncation.strategy", equalTo("sliding_window"))
                .body("harness.truncation.config.slidingWindow.messagesCount", equalTo(150))
                .body("harness.environmentVariables.size()", equalTo(0))
                .body("harness.environment.agentCoreRuntimeEnvironment.agentRuntimeName", equalTo("harness_" + name))
                .body("harness.environment.agentCoreRuntimeEnvironment.agentRuntimeId",
                        matchesPattern("harness_" + name + "-[a-zA-Z0-9]{10}"))
                .body("harness.environment.agentCoreRuntimeEnvironment.agentRuntimeArn",
                        containsString(":runtime/harness_" + name + "-"))
                .body("harness.environment.agentCoreRuntimeEnvironment.lifecycleConfiguration.idleRuntimeSessionTimeout",
                        equalTo(900))
                .body("harness.environment.agentCoreRuntimeEnvironment.lifecycleConfiguration.maxLifetime",
                        equalTo(28800))
                .body("harness.environment.agentCoreRuntimeEnvironment.networkConfiguration.networkMode",
                        equalTo("PUBLIC"))
                .body("harness.memory", nullValue())
                .body("harness.maxIterations", nullValue())
                .extract().path("harness.harnessId");

        given().get("/harnesses/" + id).then().statusCode(200)
                .body("harness.harnessId", equalTo(id))
                .body("harness.truncation.config.slidingWindow.messagesCount", equalTo(150));
        given().get("/harnesses").then().statusCode(200)
                .body("harnesses.harnessId", hasItem(id))
                .body("harnesses.find { it.harnessId == '" + id + "' }.harnessVersion", equalTo("1"));
    }

    @Test
    void anUpdateAppendsAVersionAndKeepsTheRuntime() {
        String name = name();
        String id = create("""
                {"harnessName": "%s", "executionRoleArn": "%s",
                 "model": {"bedrockModelConfig": {"modelId": "model-a", "apiFormat": "converse"}},
                 "memory": {"disabled": {}},
                 "truncation": {"strategy": "sliding_window", "config": {"slidingWindow": {"messagesCount": 1}}},
                 "environment": {"agentCoreRuntimeEnvironment": {
                     "lifecycleConfiguration": {"idleRuntimeSessionTimeout": 3600, "maxLifetime": 28800},
                     "networkConfiguration": {"networkMode": "PUBLIC"}}},
                 "allowedTools": [], "maxIterations": 2, "timeoutSeconds": 60, "environmentVariables": {}}
                """.formatted(name, ROLE))
                .body("harness.model.bedrockModelConfig.apiFormat", equalTo("converse"))
                .body("harness.memory.disabled.size()", equalTo(0))
                .extract().path("harness.harnessId");
        String runtimeId = given().get("/harnesses/" + id).then()
                .extract().path("harness.environment.agentCoreRuntimeEnvironment.agentRuntimeId");

        given().contentType(ContentType.JSON).body("""
                        {"maxIterations": 5, "memory": {},
                         "environment": {"agentCoreRuntimeEnvironment": {
                             "lifecycleConfiguration": {"idleRuntimeSessionTimeout": 1200}}}}
                        """)
                .patch("/harnesses/" + id).then().statusCode(200)
                .body("harness.harnessVersion", equalTo("2"))
                .body("harness.maxIterations", equalTo(5))
                .body("harness.timeoutSeconds", equalTo(60))
                .body("harness.memory", nullValue())
                .body("harness.model.bedrockModelConfig.modelId", equalTo("model-a"))
                .body("harness.environment.agentCoreRuntimeEnvironment.agentRuntimeId", equalTo(runtimeId))
                .body("harness.environment.agentCoreRuntimeEnvironment.lifecycleConfiguration.idleRuntimeSessionTimeout",
                        equalTo(1200))
                .body("harness.environment.agentCoreRuntimeEnvironment.lifecycleConfiguration.maxLifetime",
                        equalTo(28800));
        given().contentType(ContentType.JSON)
                .body("{\"memory\": {\"optionalValue\": {\"disabled\": {}}}, \"executionRoleArn\": \"" + ROLE + "2\"}")
                .patch("/harnesses/" + id).then().statusCode(200)
                .body("harness.harnessVersion", equalTo("3"))
                .body("harness.executionRoleArn", equalTo(ROLE + "2"))
                .body("harness.memory.disabled.size()", equalTo(0));

        given().get("/harnesses/" + id + "?harnessVersion=1").then().statusCode(200)
                .body("harness.harnessVersion", equalTo("1"))
                .body("harness.maxIterations", equalTo(2))
                .body("harness.executionRoleArn", equalTo(ROLE));
        given().get("/harnesses/" + id + "/versions").then().statusCode(200)
                .body("harnessVersions.harnessVersion", contains("3", "2", "1"))
                .body("harnessVersions[0].harnessId", equalTo(id));
        given().get("/harnesses/" + id + "/versions?maxResults=2").then().statusCode(200)
                .body("harnessVersions.harnessVersion", contains("3", "2"))
                .body("nextToken", notNullValue());
    }

    @Test
    void tagsDeletionAndRefusals() {
        String name = name();
        String arn = create("""
                {"harnessName": "%s", "executionRoleArn": "%s", "tags": {"team": "data"}}
                """.formatted(name, ROLE)).extract().path("harness.arn");
        String id = arn.substring(arn.indexOf("harness/") + "harness/".length());

        given().get("/tags/" + arn).then().statusCode(200).body("tags.team", equalTo("data"));
        given().contentType(ContentType.JSON).body("{\"tags\": {\"env\": \"local\"}}")
                .post("/tags/" + arn).then().statusCode(204);
        given().delete("/tags/" + arn + "?tagKeys=team").then().statusCode(204);
        given().get("/tags/" + arn).then().statusCode(200)
                .body("tags.env", equalTo("local")).body("tags.team", nullValue());

        given().contentType(ContentType.JSON)
                .body("{\"harnessName\": \"" + name + "\", \"executionRoleArn\": \"" + ROLE + "\"}")
                .post("/harnesses").then().statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"));
        given().contentType(ContentType.JSON).body("{\"harnessName\": \"1bad\", \"executionRoleArn\": \"" + ROLE + "\"}")
                .post("/harnesses").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"harnessName\": \"" + name() + "\"}")
                .post("/harnesses").then().statusCode(400);
        given().get("/harnesses/" + id + "?harnessVersion=9").then().statusCode(404)
                .body("message", equalTo("Version 9 not found for harness " + id));

        given().delete("/harnesses/" + id + "?deleteManagedMemory=true").then().statusCode(200)
                .body("harness.harnessId", equalTo(id))
                .body("harness.status", equalTo("DELETING"));
        given().get("/harnesses/" + id).then().statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Agent with name " + id + " not found."));
        given().get("/harnesses/" + id + "/versions").then().statusCode(404);
        given().get("/harnesses").then().body("harnesses.harnessId", not(hasItem(id)));
        created.remove(id);
    }

    @Test
    void aRepeatedClientTokenAnswersTheFirstHarness() {
        String token = UUID.randomUUID() + "-harness-token";
        String body = """
                {"harnessName": "%s", "executionRoleArn": "%s", "clientToken": "%s"}
                """.formatted(name(), ROLE, token);
        String first = create(body).extract().path("harness.harnessId");
        given().contentType(ContentType.JSON).body(body).post("/harnesses").then().statusCode(201)
                .body("harness.harnessId", equalTo(first));
    }

    private ValidatableResponse create(String body) {
        ValidatableResponse response = given().contentType(ContentType.JSON).body(body)
                .post("/harnesses").then().statusCode(201);
        created.add(response.extract().path("harness.harnessId"));
        return response;
    }

    private static String name() {
        return "h_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
