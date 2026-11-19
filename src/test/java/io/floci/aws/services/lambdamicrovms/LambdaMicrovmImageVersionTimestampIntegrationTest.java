package io.floci.aws.services.lambdamicrovms;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.config.JsonPathConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LambdaMicrovmImageVersionTimestampIntegrationTest {

    private static final String IMAGES = "/2025-09-09/microvm-images";

    private static final JsonPathConfig EXACT_NUMBERS =
            new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL);

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void getPreservesVersionTimestampWhenParentBuildsAnotherVersion() {
        String image = createImage();
        BigDecimal original = versionTimestamp(image, "1.0");
        updateImage(image);

        assertEquals(original, versionTimestamp(image, "1.0"));
    }

    @Test
    void listPreservesVersionTimestampWhenParentBuildsAnotherVersion() {
        String image = createImage();
        BigDecimal original = versionTimestamp(image, "1.0");
        updateImage(image);

        BigDecimal listed = given().when().get(IMAGES + "/" + image + "/versions")
                .then().statusCode(200).extract().jsonPath(EXACT_NUMBERS).getObject("items[0].updatedAt", BigDecimal.class);
        assertEquals(original, listed);
    }

    @Test
    void statusChangeUpdatesOnlyTheTargetVersionTimestamp() {
        String image = createImage();
        updateImage(image);
        BigDecimal original = versionTimestamp(image, "1.0");
        BigDecimal other = versionTimestamp(image, "2.0");
        BigDecimal parent = given().when().get(IMAGES + "/" + image).then().statusCode(200)
                .extract().jsonPath(EXACT_NUMBERS).getObject("updatedAt", BigDecimal.class);

        given().contentType("application/json").body("{\"status\":\"INACTIVE\"}")
                .when().patch(IMAGES + "/" + image + "/versions/1.0").then().statusCode(200);

        assertTrue(versionTimestamp(image, "1.0").compareTo(original) > 0,
                "Changing a version's status must advance its own updatedAt");
        assertEquals(other, versionTimestamp(image, "2.0"));
        assertEquals(parent, given().when().get(IMAGES + "/" + image).then().statusCode(200)
                .extract().jsonPath(EXACT_NUMBERS).getObject("updatedAt", BigDecimal.class));
    }

    private String createImage() {
        String image = "version-time-" + UUID.randomUUID();
        given().contentType("application/json")
                .body("{\"name\":\"" + image + "\"," + fields("original") + "}")
                .when().post(IMAGES).then().statusCode(201);
        cleanup.register(() -> given().when().delete(IMAGES + "/" + image).then().statusCode(200));
        return image;
    }

    private void updateImage(String image) {
        given().contentType("application/json").body("{" + fields("replacement") + "}")
                .when().put(IMAGES + "/" + image).then().statusCode(200);
    }

    private BigDecimal versionTimestamp(String image, String version) {
        return given().when().get(IMAGES + "/" + image + "/versions/" + version)
                .then().statusCode(200).extract().jsonPath(EXACT_NUMBERS).getObject("updatedAt", BigDecimal.class);
    }

    private static String fields(String label) {
        return "\"baseImageArn\":\"arn:aws:lambda:us-east-1:aws:microvm-image:" + label + "\","
                + "\"buildRoleArn\":\"arn:aws:iam::000000000000:role/build\","
                + "\"codeArtifact\":{\"uri\":\"s3://artifacts/" + label + ".zip\"}";
    }
}
