package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that CloudFormation security-group rules exist for real
 * (issue #1992): inline SecurityGroupIngress/Egress properties on
 * AWS::EC2::SecurityGroup, and the standalone SecurityGroupIngress/Egress
 * resource types, all land in Ec2Service. Metadata-only — Docker-free.
 */
@QuarkusTest
class CloudFormationSecurityGroupRulesIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void inlineAndStandaloneRulesAreApplied() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-sgrules-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.50.0.0/16"}},
                    "WebSg": {
                      "Type": "AWS::EC2::SecurityGroup",
                      "Properties": {
                        "GroupDescription": "web",
                        "VpcId": {"Ref": "Vpc"},
                        "SecurityGroupIngress": [
                          {"IpProtocol": "tcp", "FromPort": 22, "ToPort": 22, "CidrIp": "0.0.0.0/0", "Description": "ssh"}
                        ]
                      }
                    },
                    "AppSg": {
                      "Type": "AWS::EC2::SecurityGroup",
                      "Properties": {"GroupDescription": "app", "VpcId": {"Ref": "Vpc"}}
                    },
                    "AppFromWeb": {
                      "Type": "AWS::EC2::SecurityGroupIngress",
                      "Properties": {
                        "GroupId": {"Fn::GetAtt": ["AppSg", "GroupId"]},
                        "IpProtocol": "tcp",
                        "FromPort": 8080,
                        "ToPort": 8080,
                        "SourceSecurityGroupId": {"Fn::GetAtt": ["WebSg", "GroupId"]}
                      }
                    },
                    "WebEgress": {
                      "Type": "AWS::EC2::SecurityGroupEgress",
                      "Properties": {
                        "GroupId": {"Fn::GetAtt": ["WebSg", "GroupId"]},
                        "IpProtocol": "tcp",
                        "FromPort": 443,
                        "ToPort": 443,
                        "CidrIp": "10.50.0.0/16"
                      }
                    }
                  },
                  "Outputs": {
                    "WebSgId": {"Value": {"Fn::GetAtt": ["WebSg", "GroupId"]}},
                    "AppSgId": {"Value": {"Fn::GetAtt": ["AppSg", "GroupId"]}}
                  }
                }
                """;

        String created = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        String stackId = between(created, "<StackId>", "</StackId>");

        String describe = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        String webSg = between(describe, "<OutputKey>WebSgId</OutputKey>", "</member>");
        webSg = between(webSg, "<OutputValue>", "</OutputValue>");
        String appSg = between(describe, "<OutputKey>AppSgId</OutputKey>", "</member>");
        appSg = between(appSg, "<OutputValue>", "</OutputValue>");

        // Inline SSH ingress exists on WebSg.
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", webSg)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<fromPort>22</fromPort>"))
            .body(containsString("0.0.0.0/0"))
            .body(containsString("<fromPort>443</fromPort>"));

        // Standalone ingress referencing WebSg exists on AppSg.
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", appSg)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<fromPort>8080</fromPort>"))
            .body(containsString(webSg));

        // Revoking rules whose group is deleted along with them must not fail the stack.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
        assertTrue(awaitStackStatus(stackId, "DELETE_COMPLETE"),
                "stack did not reach DELETE_COMPLETE");
    }

    @Test
    void inlineIpv6RuleKeepsItsDescription() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-sgrules-v6-" + suffix;
        String template = """
                {
                  "Resources": {
                    "V6Sg": {
                      "Type": "AWS::EC2::SecurityGroup",
                      "Properties": {
                        "GroupDescription": "v6",
                        "SecurityGroupIngress": [
                          {"IpProtocol": "tcp", "FromPort": 22, "ToPort": 22,
                           "CidrIpv6": "2001:db8:1992::/48", "Description": "ssh over v6"}
                        ]
                      }
                    }
                  },
                  "Outputs": {"SgId": {"Value": {"Fn::GetAtt": ["V6Sg", "GroupId"]}}}
                }
                """;
        createStack(stackName, template);
        String sgId = output(stackName, "SgId");

        // The description travels with the ipv6 range, not just the ipv4 one.
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", sgId)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<cidrIpv6>2001:db8:1992::/48</cidrIpv6>"))
            .body(containsString("<description>ssh over v6</description>"));
    }

    /**
     * A standalone rule can target a group that is not part of the stack. Deleting the stack has to
     * revoke that rule — and only that rule, not the group's own rule that shares its ports.
     */
    @Test
    void standaloneRuleOnAForeignGroupIsRevokedWithTheStack() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String groupId = createSecurityGroup("foreign-sg-" + suffix);
        authorizeIngress(groupId, "10.1.0.0/16");

        String stackName = "cfn-sgrules-delete-" + suffix;
        createStack(stackName, standaloneIngressTemplate(groupId, "10.2.0.0/16"));
        assertTrue(describeGroup(groupId).contains("<cidrIp>10.2.0.0/16</cidrIp>"),
                "stack should have authorized its rule on the foreign group");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);

        // DeleteStack is asynchronous.
        String group = null;
        for (int i = 0; i < 100; i++) {
            group = describeGroup(groupId);
            if (!group.contains("<cidrIp>10.2.0.0/16</cidrIp>")) {
                break;
            }
            Thread.sleep(50);
        }
        assertFalse(group.contains("<cidrIp>10.2.0.0/16</cidrIp>"),
                "stack deletion left the standalone rule on the group");
        assertTrue(group.contains("<cidrIp>10.1.0.0/16</cidrIp>"),
                "stack deletion revoked the group's own rule, which shares protocol and ports");

        // The rule record goes too, so DescribeSecurityGroupRules does not report a revoked rule.
        String rules = given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", groupId)
            .header("Authorization", EC2_AUTH)
        .when().post("/").then().statusCode(200).extract().asString();
        assertEquals(0, occurrences(rules, "<cidrIpv4>10.2.0.0/16</cidrIpv4>"));
        assertEquals(1, occurrences(rules, "<cidrIpv4>10.1.0.0/16</cidrIpv4>"));
    }

    /** Re-running a template must not stack up duplicate permissions, or orphan replaced ones. */
    @Test
    void updateNeitherDuplicatesNorOrphansTheRule() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String groupId = createSecurityGroup("update-sg-" + suffix);
        String stackName = "cfn-sgrules-update-" + suffix;

        createStack(stackName, standaloneIngressTemplate(groupId, "10.3.0.0/16"));
        assertEquals(1, occurrences(describeGroup(groupId), "<cidrIp>10.3.0.0/16</cidrIp>"));

        // Unchanged template: the append-only authorize API would double the permission.
        updateStack(stackName, standaloneIngressTemplate(groupId, "10.3.0.0/16"));
        assertEquals(1, occurrences(describeGroup(groupId), "<cidrIp>10.3.0.0/16</cidrIp>"),
                "update duplicated an unchanged standalone rule");

        // Changed template: the old permission must go with it.
        updateStack(stackName, standaloneIngressTemplate(groupId, "10.4.0.0/16"));
        String group = describeGroup(groupId);
        assertEquals(1, occurrences(group, "<cidrIp>10.4.0.0/16</cidrIp>"));
        assertEquals(0, occurrences(group, "<cidrIp>10.3.0.0/16</cidrIp>"),
                "update left the replaced permission behind");
    }

    /** A deleted stack is only describable by id, as in AWS; it is retained for a grace period. */
    private static boolean awaitStackStatus(String stackId, String status) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackId)
            .when().post("/").then().extract().asString();
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static String standaloneIngressTemplate(String groupId, String cidr) {
        return """
                {
                  "Resources": {
                    "Rule": {
                      "Type": "AWS::EC2::SecurityGroupIngress",
                      "Properties": {
                        "GroupId": "%s",
                        "IpProtocol": "tcp",
                        "FromPort": 9100,
                        "ToPort": 9100,
                        "CidrIp": "%s"
                      }
                    }
                  }
                }
                """.formatted(groupId, cidr);
    }

    /** Creates the stack and returns its id, which is how a deleted stack is described. */
    private static String createStack(String stackName, String template) {
        String created = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200).extract().asString();
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));
        return between(created, "<StackId>", "</StackId>");
    }

    private static void updateStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private static String output(String stackName, String key) {
        String describe = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200).extract().asString();
        String member = between(describe, "<OutputKey>" + key + "</OutputKey>", "</member>");
        return between(member, "<OutputValue>", "</OutputValue>");
    }

    private static String createSecurityGroup(String groupName) {
        String response = given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", groupName)
            .formParam("GroupDescription", "not managed by the stack")
            .header("Authorization", EC2_AUTH)
        .when().post("/").then().statusCode(200).extract().asString();
        return between(response, "<groupId>", "</groupId>");
    }

    private static void authorizeIngress(String groupId, String cidr) {
        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "9100")
            .formParam("IpPermissions.1.ToPort", "9100")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", cidr)
            .header("Authorization", EC2_AUTH)
        .when().post("/").then().statusCode(200);
    }

    private static String describeGroup(String groupId) {
        return given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", EC2_AUTH)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static String between(String haystack, String open, String close) {
        int i = haystack.indexOf(open);
        int j = haystack.indexOf(close, i + open.length());
        return haystack.substring(i + open.length(), j);
    }
}
