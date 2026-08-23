package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for {@code aws_launch_template}'s {@code metadata_options} and
 * {@code monitoring} blocks over the EC2 Query Protocol.
 *
 * <p>Surfaced 2026-08-22 re-crossing terraform-aws-modules/terraform-aws-autoscaling's
 * {@code examples/complete} through choudoufu (live/e2e/corpus-autoscaling-complete): every
 * module call in that example sets {@code metadata_options} (the module's own variable default
 * is non-null), so a stateless replan proposed {@code + metadata_options {...}} and
 * {@code + monitoring {...}} on every launch template, forever - {@code LaunchTemplateData} had
 * no field for either at all, so {@code CreateLaunchTemplate} silently dropped both
 * ({@code @JsonIgnoreProperties(ignoreUnknown = true)}) and
 * {@code DescribeLaunchTemplateVersions} echoed back neither.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2LaunchTemplateMetadataOptionsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-west-2/ec2/aws4_request";

    @Test
    @Order(1)
    void createRoundTripsMetadataOptionsAndMonitoring() {
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "metadata-options-test")
            .formParam("LaunchTemplateData.ImageId", "ami-0123456789abcdef0")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.MetadataOptions.HttpEndpoint", "enabled")
            .formParam("LaunchTemplateData.MetadataOptions.HttpTokens", "required")
            .formParam("LaunchTemplateData.MetadataOptions.HttpPutResponseHopLimit", "1")
            .formParam("LaunchTemplateData.Monitoring.Enabled", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");
    }

    @Test
    @Order(2)
    void describeLaunchTemplateVersionsEchoesBothBlocksBack() {
        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", "metadata-options-test")
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.metadataOptions.httpEndpoint",
                    equalTo("enabled"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.metadataOptions.httpTokens",
                    equalTo("required"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.metadataOptions.httpPutResponseHopLimit",
                    equalTo("1"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.monitoring.enabled",
                    equalTo("true"));
    }

    @Test
    @Order(3)
    void aTemplateThatNeverSetEitherBlockOmitsBothRatherThanInventingDefaults() {
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "no-metadata-options-test")
            .formParam("LaunchTemplateData.ImageId", "ami-0123456789abcdef0")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String xml = given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", "no-metadata-options-test")
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        assertFalse(xml.contains("metadataOptions"), xml);
        assertFalse(xml.contains("monitoring"), xml);
    }
}
