package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;

/**
 * Security group rules whose source is a managed prefix list, over the EC2 Query protocol:
 * authorize, both read-back shapes, and the rejection of an unknown list.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2SecurityGroupPrefixListIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String prefixListId;
    private static String groupId;

    @Test
    @Order(1)
    void createThePrefixListAndGroup() {
        prefixListId = given()
            .formParam("Action", "CreateManagedPrefixList")
            .formParam("PrefixListName", "sg-source")
            .formParam("AddressFamily", "IPv4")
            .formParam("MaxEntries", "5")
            .formParam("Entry.1.Cidr", "10.0.0.0/8")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateManagedPrefixListResponse.prefixList.prefixListId");

        groupId = given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", "prefix-list-consumer")
            .formParam("GroupDescription", "references a prefix list")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateSecurityGroupResponse.groupId");
    }

    @Test
    @Order(2)
    void authorizeIngressFromThePrefixList() {
        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "5432")
            .formParam("IpPermissions.1.ToPort", "5432")
            .formParam("IpPermissions.1.PrefixListIds.1.PrefixListId", prefixListId)
            .formParam("IpPermissions.1.PrefixListIds.1.Description", "from-corp")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("AuthorizeSecurityGroupIngressResponse.securityGroupRuleSet.item.prefixListId",
                    equalTo(prefixListId))
            .body("AuthorizeSecurityGroupIngressResponse.securityGroupRuleSet.item.description",
                    equalTo("from-corp"))
            .body("AuthorizeSecurityGroupIngressResponse.securityGroupRuleSet.item.securityGroupRuleId",
                    not(emptyOrNullString()));
    }

    @Test
    @Order(3)
    void describeSecurityGroupsNestsItUnderThePermission() {
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item.prefixListIds.item.prefixListId",
                    equalTo(prefixListId))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item.prefixListIds.item.description",
                    equalTo("from-corp"));
    }

    @Test
    @Order(4)
    void describeSecurityGroupRulesCarriesThePrefixListId() {
        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            // Only the ingress rule carries a prefixListId; the group's default egress rule omits
            // the element entirely, so this resolves to a single value rather than a list.
            .body("DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item.prefixListId",
                    equalTo(prefixListId));
    }

    @Test
    @Order(5)
    void authorizeAgainstAnUnknownPrefixListIsRejected() {
        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "9999")
            .formParam("IpPermissions.1.ToPort", "9999")
            .formParam("IpPermissions.1.PrefixListIds.1.PrefixListId", "pl-doesnotexist")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidPrefixListID.NotFound"));
    }

    @Test
    @Order(6)
    void revokeRemovesThePermission() {
        given()
            .formParam("Action", "RevokeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "5432")
            .formParam("IpPermissions.1.ToPort", "5432")
            .formParam("IpPermissions.1.PrefixListIds.1.PrefixListId", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions",
                    emptyOrNullString());
    }
}
