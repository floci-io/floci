package io.floci.aws.services.lambdamicrovms;

import io.floci.aws.services.lambdamicrovms.LambdaMicrovmImageFixture.BuildInputs;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.config.JsonPathConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;

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

    private final LambdaMicrovmImageFixture images = new LambdaMicrovmImageFixture(cleanup, "version-time-",
            new BuildInputs("arn:aws:lambda:us-east-1:aws:microvm-image:original", null,
                    "arn:aws:iam::000000000000:role/build", "s3://artifacts/original.zip"),
            new BuildInputs("arn:aws:lambda:us-east-1:aws:microvm-image:replacement", null,
                    "arn:aws:iam::000000000000:role/build", "s3://artifacts/replacement.zip"));

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
        return images.create();
    }

    private void updateImage(String image) {
        images.update(image);
    }

    private BigDecimal versionTimestamp(String image, String version) {
        return given().when().get(IMAGES + "/" + image + "/versions/" + version)
                .then().statusCode(200).extract().jsonPath(EXACT_NUMBERS).getObject("updatedAt", BigDecimal.class);
    }

}
