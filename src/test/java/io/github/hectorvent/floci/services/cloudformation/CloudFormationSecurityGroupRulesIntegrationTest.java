package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

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
    void inlineAndStandaloneRulesAreApplied() {
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

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

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
    }

    private static String between(String haystack, String open, String close) {
        int i = haystack.indexOf(open);
        int j = haystack.indexOf(close, i + open.length());
        return haystack.substring(i + open.length(), j);
    }
}
