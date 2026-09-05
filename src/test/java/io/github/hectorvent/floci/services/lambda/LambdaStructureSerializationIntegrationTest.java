package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class LambdaStructureSerializationIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:structure-queue";
    private static final List<String> FUNCTION_STRUCTURE_MEMBERS = List.of(
            "Environment", "EphemeralStorage", "TracingConfig", "DeadLetterConfig",
            "VpcConfig", "SnapStart", "LoggingConfig", "ImageConfig");

    @Test
    void createFunctionRejectsScalarStructureMembers() {
        List<String> members = new java.util.ArrayList<>(FUNCTION_STRUCTURE_MEMBERS);
        members.add("Code");

        for (String member : members) {
            Map<String, Object> request = functionRequest("create-scalar-" + member.toLowerCase());
            request.put(member, 5);

            given()
                .contentType("application/json")
                .body(request)
            .when()
                .post(BASE_PATH + "/functions")
            .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));
        }
    }

    @Test
    void updateFunctionConfigurationRejectsScalarStructureMembers() {
        createFunction("update-scalar-members");

        for (String member : FUNCTION_STRUCTURE_MEMBERS) {
            given()
                .contentType("application/json")
                .body(Map.of(member, 5))
            .when()
                .put(BASE_PATH + "/functions/update-scalar-members/configuration")
            .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));
        }
    }

    @Test
    void environmentVariablesRejectsScalarBeforeUpdateMutation() {
        Map<String, Object> createRequest = functionRequest("nested-environment-create");
        createRequest.put("Environment", Map.of("Variables", 5));
        given()
            .contentType("application/json")
            .body(createRequest)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));

        Map<String, Object> initialRequest = functionRequest("nested-environment-update");
        initialRequest.put("Description", "original description");
        given()
            .contentType("application/json")
            .body(initialRequest)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201);

        given()
            .contentType("application/json")
            .body(Map.of(
                    "Description", "must not be applied",
                    "Environment", Map.of("Variables", 5)))
        .when()
            .put(BASE_PATH + "/functions/nested-environment-update/configuration")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));

        given()
        .when()
            .get(BASE_PATH + "/functions/nested-environment-update/configuration")
        .then()
            .statusCode(200)
            .body("Description", equalTo("original description"));
    }

    @Test
    void explicitNullStructureMembersRemainOptional() {
        Map<String, Object> request = functionRequest("null-structure-members");
        FUNCTION_STRUCTURE_MEMBERS.forEach(member -> request.put(member, null));
        request.put("Code", null);

        given()
            .contentType("application/json")
            .body(request)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201);

        Map<String, Object> update = new HashMap<>();
        FUNCTION_STRUCTURE_MEMBERS.forEach(member -> update.put(member, null));
        given()
            .contentType("application/json")
            .body(update)
        .when()
            .put(BASE_PATH + "/functions/null-structure-members/configuration")
        .then()
            .statusCode(200);
    }

    @Test
    void eventSourceMappingRejectsScalarStructureMembers() {
        createFunction("esm-structure-members");

        assertEventSourceMappingSerializationError(Map.of("ScalingConfig", 5));
        assertEventSourceMappingSerializationError(Map.of("DestinationConfig", 5));
        assertEventSourceMappingSerializationError(Map.of(
                "DestinationConfig", Map.of("OnFailure", 5)));

        String uuid = given()
            .contentType("application/json")
            .body(Map.of(
                    "FunctionName", "esm-structure-members",
                    "EventSourceArn", QUEUE_ARN,
                    "Enabled", false))
        .when()
            .post(BASE_PATH + "/event-source-mappings")
        .then()
            .statusCode(202)
            .body("UUID", notNullValue())
        .extract()
            .path("UUID");

        List<Map<String, Object>> updates = List.of(
                Map.<String, Object>of("ScalingConfig", 5),
                Map.<String, Object>of("DestinationConfig", 5),
                Map.<String, Object>of("DestinationConfig", Map.of("OnFailure", 5)));
        for (Map<String, Object> update : updates) {
            given()
                .contentType("application/json")
                .body(update)
            .when()
                .put(BASE_PATH + "/event-source-mappings/" + uuid)
            .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));
        }
    }

    private static void assertEventSourceMappingSerializationError(Map<String, Object> structureMember) {
        Map<String, Object> request = new HashMap<>();
        request.put("FunctionName", "esm-structure-members");
        request.put("EventSourceArn", QUEUE_ARN);
        request.put("Enabled", false);
        request.putAll(structureMember);

        given()
            .contentType("application/json")
            .body(request)
        .when()
            .post(BASE_PATH + "/event-source-mappings")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    private static void createFunction(String name) {
        given()
            .contentType("application/json")
            .body(functionRequest(name))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201);
    }

    private static Map<String, Object> functionRequest(String name) {
        Map<String, Object> request = new HashMap<>();
        request.put("FunctionName", name);
        request.put("Runtime", "nodejs20.x");
        request.put("Role", "arn:aws:iam::000000000000:role/lambda-role");
        request.put("Handler", "index.handler");
        return request;
    }
}
