package io.floci.aws.services.lambdamicrovms;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class LambdaMicrovmImageRequestFieldsIntegrationTest {

    private static final String IMAGES = "/2025-09-09/microvm-images";
    private static final String CONNECTOR =
            "arn:aws:lambda:us-east-1:000000000000:network-connector:nc-0123456789abcdef0";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String create(String fields) {
        String name = "image-fields-" + UUID.randomUUID();
        given().contentType("application/json")
                .body("""
                        {"name":"%s",
                         "baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                         "buildRoleArn":"arn:aws:iam::000000000000:role/microvm-build",
                         "codeArtifact":{"uri":"s3://bucket/code.zip"}%s}
                        """.formatted(name, fields))
                .when().post(IMAGES).then().statusCode(201);
        cleanup.register(() -> given().when().delete(IMAGES + "/" + name).then().statusCode(200));
        return name;
    }

    private String updateBody(String fields) {
        return """
                {"baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                 "buildRoleArn":"arn:aws:iam::000000000000:role/microvm-build",
                 "codeArtifact":{"uri":"s3://bucket/code-v2.zip"}%s}
                """.formatted(fields);
    }

    @Test
    void imageVersionReturnsTheRequestedRuntimeConfiguration() {
        String name = create("""
                ,"egressNetworkConnectors":["%s"],
                 "logging":{"cloudWatch":{"logGroup":"/aws/lambda-microvms/image-fields"}},
                 "environmentVariables":{"STAGE":"created"},
                 "additionalOsCapabilities":["ALL"],
                 "cpuConfigurations":[{"architecture":"ARM_64"}]
                """.formatted(CONNECTOR));

        given().when().get(IMAGES + "/" + name + "/versions/1.0").then().statusCode(200)
                .body("egressNetworkConnectors", contains(CONNECTOR))
                .body("logging.cloudWatch.logGroup", equalTo("/aws/lambda-microvms/image-fields"))
                .body("environmentVariables.STAGE", equalTo("created"))
                .body("additionalOsCapabilities", contains("ALL"))
                .body("cpuConfigurations[0].architecture", equalTo("ARM_64"));
    }

    @Test
    void imageCreationRetainsTheRequestedTags() {
        String name = create(",\"tags\":{\"owner\":\"image-fields\"}");

        given().when().get(IMAGES + "/" + name).then().statusCode(200)
                .body("tags.owner", equalTo("image-fields"));
    }

    @Test
    void imageCreationAndUpdateRetainTheRequestedBaseVersion() {
        String name = create(",\"baseImageVersion\":\"9\"");
        given().when().get(IMAGES + "/" + name + "/versions/1.0").then().statusCode(200)
                .body("baseImageVersion", equalTo("9"));

        given().contentType("application/json").body(updateBody(",\"baseImageVersion\":\"10\""))
                .when().put(IMAGES + "/" + name).then().statusCode(200);
        given().when().get(IMAGES + "/" + name + "/versions/2.0").then().statusCode(200)
                .body("baseImageVersion", equalTo("10"));
    }

    @Test
    void updatedRuntimeConfigurationIsSpecificToItsImageVersion() {
        String name = create(",\"environmentVariables\":{\"STAGE\":\"one\"}");
        given().contentType("application/json")
                .body(updateBody(",\"environmentVariables\":{\"STAGE\":\"two\"},\"logging\":{\"disabled\":{}}"))
                .when().put(IMAGES + "/" + name).then().statusCode(200);

        given().when().get(IMAGES + "/" + name + "/versions").then().statusCode(200)
                .body("items.size()", equalTo(2))
                .body("items[0].environmentVariables.STAGE", equalTo("one"))
                .body("items[1].environmentVariables.STAGE", equalTo("two"))
                .body("items[1].logging.disabled", equalTo(Map.of()));
    }

    @Test
    void omittedUpdateConfigurationResetsToTheDefaults() {
        String name = create(",\"baseImageVersion\":\"9\",\"environmentVariables\":{\"STAGE\":\"one\"},"
                + "\"logging\":{\"disabled\":{}}");
        given().contentType("application/json").body(updateBody(""))
                .when().put(IMAGES + "/" + name).then().statusCode(200);
        given().when().get(IMAGES + "/" + name + "/versions/2.0").then().statusCode(200)
                .body("baseImageVersion", equalTo("1.0"))
                .body("environmentVariables", nullValue())
                .body("logging", nullValue());
        given().when().get(IMAGES + "/" + name + "/versions/1.0").then().statusCode(200)
                .body("environmentVariables.STAGE", equalTo("one"));
    }

    @Test
    void anExplicitEmptyEgressListUsesTheManagedDefault() {
        String name = create(",\"egressNetworkConnectors\":[]");
        given().when().get(IMAGES + "/" + name + "/versions/1.0").then().statusCode(200)
                .body("egressNetworkConnectors", contains(
                        "arn:aws:lambda:us-east-1:aws:network-connector:aws-network-connector:INTERNET_EGRESS"));
    }

    @Test
    void omittedRuntimeConfigurationKeepsTheManagedEgressDefault() {
        String name = create("");
        given().when().get(IMAGES + "/" + name + "/versions/1.0").then().statusCode(200)
                .body("egressNetworkConnectors", contains(
                        "arn:aws:lambda:us-east-1:aws:network-connector:aws-network-connector:INTERNET_EGRESS"))
                .body("logging", nullValue());
    }
}
