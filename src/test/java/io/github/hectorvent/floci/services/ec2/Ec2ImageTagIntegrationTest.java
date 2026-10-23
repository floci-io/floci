package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2ImageTagIntegrationTest {

    @Inject
    Ec2Service service;

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

    @Test
    void registerImageTagsTheImageWithItsImageTagSpecification() {
        String suffix = UUID.randomUUID().toString();

        String imageId = given()
                .formParam("Action", "RegisterImage")
                .formParam("Name", "spec-tagged-image-" + suffix)
                .formParam("RootDeviceName", "/dev/xvda")
                .formParam("TagSpecification.1.ResourceType", "image")
                .formParam("TagSpecification.1.Tag.1.Key", "Build")
                .formParam("TagSpecification.1.Tag.1.Value", suffix)
                .formParam("TagSpecification.1.Tag.2.Key", "Reviewed")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("RegisterImageResponse.imageId");

        given()
                .formParam("Action", "DescribeImages")
                .formParam("ImageId.1", imageId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(hasXPath("count(//*[local-name()='tagSet']/*[local-name()='item'])", equalTo("2")))
                .body("DescribeImagesResponse.imagesSet.item.tagSet.item.find { it.key == 'Build' }.value",
                        equalTo(suffix))
                // A tag given without a value is stored with an empty one, as CreateTags stores it.
                .body("DescribeImagesResponse.imagesSet.item.tagSet.item.find { it.key == 'Reviewed' }.value",
                        equalTo(""));

        given()
                .formParam("Action", "DescribeImages")
                .formParam("Filter.1.Name", "tag:Build")
                .formParam("Filter.1.Value.1", suffix)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(hasXPath("count(//*[local-name()='imagesSet']/*[local-name()='item'])", equalTo("1")))
                .body("DescribeImagesResponse.imagesSet.item.imageId", equalTo(imageId));

        given()
                .formParam("Action", "DescribeTags")
                .formParam("Filter.1.Name", "resource-id")
                .formParam("Filter.1.Value.1", imageId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(hasXPath("count(//*[local-name()='tagSet']/*[local-name()='item'])", equalTo("2")))
                .body("DescribeTagsResponse.tagSet.item.resourceType", everyItem(equalTo("image")));
    }

    @Test
    void registerImageKeepsTheLastValueOfATagKeyGivenTwice() {
        // A resource holds one value per tag key. Floci keeps the last one given, as its CreateTags
        // does; what AWS answers to a key repeated in one request is not documented.
        String imageId = given()
                .formParam("Action", "RegisterImage")
                .formParam("Name", "spec-repeated-key-" + UUID.randomUUID())
                .formParam("RootDeviceName", "/dev/xvda")
                .formParam("TagSpecification.1.ResourceType", "image")
                .formParam("TagSpecification.1.Tag.1.Key", "Build")
                .formParam("TagSpecification.1.Tag.1.Value", "first")
                .formParam("TagSpecification.1.Tag.2.Key", "Build")
                .formParam("TagSpecification.1.Tag.2.Value", "second")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("RegisterImageResponse.imageId");

        given()
                .formParam("Action", "DescribeImages")
                .formParam("ImageId.1", imageId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(hasXPath("count(//*[local-name()='tagSet']/*[local-name()='item'])", equalTo("1")))
                .body("DescribeImagesResponse.imagesSet.item.tagSet.item.value", equalTo("second"));
    }

    @Test
    void registerImageRefusesTagsForAnotherResourceTypeAndRegistersNothing() {
        // "To tag the AMI, the value for ResourceType must be image. If you specify another value
        // for ResourceType, the request fails."
        String imageName = "spec-snapshot-tags-" + UUID.randomUUID();

        given()
                .formParam("Action", "RegisterImage")
                .formParam("Name", imageName)
                .formParam("RootDeviceName", "/dev/xvda")
                .formParam("TagSpecification.1.ResourceType", "snapshot")
                .formParam("TagSpecification.1.Tag.1.Key", "Build")
                .formParam("TagSpecification.1.Tag.1.Value", "nightly")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(hasXPath("//*[local-name()='Code']", equalTo("InvalidParameterValue")));

        assertNoImageNamed(imageName);
    }

    @ParameterizedTest
    @CsvSource({
            "reserved-key, InvalidParameterValue",
            "long-key, InvalidParameterValue",
            "long-value, InvalidParameterValue",
            "too-many-tags, TagLimitExceeded"})
    void registerImageRefusesTagsCreateTagsWouldRefuseAndRegistersNothing(String invalid, String code) {
        String imageName = "spec-invalid-tags-" + UUID.randomUUID();

        given()
                .formParam("Action", "RegisterImage")
                .formParam("Name", imageName)
                .formParam("RootDeviceName", "/dev/xvda")
                .formParam("TagSpecification.1.ResourceType", "image")
                .formParams(invalidImageTags(invalid))
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(hasXPath("//*[local-name()='Code']", equalTo(code)));

        assertNoImageNamed(imageName);
    }

    @Test
    void registerImageAcceptsFiftyTags() {
        String imageId = given()
                .formParam("Action", "RegisterImage")
                .formParam("Name", "spec-fifty-tags-" + UUID.randomUUID())
                .formParam("RootDeviceName", "/dev/xvda")
                .formParam("TagSpecification.1.ResourceType", "image")
                .formParams(IntStream.rangeClosed(1, 50).boxed().collect(Collectors.toMap(
                        i -> "TagSpecification.1.Tag." + i + ".Key", i -> "key-" + i)))
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("RegisterImageResponse.imageId");

        given()
                .formParam("Action", "DescribeTags")
                .formParam("Filter.1.Name", "resource-id")
                .formParam("Filter.1.Value.1", imageId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(hasXPath("count(//*[local-name()='tagSet']/*[local-name()='item'])", equalTo("50")));
    }

    @Test
    void theServiceRefusesASystemTagKeyAndTreatsNoTagsAsNone() {
        // The Query handler refuses an aws: key first; the service checks it too, for its other callers.
        String refusedName = "service-system-tag-" + UUID.randomUUID();
        AwsException refused = assertThrows(AwsException.class, () -> service.registerImage("us-east-1",
                refusedName, null, null, "/dev/xvda", null, List.of(new Tag("aws:build", "nightly"))));
        assertEquals("InvalidParameterValue", refused.getErrorCode());
        assertNoImageNamed(refusedName);

        Image untagged = service.registerImage("us-east-1", "service-no-tags-" + UUID.randomUUID(), null, null,
                "/dev/xvda", null, null);
        assertTrue(service.resourceTags("us-east-1", untagged.getImageId()).isEmpty());
    }

    private static Map<String, String> invalidImageTags(String invalid) {
        return switch (invalid) {
            case "reserved-key" -> Map.of("TagSpecification.1.Tag.1.Key", "aws:build");
            case "long-key" -> Map.of("TagSpecification.1.Tag.1.Key", "k".repeat(129));
            case "long-value" -> Map.of("TagSpecification.1.Tag.1.Key", "Build",
                    "TagSpecification.1.Tag.1.Value", "v".repeat(257));
            case "too-many-tags" -> IntStream.rangeClosed(1, 51).boxed().collect(Collectors.toMap(
                    i -> "TagSpecification.1.Tag." + i + ".Key", i -> "key-" + i));
            default -> throw new IllegalArgumentException(invalid);
        };
    }

    private static void assertNoImageNamed(String imageName) {
        given()
                .formParam("Action", "DescribeImages")
                .formParam("Filter.1.Name", "name")
                .formParam("Filter.1.Value.1", imageName)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(hasXPath("count(//*[local-name()='imagesSet']/*[local-name()='item'])", equalTo("0")));
    }
}
