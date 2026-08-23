package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * lex00/floci#102: a security group rule whose source is a managed prefix list must keep that
 * source.
 *
 * <p>{@code PrefixListIds} is the fourth kind of source an {@code IpPermission} can carry
 * (alongside {@code IpRanges}, {@code Ipv6Ranges} and {@code Groups}, all covered by
 * {@link Ec2SecurityGroupRuleSourcesIntegrationTest}); it was never read on authorize at all, so
 * every prefix-list-sourced rule survived as a rule with no source, and {@code PrefixListId} came
 * back {@code null} on every subsequent read. That made a Terraform refresh see the field as
 * always unset and propose a forced replacement on every plan. The source has to survive on the
 * same three surfaces as the other kinds: the rule echoed by AuthorizeSecurityGroupIngress,
 * DescribeSecurityGroups ({@code PrefixListIds}) and DescribeSecurityGroupRules
 * ({@code PrefixListId}), and revoke has to find and remove it by that same source.
 */
@QuarkusTest
class Ec2SecurityGroupRulePrefixListIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void prefixListSourceSurvivesAuthorizeAndBothDescribes() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("target-pl"), vpcId);
        String prefixListId = createManagedPrefixList(uniqueName("dns"));

        // The rule under test: allow udp/53 from a managed prefix list.
        String authorizeBody = given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "udp")
            .formParam("IpPermissions.1.FromPort", "53")
            .formParam("IpPermissions.1.ToPort", "53")
            .formParam("IpPermissions.1.PrefixListIds.1.PrefixListId", prefixListId)
            .formParam("IpPermissions.1.PrefixListIds.1.Description", "dns-from-prefix-list")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertContains(authorizeBody, "<prefixListId>" + prefixListId + "</prefixListId>"
                + "<description>dns-from-prefix-list</description>",
                "AuthorizeSecurityGroupIngress must echo the prefix list source");

        // Control: the same group with a CIDR rule, which already worked.
        authorizeCidrIngress(groupId, "8443", "10.0.0.0/8", "control");

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[0].fromPort",
                    equalTo("53"))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[0].prefixListIds.item.prefixListId",
                    equalTo(prefixListId))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[0].prefixListIds.item.description",
                    equalTo("dns-from-prefix-list"))
            // The CIDR control is unchanged by the new source kind.
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[1].ipRanges.item.cidrIp",
                    equalTo("10.0.0.0/8"));

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<prefixListId>" + prefixListId + "</prefixListId>"))
            .body(containsString("<cidrIpv4>10.0.0.0/8</cidrIpv4>"))
            // The prefix-list rule carries a prefix list, not a CIDR, and vice versa.
            .body(not(containsString("<cidrIpv4>10.0.0.0/8</cidrIpv4><prefixListId>" + prefixListId)));
    }

    @Test
    void prefixListSourceSurvivesOnEgress() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("target-pl-egress"), vpcId);
        String prefixListId = createManagedPrefixList(uniqueName("s3"));

        given()
            .formParam("Action", "AuthorizeSecurityGroupEgress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "443")
            .formParam("IpPermissions.1.ToPort", "443")
            .formParam("IpPermissions.1.PrefixListIds.1.PrefixListId", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<isEgress>true</isEgress>"))
            .body(containsString("<prefixListId>" + prefixListId + "</prefixListId>"));

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<isEgress>true</isEgress>"))
            .body(containsString("<prefixListId>" + prefixListId + "</prefixListId>"));
    }

    @Test
    void revokeRemovesTheRuleByItsPrefixListSource() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("target-pl-revoke"), vpcId);
        String prefixListId = createManagedPrefixList(uniqueName("revoke"));

        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "udp")
            .formParam("IpPermissions.1.FromPort", "53")
            .formParam("IpPermissions.1.ToPort", "53")
            .formParam("IpPermissions.1.PrefixListIds.1.PrefixListId", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "RevokeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "udp")
            .formParam("IpPermissions.1.FromPort", "53")
            .formParam("IpPermissions.1.ToPort", "53")
            .formParam("IpPermissions.1.PrefixListIds.1.PrefixListId", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<isEgress>false</isEgress>")));
    }

    private static void assertContains(String haystack, String needle, String message) {
        if (!haystack.contains(needle)) {
            throw new AssertionError(message + ": " + haystack);
        }
    }

    private String createVpc() {
        return given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.0.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    /** The suite shares one emulator and group/prefix-list names are unique per scope, so never reuse one. */
    private String uniqueName(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private String createSecurityGroup(String groupName, String vpcId) {
        return given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", groupName)
            .formParam("GroupDescription", groupName)
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSecurityGroupResponse.groupId");
    }

    private String createManagedPrefixList(String name) {
        return given()
            .formParam("Action", "CreateManagedPrefixList")
            .formParam("PrefixListName", name)
            .formParam("AddressFamily", "IPv4")
            .formParam("MaxEntries", "1")
            .formParam("Entry.1.Cidr", "10.0.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateManagedPrefixListResponse.prefixList.prefixListId");
    }

    private void authorizeCidrIngress(String groupId, String port, String cidr, String description) {
        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", port)
            .formParam("IpPermissions.1.ToPort", port)
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", cidr)
            .formParam("IpPermissions.1.IpRanges.1.Description", description)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
