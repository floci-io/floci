package io.floci.aws.services.lambdamicrovms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;

@QuarkusTest
class LambdaMicrovmImageModeledConstraintsIntegrationTest {

    private static final String IMAGES = "/2025-09-09/microvm-images";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String name() {
        String name = "image-model-" + UUID.randomUUID();
        cleanup.register(() -> given().when().delete(IMAGES + "/" + name));
        return name;
    }

    private String body(String name, String fields) {
        return """
                {"name":"%s",
                 "baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                 "buildRoleArn":"arn:aws:iam::000000000000:role/microvm-build",
                 "codeArtifact":{"uri":"s3://bucket/code.zip"}%s}
                """.formatted(name, fields);
    }

    private static String environment(int count, String key, String value) {
        ObjectNode variables = MAPPER.createObjectNode();
        for (int i = 0; i < count; i++) {
            variables.put(key + (count == 1 ? "" : i), value);
        }
        return "\"environmentVariables\":" + variables;
    }

    static Stream<String> invalidRuntimeFields() {
        return Stream.of(
                "\"additionalOsCapabilities\":[\"SYS_ADMIN\"]",
                "\"egressNetworkConnectors\":[\"one\",\"two\"]",
                "\"cpuConfigurations\":[{\"architecture\":\"X86_64\"}]",
                environment(51, "KEY", "value"),
                environment(1, "", "value"),
                environment(1, "k".repeat(257), "value"),
                environment(1, "bad key", "value"),
                environment(1, "KEY", "x".repeat(4097)),
                "\"baseImageVersion\":\"\"",
                "\"baseImageVersion\":\"bad version\"",
                "\"baseImageVersion\":\"" + "x".repeat(2049) + "\"");
    }

    @ParameterizedTest
    @MethodSource("invalidRuntimeFields")
    void modeledRuntimeBoundsAreEnforcedOnCreate(String fields) {
        given().contentType("application/json").body(body(name(), "," + fields))
                .when().post(IMAGES).then().statusCode(400);
    }

    @ParameterizedTest
    @MethodSource("invalidRuntimeFields")
    void modeledRuntimeBoundsAreEnforcedOnUpdate(String fields) {
        String name = name();
        given().contentType("application/json").body(body(name, ""))
                .when().post(IMAGES).then().statusCode(201);
        given().contentType("application/json").body(body(name, "," + fields))
                .when().put(IMAGES + "/" + name).then().statusCode(400);
    }

    static Stream<String> invalidTags() {
        return Stream.of("{\"\":\"value\"}", "{\"" + "k".repeat(129) + "\":\"value\"}",
                "{\"bad#key\":\"value\"}", "{\"owner\":\"" + "x".repeat(257) + "\"}",
                "{\"owner\":\"bad#value\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidTags")
    void modeledTagBoundsAreEnforcedOnCreate(String tags) {
        given().contentType("application/json").body(body(name(), ",\"tags\":" + tags))
                .when().post(IMAGES).then().statusCode(400);
    }

    private static String boundaryFields() {
        ObjectNode variables = MAPPER.createObjectNode();
        variables.put("k".repeat(256), "x".repeat(4096));
        for (int i = 1; i < 50; i++) {
            variables.put("KEY" + i, "");
        }
        return ",\"environmentVariables\":" + variables
                + ",\"additionalOsCapabilities\":[\"ALL\"],\"egressNetworkConnectors\":[\"one\"]"
                + ",\"cpuConfigurations\":[{\"architecture\":\"ARM_64\"}]"
                + ",\"baseImageVersion\":\"" + "x".repeat(2048) + "\"";
    }

    @Test
    void modeledBoundariesAreAcceptedOnCreate() {
        String tags = ",\"tags\":{\"" + "k".repeat(128) + "\":\"" + "x".repeat(256) + "\"}";
        given().contentType("application/json").body(body(name(), boundaryFields() + tags))
                .when().post(IMAGES).then().statusCode(201);
    }

    @Test
    void modeledBoundariesAreAcceptedOnUpdateWithoutABodyIdentifier() {
        String name = name();
        given().contentType("application/json").body(body(name, ""))
                .when().post(IMAGES).then().statusCode(201);
        given().contentType("application/json").body(body(name, boundaryFields()))
                .when().put(IMAGES + "/" + name).then().statusCode(200);
    }
}
