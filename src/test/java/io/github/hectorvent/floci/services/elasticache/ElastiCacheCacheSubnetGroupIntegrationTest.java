package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
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
    private static final String CIDR_A = "10.72.1.0/24";
    private static final String CIDR_B = "10.72.2.0/24";

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
            .formParam("SubnetIds.member.1", subnetIdA)
            .formParam("SubnetIds.member.2", subnetIdB)
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

    @Test
    @Order(5)
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
    @Order(6)
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
