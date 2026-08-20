package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SDK/CLI-shaped round-trip for the ElastiCache CacheSubnetGroup family
 * (CreateCacheSubnetGroup / DescribeCacheSubnetGroups / DeleteCacheSubnetGroup),
 * mirroring RDS's DBSubnetGroup coverage. Control-plane only — no Docker needed.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheCacheSubnetGroupIntegrationTest {

    private static final String EC2_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260818/us-east-1/ec2/aws4_request";
    private static final String ELASTICACHE_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260818/us-east-1/elasticache/aws4_request";

    private static final String SUBNET_GROUP_NAME = "it-ec-subnet-group";
    private static final String SUBNET_GROUP_ARN =
            "arn:aws:elasticache:us-east-1:000000000000:subnetgroup:" + SUBNET_GROUP_NAME;
    private static final String CIDR_A = "10.72.1.0/24";
    private static final String CIDR_B = "10.72.2.0/24";

    @BeforeAll
    static void configureRestAssured() {
        // GetResources is JSON-1.1; RestAssured needs the encoder registered before it
        // will serialize a body for that content type at all.
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String vpcId;
    private static String subnetIdA;
    private static String subnetIdB;

    @Test
    @Order(1)
    void createRealEc2SubnetsForTheGroup() {
        vpcId = given()
                .formParam("Action", "CreateVpc")
                .formParam("CidrBlock", "10.72.0.0/16")
                .header("Authorization", EC2_AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");

        subnetIdA = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", vpcId)
                .formParam("CidrBlock", CIDR_A)
                .formParam("AvailabilityZone", "us-east-1a")
                .header("Authorization", EC2_AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");

        subnetIdB = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", vpcId)
                .formParam("CidrBlock", CIDR_B)
                .formParam("AvailabilityZone", "us-east-1b")
                .header("Authorization", EC2_AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");

        assertNotNull(subnetIdA);
        assertNotNull(subnetIdB);
    }

    @Test
    @Order(2)
    void createCacheSubnetGroupReferencingRealSubnets() {
        given()
            .formParam("Action", "CreateCacheSubnetGroup")
            .formParam("CacheSubnetGroupName", SUBNET_GROUP_NAME)
            .formParam("CacheSubnetGroupDescription", "integration test subnet group")
            .formParam("SubnetIds.SubnetIdentifier.1", subnetIdA)
            .formParam("SubnetIds.SubnetIdentifier.2", subnetIdB)
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateCacheSubnetGroupResponse.CreateCacheSubnetGroupResult.CacheSubnetGroup.CacheSubnetGroupName",
                    equalTo(SUBNET_GROUP_NAME))
            .body("CreateCacheSubnetGroupResponse.CreateCacheSubnetGroupResult.CacheSubnetGroup.CacheSubnetGroupDescription",
                    equalTo("integration test subnet group"))
            .body("CreateCacheSubnetGroupResponse.CreateCacheSubnetGroupResult.CacheSubnetGroup.VpcId",
                    equalTo(vpcId))
            .body("CreateCacheSubnetGroupResponse.CreateCacheSubnetGroupResult.CacheSubnetGroup.ARN",
                    equalTo("arn:aws:elasticache:us-east-1:000000000000:subnetgroup:" + SUBNET_GROUP_NAME));
    }

    @Test
    @Order(3)
    void describeCacheSubnetGroupsEchoesExactSubnets() {
        given()
            .formParam("Action", "DescribeCacheSubnetGroups")
            .formParam("CacheSubnetGroupName", SUBNET_GROUP_NAME)
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCacheSubnetGroupsResponse.DescribeCacheSubnetGroupsResult.CacheSubnetGroups.CacheSubnetGroup.CacheSubnetGroupName",
                    equalTo(SUBNET_GROUP_NAME))
            .body("DescribeCacheSubnetGroupsResponse.DescribeCacheSubnetGroupsResult.CacheSubnetGroups.CacheSubnetGroup.Subnets.Subnet.SubnetIdentifier",
                    hasItems(subnetIdA, subnetIdB));
    }

    @Test
    @Order(4)
    void describeCacheSubnetGroupsFaultsForUnknownName() {
        given()
            .formParam("Action", "DescribeCacheSubnetGroups")
            .formParam("CacheSubnetGroupName", "does-not-exist")
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("CacheSubnetGroupNotFoundFault"));
    }

    // ── Tags ─────────────────────────────────────────────────────────────────
    //
    // The AWS Terraform provider calls ListTagsForResource on every read of an
    // aws_elasticache_subnet_group, so a group that cannot answer it fails the whole
    // apply even when nothing in the configuration mentions tags at all.

    @Test
    @Order(5)
    void listTagsForResourceAnswersEmptyForAnUntaggedGroup() {
        given()
            .formParam("Action", "ListTagsForResource")
            .formParam("ResourceName", SUBNET_GROUP_ARN)
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("ListTagsForResourceResponse.ListTagsForResourceResult.TagList", equalTo(""));
    }

    @Test
    @Order(6)
    void addTagsToResourceReturnsTheResultingListAndListReadsItBack() {
        given()
            .formParam("Action", "AddTagsToResource")
            .formParam("ResourceName", SUBNET_GROUP_ARN)
            .formParam("Tags.Tag.1.Key", "Environment")
            .formParam("Tags.Tag.1.Value", "dev")
            .formParam("Tags.Tag.2.Key", "Owner")
            .formParam("Tags.Tag.2.Value", "platform")
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AddTagsToResourceResponse.AddTagsToResourceResult.TagList.Tag.Key",
                    hasItems("Environment", "Owner"))
            .body("AddTagsToResourceResponse.AddTagsToResourceResult.TagList.Tag.Value",
                    hasItems("dev", "platform"));

        given()
            .formParam("Action", "ListTagsForResource")
            .formParam("ResourceName", SUBNET_GROUP_ARN)
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListTagsForResourceResponse.ListTagsForResourceResult.TagList.Tag.Key",
                    hasItems("Environment", "Owner"));
    }

    @Test
    @Order(7)
    void taggedGroupIsVisibleToResourceGroupsTaggingGetResources() {
        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "ResourceGroupsTaggingAPI_20170126.GetResources")
                .body("{\"ResourceARNList\": [\"" + SUBNET_GROUP_ARN + "\"]}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ResourceTagMappingList.size()", equalTo(1))
                .body("ResourceTagMappingList[0].ResourceARN", equalTo(SUBNET_GROUP_ARN))
                .body("ResourceTagMappingList[0].Tags.Key", hasItems("Environment", "Owner"));
    }

    @Test
    @Order(8)
    void modifyCacheSubnetGroupKeepsTheTags() {
        given()
            .formParam("Action", "ModifyCacheSubnetGroup")
            .formParam("CacheSubnetGroupName", SUBNET_GROUP_NAME)
            .formParam("CacheSubnetGroupDescription", "modified description")
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyCacheSubnetGroupResponse.ModifyCacheSubnetGroupResult.CacheSubnetGroup.CacheSubnetGroupDescription",
                    equalTo("modified description"));

        given()
            .formParam("Action", "ListTagsForResource")
            .formParam("ResourceName", SUBNET_GROUP_ARN)
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListTagsForResourceResponse.ListTagsForResourceResult.TagList.Tag.Key",
                    hasItems("Environment", "Owner"));
    }

    @Test
    @Order(9)
    void removeTagsFromResourceDropsOnlyTheNamedKey() {
        given()
            .formParam("Action", "RemoveTagsFromResource")
            .formParam("ResourceName", SUBNET_GROUP_ARN)
            .formParam("TagKeys.member.1", "Owner")
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RemoveTagsFromResourceResponse.RemoveTagsFromResourceResult.TagList.Tag.Key",
                    equalTo("Environment"));
    }

    @Test
    @Order(10)
    void deleteCacheSubnetGroupRemovesIt() {
        given()
            .formParam("Action", "DeleteCacheSubnetGroup")
            .formParam("CacheSubnetGroupName", SUBNET_GROUP_NAME)
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeCacheSubnetGroups")
            .formParam("CacheSubnetGroupName", SUBNET_GROUP_NAME)
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("CacheSubnetGroupNotFoundFault"));
    }

    @Test
    @Order(11)
    void deleteCacheSubnetGroupFaultsWhenAlreadyGone() {
        given()
            .formParam("Action", "DeleteCacheSubnetGroup")
            .formParam("CacheSubnetGroupName", SUBNET_GROUP_NAME)
            .header("Authorization", ELASTICACHE_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("CacheSubnetGroupNotFoundFault"));
    }
}
