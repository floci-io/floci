package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #2190: a security group rule whose source is another security group must keep that source.
 *
 * <p>Transcribes the reported repro: authorize tcp/443 from a source group, plus a tcp/8443 CIDR
 * rule as the control, then read both back. The source has to survive on three surfaces, since the
 * report shows it lost on all three: the rule echoed by AuthorizeSecurityGroupIngress,
 * DescribeSecurityGroups ({@code UserIdGroupPairs}) and DescribeSecurityGroupRules
 * ({@code ReferencedGroupInfo}).
 */
@QuarkusTest
class Ec2SecurityGroupRuleSourcesIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String ACCOUNT_ID = "000000000000";

    @Test
    void groupReferenceSurvivesAuthorizeAndBothDescribes() {
        String vpcId = createVpc();
        String sourceId = createSecurityGroup(uniqueName("source-ingress"), vpcId);
        String targetId = createSecurityGroup(uniqueName("target-ingress"), vpcId);

        // The rule under test: allow tcp/443 from another security group.
        String authorizeBody = given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", targetId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "443")
            .formParam("IpPermissions.1.ToPort", "443")
            .formParam("IpPermissions.1.Groups.1.GroupId", sourceId)
            .formParam("IpPermissions.1.Groups.1.Description", "from-source-sg")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();

        // The echoed rule is the first symptom in the report, so assert it before the describes.
        // The exact fragment also pins the shape: a bare object with scalar children (no <item>
        // wrapper), with description as a sibling rather than a child.
        assertTrue(
                authorizeBody.contains(referencedGroupInfoXml(sourceId)
                        + "<description>from-source-sg</description>"),
                "AuthorizeSecurityGroupIngress must echo the source group, got: " + authorizeBody);

        // Control: the same group with a CIDR rule, which already worked.
        authorizeCidrIngress(targetId, "8443", "10.0.0.0/8", "control");

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", targetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[0].fromPort",
                    equalTo("443"))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[0].groups.item.groupId",
                    equalTo(sourceId))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[0].groups.item.userId",
                    equalTo(ACCOUNT_ID))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[0].groups.item.description",
                    equalTo("from-source-sg"))
            // The CIDR control is unchanged by the fan-out change.
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[1].ipRanges.item.cidrIp",
                    equalTo("10.0.0.0/8"))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[1].ipRanges.item.description",
                    equalTo("control"));

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", targetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(referencedGroupInfoXml(sourceId)
                    + "<description>from-source-sg</description>"))
            .body(containsString("<cidrIpv4>10.0.0.0/8</cidrIpv4>"))
            // The group-reference rule carries a group, not a CIDR, and vice versa.
            .body(not(containsString("<cidrIpv4>10.0.0.0/8</cidrIpv4><referencedGroupInfo>")));
    }

    @Test
    void groupReferenceSurvivesOnEgress() {
        String vpcId = createVpc();
        String sourceId = createSecurityGroup(uniqueName("source-egress"), vpcId);
        String targetId = createSecurityGroup(uniqueName("target-egress"), vpcId);

        given()
            .formParam("Action", "AuthorizeSecurityGroupEgress")
            .formParam("GroupId", targetId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "5432")
            .formParam("IpPermissions.1.ToPort", "5432")
            .formParam("IpPermissions.1.Groups.1.GroupId", sourceId)
            .formParam("IpPermissions.1.Groups.1.Description", "to-database-sg")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<isEgress>true</isEgress>"))
            .body(containsString(referencedGroupInfoXml(sourceId)));

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", targetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // [0] is the default allow-all egress created with the group.
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissionsEgress.item[1]"
                    + ".groups.item.groupId", equalTo(sourceId))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissionsEgress.item[1]"
                    + ".groups.item.description", equalTo("to-database-sg"));

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", targetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(referencedGroupInfoXml(sourceId)
                    + "<description>to-database-sg</description>"));
    }

    @Test
    void groupReferenceByNameResolvesToGroupId() {
        String vpcId = createVpc();
        String sourceName = uniqueName("source-by-name");
        String sourceId = createSecurityGroup(sourceName, vpcId);
        String targetId = createSecurityGroup(uniqueName("target-by-name"), vpcId);

        // A default-VPC caller may name the source group instead of identifying it, and
        // ReferencedGroupInfo has no group name, so the id has to be resolved on the way in.
        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", targetId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "80")
            .formParam("IpPermissions.1.ToPort", "80")
            .formParam("IpPermissions.1.Groups.1.GroupName", sourceName)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(referencedGroupInfoXml(sourceId)));

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", targetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item.groups.item.groupId",
                    equalTo(sourceId))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item.groups.item.groupName",
                    equalTo(sourceName));
    }

    @Test
    void groupNameResolvesWithinTheTargetVpcOnly() {
        String otherVpcId = createVpc();
        String targetVpcId = createVpc();
        // Group names are unique per VPC, not per region, so the same name can exist in both. Only
        // the one sharing the target group's VPC may be resolved.
        String sharedName = uniqueName("shared-name");
        String decoyId = createSecurityGroup(sharedName, otherVpcId);
        String wantedId = createSecurityGroup(sharedName, targetVpcId);
        String targetId = createSecurityGroup(uniqueName("target-scoped"), targetVpcId);

        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", targetId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "8080")
            .formParam("IpPermissions.1.ToPort", "8080")
            .formParam("IpPermissions.1.Groups.1.GroupName", sharedName)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(referencedGroupInfoXml(wantedId)))
            .body(not(containsString(decoyId)));
    }

    @Test
    void unknownGroupNameLeavesTheReferenceWithoutAnId() {
        String vpcId = createVpc();
        String targetId = createSecurityGroup(uniqueName("target-unknown-name"), vpcId);

        // Floci does not validate the source group's existence on authorize, so a name that matches
        // nothing must still round-trip as a name rather than fabricating an id or failing.
        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", targetId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "9000")
            .formParam("IpPermissions.1.ToPort", "9000")
            .formParam("IpPermissions.1.Groups.1.GroupName", uniqueName("no-such-group"))
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<referencedGroupInfo><userId>" + ACCOUNT_ID + "</userId>"
                    + "</referencedGroupInfo>"));
    }

    @Test
    void mixedSourcesFanOutToOneRulePerSource() {
        String vpcId = createVpc();
        String sourceId = createSecurityGroup(uniqueName("source-mixed"), vpcId);
        String targetId = createSecurityGroup(uniqueName("target-mixed"), vpcId);

        // One permission, four sources. AWS gives every rule exactly one source, so this must
        // produce four rules and no sourceless one.
        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", targetId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "3306")
            .formParam("IpPermissions.1.ToPort", "3306")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.1.0.0/16")
            .formParam("IpPermissions.1.IpRanges.2.CidrIp", "10.2.0.0/16")
            .formParam("IpPermissions.1.Ipv6Ranges.1.CidrIpv6", "2001:db8::/32")
            .formParam("IpPermissions.1.Ipv6Ranges.1.Description", "v6-range")
            .formParam("IpPermissions.1.Groups.1.GroupId", sourceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AuthorizeSecurityGroupIngressResponse.securityGroupRuleSet.item.size()", equalTo(4));

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", targetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // The four fanned-out rules plus the group's own default egress rule.
            .body("DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item.size()", equalTo(5))
            .body("DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item.cidrIpv4",
                    hasItems("10.1.0.0/16", "10.2.0.0/16"))
            .body(containsString("<cidrIpv6>2001:db8::/32</cidrIpv6><description>v6-range</description>"))
            .body(containsString(referencedGroupInfoXml(sourceId)));

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", targetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item.ipv6Ranges"
                    + ".item.cidrIpv6", equalTo("2001:db8::/32"))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item.ipv6Ranges"
                    + ".item.description", equalTo("v6-range"));
    }

    private String referencedGroupInfoXml(String groupId) {
        return "<referencedGroupInfo><groupId>" + groupId + "</groupId>"
                + "<userId>" + ACCOUNT_ID + "</userId></referencedGroupInfo>";
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

    /** The suite shares one emulator and group names are unique per VPC, so never reuse one. */
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
