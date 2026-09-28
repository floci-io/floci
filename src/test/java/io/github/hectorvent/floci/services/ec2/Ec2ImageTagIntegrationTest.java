package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.xml.HasXPath.hasXPath;

@QuarkusTest
class Ec2ImageTagIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260917/us-east-1/ec2/aws4_request";

    @Test
    void describeImagesReturnsAndFiltersCurrentTags() {
        String suffix = UUID.randomUUID().toString();
        String imageName = "tagged-image-" + suffix;
        String tagKey = "Origin-" + suffix;
        String tagValue = "Packer-" + suffix;

        String imageId = given()
                .formParam("Action", "RegisterImage")
                .formParam("Name", imageName)
                .formParam("Architecture", "x86_64")
                .formParam("RootDeviceName", "/dev/xvda")
                .formParam("VirtualizationType", "hvm")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("RegisterImageResponse.imageId", startsWith("ami-"))
                .extract().path("RegisterImageResponse.imageId");

        given()
                .formParam("Action", "CreateTags")
                .formParam("ResourceId.1", imageId)
                .formParam("Tag.1.Key", tagKey)
                .formParam("Tag.1.Value", tagValue)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateTagsResponse.return", equalTo("true"));

        given()
                .formParam("Action", "DescribeImages")
                .formParam("ImageId.1", imageId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeImagesResponse.imagesSet.item.imageId", equalTo(imageId))
                .body("DescribeImagesResponse.imagesSet.item.tagSet.item.key", equalTo(tagKey))
                .body("DescribeImagesResponse.imagesSet.item.tagSet.item.value", equalTo(tagValue));

        given()
                .formParam("Action", "DescribeImages")
                .formParam("Filter.1.Name", "tag:" + tagKey)
                .formParam("Filter.1.Value.1", tagValue)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(hasXPath("count(//*[local-name()='imagesSet']/*[local-name()='item'])", equalTo("1")))
                .body("DescribeImagesResponse.imagesSet.item.imageId", equalTo(imageId));

        given()
                .formParam("Action", "DescribeImages")
                .formParam("Filter.1.Name", "tag:" + tagKey)
                .formParam("Filter.1.Value.1", "does-not-match-" + suffix)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(hasXPath("count(//*[local-name()='imagesSet']/*[local-name()='item'])", equalTo("0")));

        given()
                .formParam("Action", "DescribeImages")
                .formParam("Filter.1.Name", "tag-key")
                .formParam("Filter.1.Value.1", tagKey)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(hasXPath("count(//*[local-name()='imagesSet']/*[local-name()='item'])", equalTo("1")))
                .body("DescribeImagesResponse.imagesSet.item.imageId", equalTo(imageId));
    }
}
