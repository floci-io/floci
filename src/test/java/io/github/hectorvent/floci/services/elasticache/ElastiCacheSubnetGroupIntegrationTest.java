package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Cache subnet groups over the Query protocol.
 *
 * <p>The VPC and each subnet's availability zone are read from the subnets, as AWS reads them, so
 * these tests create real subnets in the emulator's EC2 first: a group cannot be answered for
 * subnets that do not exist.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheSubnetGroupIntegrationTest {

    private static final String GROUP = "int-test-sng";
    private static final String EC_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260412/%s/ec2/aws4_request";

    private static String firstSubnet;
    private static String secondSubnet;
    private static String vpcId;

    private static io.restassured.specification.RequestSpecification elasticache(String action) {
        return given().header("Authorization", EC_AUTH)
                .formParam("Action", action)
                .formParam("Version", "2015-02-02");
    }

    private static String ec2(String region, String action, String... formParams) {
        var request = given().header("Authorization", EC2_AUTH.formatted(region))
                .formParam("Action", action)
                .formParam("Version", "2016-11-15");
        for (int i = 0; i < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.when().post("/").then().statusCode(200).extract().body().asString();
    }

    private static String between(String xml, String tag) {
        int start = xml.indexOf("<" + tag + ">") + tag.length() + 2;
        return xml.substring(start, xml.indexOf("</" + tag + ">", start));
    }

    @Test
    @Order(1)
    void createSubnetsToPlaceTheGroupIn() {
        vpcId = between(ec2("us-east-1", "CreateVpc", "CidrBlock", "10.20.0.0/16"), "vpcId");
        firstSubnet = between(ec2("us-east-1", "CreateSubnet", "VpcId", vpcId, "CidrBlock", "10.20.1.0/24",
                "AvailabilityZone", "us-east-1a"), "subnetId");
        secondSubnet = between(ec2("us-east-1", "CreateSubnet", "VpcId", vpcId, "CidrBlock", "10.20.2.0/24",
                "AvailabilityZone", "us-east-1b"), "subnetId");
    }

    @Test
    @Order(2)
    void createReportsTheVpcAndZonesTakenFromTheSubnets() {
        elasticache("CreateCacheSubnetGroup")
                .formParam("CacheSubnetGroupName", GROUP)
                .formParam("CacheSubnetGroupDescription", "integration test")
                .formParam("SubnetIds.SubnetIdentifier.1", firstSubnet)
                .formParam("SubnetIds.SubnetIdentifier.2", secondSubnet)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CacheSubnetGroupName>" + GROUP + "</CacheSubnetGroupName>"))
            .body(containsString("<VpcId>" + vpcId + "</VpcId>"))
            .body(containsString("<SubnetIdentifier>" + firstSubnet + "</SubnetIdentifier>"))
            .body(containsString("<Name>us-east-1a</Name>"))
            .body(containsString("<Name>us-east-1b</Name>"))
            .body(containsString(":subnetgroup:" + GROUP + "</ARN>"))
            // In the order they were given: the subnets are read back from a store whose scan
            // order is its own, so the group would otherwise report them in an arbitrary order.
            .body(org.hamcrest.Matchers.matchesPattern(
                    "(?s).*<SubnetIdentifier>" + firstSubnet + "</SubnetIdentifier>.*"
                            + "<SubnetIdentifier>" + secondSubnet + "</SubnetIdentifier>.*"));
    }

    @Test
    @Order(3)
    void aSubnetThatDoesNotExistIsRejected() {
        elasticache("CreateCacheSubnetGroup")
                .formParam("CacheSubnetGroupName", "absent-subnet-sng")
                .formParam("CacheSubnetGroupDescription", "x")
                .formParam("SubnetIds.SubnetIdentifier.1", "subnet-00000000000000000")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("are invalid"));
    }

    @Test
    @Order(3)
    void subnetsFromTwoVpcsAreRejected() {
        String otherVpc = between(ec2("us-east-1", "CreateVpc", "CidrBlock", "10.21.0.0/16"), "vpcId");
        String otherSubnet = between(ec2("us-east-1", "CreateSubnet", "VpcId", otherVpc, "CidrBlock", "10.21.1.0/24",
                "AvailabilityZone", "us-east-1a"), "subnetId");

        elasticache("CreateCacheSubnetGroup")
                .formParam("CacheSubnetGroupName", "cross-vpc-sng")
                .formParam("CacheSubnetGroupDescription", "x")
                .formParam("SubnetIds.SubnetIdentifier.1", firstSubnet)
                .formParam("SubnetIds.SubnetIdentifier.2", otherSubnet)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("are not in the same VPC"));
    }

    @Test
    @Order(3)
    void duplicateAndUnknownGroupsCarryTheirOwnErrors() {
        elasticache("CreateCacheSubnetGroup")
                .formParam("CacheSubnetGroupName", GROUP)
                .formParam("CacheSubnetGroupDescription", "again")
                .formParam("SubnetIds.SubnetIdentifier.1", firstSubnet)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("CacheSubnetGroupAlreadyExists"));

        // 400, not 404: the subnet-group fault is declared that way, unlike the parameter-group
        // one a few hundred lines up, which really is a 404.
        elasticache("DescribeCacheSubnetGroups")
                .formParam("CacheSubnetGroupName", "no-such-sng")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("Cache subnet group no-such-sng not found"));
    }

    @Test
    @Order(4)
    void modifyReplacesTheDescriptionAndSubnets() {
        elasticache("ModifyCacheSubnetGroup")
                .formParam("CacheSubnetGroupName", GROUP)
                .formParam("CacheSubnetGroupDescription", "changed")
                .formParam("SubnetIds.SubnetIdentifier.1", firstSubnet)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CacheSubnetGroupDescription>changed</CacheSubnetGroupDescription>"))
            .body(containsString("<SubnetIdentifier>" + firstSubnet + "</SubnetIdentifier>"));

        // The subnet dropped by the modify is gone from the group.
        elasticache("DescribeCacheSubnetGroups")
                .formParam("CacheSubnetGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(org.hamcrest.Matchers.not(containsString(secondSubnet)));
    }

    @Test
    @Order(4)
    void tagsGivenAtCreateAreKeptAndListed() {
        // Dropping them would leave terraform with a diff it can never settle.
        elasticache("CreateCacheSubnetGroup")
                .formParam("CacheSubnetGroupName", "tagged-sng")
                .formParam("CacheSubnetGroupDescription", "tagged")
                .formParam("SubnetIds.SubnetIdentifier.1", firstSubnet)
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "prod")
        .when().post("/")
        .then()
            .statusCode(200);

        elasticache("ListTagsForResource")
                .formParam("ResourceName",
                        "arn:aws:elasticache:us-east-1:000000000000:subnetgroup:tagged-sng")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>env</Key>"))
            .body(containsString("<Value>prod</Value>"));
    }

    @Test
    @Order(4)
    void aDescriptionOnlyModifyLeavesTheSubnetsAlone() {
        // Nothing is said about subnets, so none change — and the stored ones are not re-resolved,
        // which would fail this call if one of them had since been deleted from EC2.
        elasticache("ModifyCacheSubnetGroup")
                .formParam("CacheSubnetGroupName", "tagged-sng")
                .formParam("CacheSubnetGroupDescription", "description only")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CacheSubnetGroupDescription>description only</CacheSubnetGroupDescription>"))
            .body(containsString("<SubnetIdentifier>" + firstSubnet + "</SubnetIdentifier>"));

        // And the tags survive a modify.
        elasticache("ListTagsForResource")
                .formParam("ResourceName",
                        "arn:aws:elasticache:us-east-1:000000000000:subnetgroup:tagged-sng")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>env</Key>"));

        elasticache("DeleteCacheSubnetGroup")
                .formParam("CacheSubnetGroupName", "tagged-sng")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(4)
    void subnetsAreResolvedInTheCallersRegion() {
        // Subnets exist in the region they were created in. Resolving against the configured
        // default region instead would reject these as invalid, and would report the group under
        // an eu-west-1 ARN built from subnets read somewhere else.
        String euVpc = between(ec2("eu-west-1", "CreateVpc", "CidrBlock", "10.30.0.0/16"), "vpcId");
        String euSubnet = between(ec2("eu-west-1", "CreateSubnet", "VpcId", euVpc,
                "CidrBlock", "10.30.1.0/24", "AvailabilityZone", "eu-west-1a"), "subnetId");

        given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260412/eu-west-1/elasticache/aws4_request")
                .formParam("Action", "CreateCacheSubnetGroup")
                .formParam("Version", "2015-02-02")
                .formParam("CacheSubnetGroupName", "eu-west-sng")
                .formParam("CacheSubnetGroupDescription", "other region")
                .formParam("SubnetIds.SubnetIdentifier.1", euSubnet)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<VpcId>" + euVpc + "</VpcId>"))
            .body(containsString("<Name>eu-west-1a</Name>"))
            .body(containsString("arn:aws:elasticache:eu-west-1:"));
    }

    @Test
    @Order(5)
    void deleteRemovesItAndSaysSoDifferentlyTheSecondTime() {
        elasticache("DeleteCacheSubnetGroup")
                .formParam("CacheSubnetGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200);

        // AWS words the delete's not-found differently from the describe's.
        elasticache("DeleteCacheSubnetGroup")
                .formParam("CacheSubnetGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("Cache Subnet Group " + GROUP + " does not exist"));
    }
}
