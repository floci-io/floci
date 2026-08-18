package io.github.hectorvent.floci.services.resourcegroupstagging;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;

/**
 * {@code GetResources} must see resources tagged through the owning service's own API, not only
 * the ones tagged through {@code TagResources}.
 *
 * <p>This is the regression gate for the defect where an estate full of tagged resources came
 * back as {@code "ResourceTagMappingList": []}: floci keeps a resource's tags on that resource's
 * own model, and the tagging service read a store nothing but EventBridge and Glue ever wrote to.
 * Every assertion here goes through the public wire protocols — EC2 Query for the volume, IAM
 * Query for the role, JSON 1.1 for the tagging API — so it fails if the seam between them breaks
 * for any reason, not just the one this test was written for.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EstateTaggingIndexIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "ResourceGroupsTaggingAPI_20170126.";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private static final String ESTATE_TAG = "estate-index-probe";
    private static final String ROLE_NAME = "estate-index-probe-role";

    private static String volumeId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createVolumeWithInlineTags() {
        volumeId = given()
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("Size", "8")
            .formParam("TagSpecification.1.ResourceType", "volume")
            .formParam("TagSpecification.1.Tag.1.Key", "tofu-estate")
            .formParam("TagSpecification.1.Tag.1.Value", ESTATE_TAG)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVolumeResponse.volumeId");
    }

    @Test
    @Order(2)
    void createRoleWithTags() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", ROLE_NAME)
            .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
            .formParam("Tags.member.1.Key", "tofu-estate")
            .formParam("Tags.member.1.Value", ESTATE_TAG)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    /** An EC2 model carries an id, not an ARN; the ARN has to be assembled and be the real one. */
    @Test
    @Order(3)
    void getResourcesFindsBothWithoutAnyTagResourcesCall() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "tofu-estate", "Values": ["%s"]}]}
                """.formatted(ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItems(
                    "arn:aws:ec2:us-east-1:000000000000:volume/" + volumeId,
                    "arn:aws:iam::000000000000:role/" + ROLE_NAME));
    }

    @Test
    @Order(4)
    void resourceTypeFilterSelectsTheSynthesizedEc2Type() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "tofu-estate", "Values": ["%s"]}],
                 "ResourceTypeFilters": ["ec2:volume"]}
                """.formatted(ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.size()", equalTo(1))
            .body("ResourceTagMappingList[0].ResourceARN",
                    equalTo("arn:aws:ec2:us-east-1:000000000000:volume/" + volumeId));
    }

    @Test
    @Order(5)
    void tagKeysAndValuesSeeTheSameEstate() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagValues")
            .contentType(CONTENT_TYPE)
            .body("{\"Key\": \"tofu-estate\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagValues", hasItem(ESTATE_TAG));
    }

    /**
     * {@code CreateTags} merges with what the resource already has. It used to merge only with
     * the {@code ec2-tags.json} side-store, which a volume created with {@code TagSpecification}
     * never appears in — so adding one tag silently dropped the tags it was created with, both
     * from {@code DescribeVolumes} and from the tagging index.
     */
    @Test
    @Order(6)
    void createTagsKeepsTheTagsTheResourceWasCreatedWith() {
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", volumeId)
            .formParam("Tag.1.Key", "added-later")
            .formParam("Tag.1.Value", "yes")
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeVolumes")
            .formParam("VolumeId.1", volumeId)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVolumesResponse.volumeSet.item.tagSet.item.key",
                    hasItems("tofu-estate", "added-later"));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["arn:aws:ec2:us-east-1:000000000000:volume/%s"]}
                """.formatted(volumeId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList[0].Tags.Key", hasItems("tofu-estate", "added-later"));
    }

    /** DescribeTags used to report {@code unknown} for every id outside a 12-entry prefix list. */
    @Test
    @Order(7)
    void describeTagsNamesTheResourceType() {
        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", volumeId)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.resourceType", hasItem("volume"));
    }

    /**
     * Route53 keeps a hosted zone's tags in a side store ({@code route53-tags.json}, keyed
     * {@code hostedzone/<id>}) with nothing on the model itself — no {@code tags} field, no
     * {@code arn} field. {@code list-tags-for-resource} always saw them; {@code get-resources}
     * did not, because the estate-wide scan only read tags off a resource's own model. This is
     * the regression gate for that gap.
     */
    private static String hostedZoneId;

    @Test
    @Order(8)
    void createHostedZoneAndTagItThroughRoute53() {
        String createBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>estate-index-probe.example.com</Name>
                  <CallerReference>estate-index-probe</CallerReference>
                </CreateHostedZoneRequest>
                """;
        String location = given()
                .contentType("application/xml")
                .body(createBody)
            .when()
                .post("/2013-04-01/hostedzone")
            .then()
                .statusCode(201)
                .extract().header("Location");
        hostedZoneId = location.substring(location.lastIndexOf('/') + 1);

        String addTagBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeTagsForResourceRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <AddTags>
                    <Tag><Key>tofu-estate</Key><Value>%s</Value></Tag>
                  </AddTags>
                </ChangeTagsForResourceRequest>
                """.formatted(ESTATE_TAG);
        given()
                .contentType("application/xml")
                .body(addTagBody)
            .when()
                .post("/2013-04-01/tags/hostedzone/" + hostedZoneId)
            .then()
                .statusCode(200);
    }

    @Test
    @Order(9)
    void getResourcesFindsTheHostedZoneWithNoTagResourcesCall() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["arn:aws:route53:::hostedzone/%s"]}
                """.formatted(hostedZoneId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList[0].ResourceARN", equalTo("arn:aws:route53:::hostedzone/" + hostedZoneId))
            .body("ResourceTagMappingList[0].Tags.Key", hasItem("tofu-estate"))
            .body("ResourceTagMappingList[0].Tags.Value", hasItem(ESTATE_TAG));
    }

    @Test
    @Order(10)
    void resourceTypeFilterSelectsTheSynthesizedRoute53Type() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "tofu-estate", "Values": ["%s"]}],
                 "ResourceTypeFilters": ["route53:hostedzone"]}
                """.formatted(ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItem("arn:aws:route53:::hostedzone/" + hostedZoneId));
    }
}
