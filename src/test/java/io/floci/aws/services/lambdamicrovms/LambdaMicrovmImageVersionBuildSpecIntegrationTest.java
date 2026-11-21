package io.floci.aws.services.lambdamicrovms;

import io.floci.aws.services.lambdamicrovms.LambdaMicrovmImageFixture.BuildInputs;
import io.floci.aws.services.lambdamicrovms.LambdaMicrovmImageFixture.UpdatedImage;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class LambdaMicrovmImageVersionBuildSpecIntegrationTest {

    private static final String IMAGES = "/2025-09-09/microvm-images";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final LambdaMicrovmImageFixture images = new LambdaMicrovmImageFixture(cleanup, "version-build-spec-",
            new BuildInputs("arn:aws:lambda:us-east-1:aws:microvm-image:original", "1.0",
                    "arn:aws:iam::000000000000:role/original", "s3://artifacts/original.zip"),
            new BuildInputs("arn:aws:lambda:us-east-1:aws:microvm-image:replacement", "2.0",
                    "arn:aws:iam::000000000000:role/replacement", "s3://artifacts/replacement.zip"));

    @Test
    void getKeepsEachVersionsBuildInputsAfterAnUpdate() {
        String name = createAndUpdate();
        assertBuildSpec(given().when().get(IMAGES + "/" + name + "/versions/1.0")
                .then().statusCode(200), "", "original", "1.0");
        assertBuildSpec(given().when().get(IMAGES + "/" + name + "/versions/2.0")
                .then().statusCode(200), "", "replacement", "2.0");
    }

    @Test
    void listKeepsEachVersionsBuildInputsAfterAnUpdate() {
        String name = createAndUpdate();
        ValidatableResponse listed = given().when().get(IMAGES + "/" + name + "/versions")
                .then().statusCode(200).body("items.size()", equalTo(2));
        assertBuildSpec(listed, "items[0].", "original", "1.0");
        assertBuildSpec(listed, "items[1].", "replacement", "2.0");
    }

    private String createAndUpdate() {
        UpdatedImage image = images.createWithTwoVersions();
        assertBuildSpec(image.response(), "", "replacement", "2.0");
        given().when().get(IMAGES + "/" + image.name()).then().statusCode(200)
                .body("latestActiveImageVersion", equalTo("2.0"));
        return image.name();
    }

    private static void assertBuildSpec(ValidatableResponse response, String prefix, String label, String version) {
        response.body(prefix + "baseImageArn", equalTo("arn:aws:lambda:us-east-1:aws:microvm-image:" + label))
                .body(prefix + "baseImageVersion", equalTo(version))
                .body(prefix + "buildRoleArn", equalTo("arn:aws:iam::000000000000:role/" + label))
                .body(prefix + "codeArtifact.uri", equalTo("s3://artifacts/" + label + ".zip"));
    }
}
