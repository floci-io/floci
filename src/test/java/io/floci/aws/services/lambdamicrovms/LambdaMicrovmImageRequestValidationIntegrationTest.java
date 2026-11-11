package io.floci.aws.services.lambdamicrovms;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;

@QuarkusTest
class LambdaMicrovmImageRequestValidationIntegrationTest {

    private static final String IMAGES = "/2025-09-09/microvm-images";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String body(String name, String fields) {
        return """
                {"name":"%s",
                 "baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                 "buildRoleArn":"arn:aws:iam::000000000000:role/microvm-build",
                 "codeArtifact":{"uri":"s3://bucket/code.zip"}%s}
                """.formatted(name, fields);
    }

    private String name() {
        String name = "image-validation-" + UUID.randomUUID();
        cleanup.register(() -> given().when().delete(IMAGES + "/" + name));
        return name;
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"cpuConfigurations\":[{}]", "\"cpuConfigurations\":[\"ARM_64\"]",
            "\"environmentVariables\":[]", "\"logging\":[]",
            "\"egressNetworkConnectors\":{}", "\"additionalOsCapabilities\":{}",
            "\"cpuConfigurations\":[{\"architecture\":\"\"}]", "\"cpuConfigurations\":[{\"architecture\":42}]",
            "\"environmentVariables\":{\"STAGE\":{}}", "\"logging\":{\"cloudWatch\":[]}",
            "\"logging\":{\"disabled\":[]}", "\"logging\":{\"cloudWatch\":{\"logGroup\":[]}}",
            "\"egressNetworkConnectors\":[{}]", "\"additionalOsCapabilities\":[true]"})
    void malformedRuntimeSettingsAreRejectedOnCreate(String field) {
        given().contentType("application/json").body(body(name(), "," + field))
                .when().post(IMAGES).then().statusCode(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"cpuConfigurations\":[{}]", "\"cpuConfigurations\":[\"ARM_64\"]",
            "\"environmentVariables\":[]", "\"logging\":[]",
            "\"egressNetworkConnectors\":{}", "\"additionalOsCapabilities\":{}",
            "\"cpuConfigurations\":[{\"architecture\":\"\"}]", "\"cpuConfigurations\":[{\"architecture\":42}]",
            "\"environmentVariables\":{\"STAGE\":{}}", "\"logging\":{\"cloudWatch\":[]}",
            "\"logging\":{\"disabled\":[]}", "\"logging\":{\"cloudWatch\":{\"logGroup\":[]}}",
            "\"egressNetworkConnectors\":[{}]", "\"additionalOsCapabilities\":[true]"})
    void malformedRuntimeSettingsAreRejectedOnUpdate(String field) {
        String name = name();
        given().contentType("application/json").body(body(name, ""))
                .when().post(IMAGES).then().statusCode(201);
        given().contentType("application/json").body(body(name, "," + field))
                .when().put(IMAGES + "/" + name).then().statusCode(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{\"owner\":{}}", "{\"owner\":42}"})
    void malformedCreateTagsAreRejected(String tags) {
        given().contentType("application/json").body(body(name(), ",\"tags\":" + tags))
                .when().post(IMAGES).then().statusCode(400);
    }
}
