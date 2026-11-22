package io.floci.aws.services.lambdamicrovms;

import io.floci.aws.testing.PartitionCleanup;
import io.restassured.response.ValidatableResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

final class LambdaMicrovmImageFixture {

    private static final String IMAGES = "/2025-09-09/microvm-images";

    private final PartitionCleanup cleanup;
    private final String prefix;
    private final BuildInputs original;
    private final BuildInputs replacement;

    LambdaMicrovmImageFixture(PartitionCleanup cleanup, String prefix, BuildInputs original, BuildInputs replacement) {
        this.cleanup = cleanup;
        this.prefix = prefix;
        this.original = original;
        this.replacement = replacement;
    }

    String create() {
        String name = prefix + UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.putAll(original.fields());
        given().contentType("application/json").body(body)
                .when().post(IMAGES).then().statusCode(201);
        cleanup.register(() -> given().when().delete(IMAGES + "/" + name).then().statusCode(200));
        return name;
    }

    ValidatableResponse update(String image) {
        return given().contentType("application/json").body(replacement.fields())
                .when().put(IMAGES + "/" + image).then().statusCode(200);
    }

    UpdatedImage createWithTwoVersions() {
        String name = create();
        return new UpdatedImage(name, update(name));
    }

    record UpdatedImage(String name, ValidatableResponse response) {
    }

    record BuildInputs(String baseImageArn, String baseImageVersion, String buildRoleArn, String artifactUri) {
        Map<String, Object> fields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("baseImageArn", baseImageArn);
            if (baseImageVersion != null) {
                fields.put("baseImageVersion", baseImageVersion);
            }
            fields.put("buildRoleArn", buildRoleArn);
            fields.put("codeArtifact", Map.of("uri", artifactUri));
            return fields;
        }
    }
}
