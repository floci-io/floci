package io.floci.aws.services.lambdamicrovms;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * The customer managed KMS key a request names: an image version keeps the key it was built with, and a MicroVM
 * the key it was run with. Without one, AWS uses a key of its own, and nothing names a key.
 */
@QuarkusTest
class LambdaMicrovmKmsKeyIntegrationTest {

    private static final String ROOT = "/2025-09-09";
    private static final String IMAGES = ROOT + "/microvm-images";
    private static final String FIRST_KEY = "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";
    private static final String SECOND_KEY = "arn:aws:kms:us-east-1:000000000000:alias/microvm-images";
    private static final String RUN_KEY = "arn:aws:kms:us-east-1:000000000000:key/0987dcba-09fe-87dc-65ba-ab0987654321";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static Map<String, Object> buildInputs(String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseImageArn", "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1");
        body.put("buildRoleArn", "arn:aws:iam::000000000000:role/microvm-build");
        body.put("codeArtifact", Map.of("uri", "s3://bucket/code.zip"));
        if (key != null) {
            body.put("kmsKeyIdentifier", key);
        }
        return body;
    }

    private String createImage(String key) {
        String name = "kms-key-" + UUID.randomUUID();
        Map<String, Object> body = buildInputs(key);
        body.put("name", name);
        given().contentType("application/json").body(body)
                .when().post(IMAGES).then().statusCode(201)
                .body("kmsKeyIdentifier", key == null ? nullValue() : equalTo(key));
        cleanup.register(() -> given().when().delete(IMAGES + "/" + name).then().statusCode(200));
        return name;
    }

    private void versionHasKey(String image, String version, String key) {
        given().when().get(IMAGES + "/" + image + "/versions/" + version).then().statusCode(200)
                .body("kmsKeyIdentifier", key == null ? nullValue() : equalTo(key));
    }

    @Test
    void eachImageVersionKeepsTheKeyItWasBuiltWith() {
        String image = createImage(FIRST_KEY);
        versionHasKey(image, "1.0", FIRST_KEY);

        given().contentType("application/json").body(buildInputs(SECOND_KEY))
                .when().put(IMAGES + "/" + image).then().statusCode(200)
                .body("kmsKeyIdentifier", equalTo(SECOND_KEY));
        versionHasKey(image, "2.0", SECOND_KEY);
        versionHasKey(image, "1.0", FIRST_KEY);

        // The update is a full replace, so leaving the key out builds the next version with an AWS owned key.
        given().contentType("application/json").body(buildInputs(null))
                .when().put(IMAGES + "/" + image).then().statusCode(200)
                .body("kmsKeyIdentifier", nullValue());
        versionHasKey(image, "3.0", null);
    }

    @Test
    void anImageWithoutAKeyNamesNone() {
        versionHasKey(createImage(null), "1.0", null);
    }

    private Response run(String image, String fields) {
        Response response = given().contentType("application/json")
                .body("{\"imageIdentifier\":\"" + image + "\"" + fields + "}")
                .when().post(ROOT + "/microvms").then().statusCode(200).extract().response();
        String id = response.path("microvmId");
        cleanup.register(() -> given().when().delete(ROOT + "/microvms/" + id).then().statusCode(200));
        return response;
    }

    @Test
    void aMicrovmKeepsTheKeyItWasRunWith() {
        String image = createImage(null);
        Response keyed = run(image, ",\"kmsKeyIdentifier\":\"" + RUN_KEY + "\"");
        keyed.then().body("kmsKeyIdentifier", equalTo(RUN_KEY));
        given().when().get(ROOT + "/microvms/" + keyed.path("microvmId")).then().statusCode(200)
                .body("kmsKeyIdentifier", equalTo(RUN_KEY));

        Response unkeyed = run(image, "");
        unkeyed.then().body("kmsKeyIdentifier", nullValue());
        given().when().get(ROOT + "/microvms/" + unkeyed.path("microvmId")).then().statusCode(200)
                .body("kmsKeyIdentifier", nullValue());
    }
}
