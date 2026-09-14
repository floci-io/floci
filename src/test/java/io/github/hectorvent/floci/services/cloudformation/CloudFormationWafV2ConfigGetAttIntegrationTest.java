package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end check that CloudFormation registers real {@code Fn::GetAtt} attributes for
 * AWS::WAFv2::WebACL and AWS::Config::ConfigRule rather than leaving them to resolve to
 * literal placeholders. Both resources are metadata (no container), so this is Docker-free.
 */
@QuarkusTest
class CloudFormationWafV2ConfigGetAttIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String WAF_JSON = "application/x-amz-json-1.1";
    private static final String WAF_TARGET_PREFIX = "AWSWAF_20190729.";
    private static final String CONFIG_JSON = "application/x-amz-json-1.1";
    private static final String CONFIG_TARGET_PREFIX = "StarlingDoveService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStackProvisionsWebAclAndResolvesGetAtt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String aclName = "cfn-acl-" + suffix;
        String stackName = "cfn-wafv2-getatt-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Acl": {
                      "Type": "AWS::WAFv2::WebACL",
                      "Properties": {
                        "Name": "%s",
                        "Scope": "REGIONAL",
                        "DefaultAction": {"Allow": {}},
                        "VisibilityConfig": {
                          "SampledRequestsEnabled": true,
                          "CloudWatchMetricsEnabled": true,
                          "MetricName": "%s"
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "AclArn": {"Value": {"Fn::GetAtt": ["Acl", "Arn"]}},
                    "AclId": {"Value": {"Fn::GetAtt": ["Acl", "Id"]}}
                  }
                }
                """.formatted(aclName, aclName);

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

        String describeBody = given()
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

        // The GetAtt Id output must be the real WAFv2 WebACL id, not a synthetic placeholder —
        // recover it from the Outputs and confirm the ACL is really registered under it.
        String idMarker = "<OutputKey>AclId</OutputKey><OutputValue>";
        int idStart = describeBody.indexOf(idMarker) + idMarker.length();
        String aclId = describeBody.substring(idStart, describeBody.indexOf("</OutputValue>", idStart));

        given()
            .contentType(WAF_JSON)
            .header("X-Amz-Target", WAF_TARGET_PREFIX + "GetWebACL")
            .body("{\"Name\":\"" + aclName + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + aclId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WebACL.Name", org.hamcrest.Matchers.equalTo(aclName));
    }

    @Test
    void createStackProvisionsConfigRuleAndResolvesGetAtt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String ruleName = "cfn-rule-" + suffix;
        String stackName = "cfn-config-getatt-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Rule": {
                      "Type": "AWS::Config::ConfigRule",
                      "Properties": {
                        "ConfigRuleName": "%s",
                        "Source": {
                          "Owner": "AWS",
                          "SourceIdentifier": "S3_BUCKET_PUBLIC_READ_PROHIBITED"
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "RuleArn": {"Value": {"Fn::GetAtt": ["Rule", "Arn"]}},
                    "RuleId": {"Value": {"Fn::GetAtt": ["Rule", "ConfigRuleId"]}}
                  }
                }
                """.formatted(ruleName);

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

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<OutputKey>RuleArn</OutputKey>"))
            .body(containsString("<OutputKey>RuleId</OutputKey>"));

        // The rule really exists in AWS Config, addressable by the name CloudFormation used.
        given()
            .contentType(CONFIG_JSON)
            .header("X-Amz-Target", CONFIG_TARGET_PREFIX + "DescribeConfigRules")
            .body("{\"ConfigRuleNames\":[\"" + ruleName + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules[0].ConfigRuleName", org.hamcrest.Matchers.equalTo(ruleName));
    }

    @Test
    void createStackWithoutWebAclVisibilityConfigFailsWithRequiredPropertyReason() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-required-stack-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Acl": {
                      "Type": "AWS::WAFv2::WebACL",
                      "Properties": {
                        "Name": "cfn-acl-required-%s",
                        "Scope": "REGIONAL",
                        "DefaultAction": {"Allow": {}}
                      }
                    }
                  }
                }
                """.formatted(suffix);

        createStack(stackName, template);

        describeStackEvents(stackName)
            .body(containsString("<ResourceStatus>CREATE_FAILED</ResourceStatus>"))
            .body(containsString(
                    "<ResourceStatusReason>AWS::WAFv2::WebACL requires VisibilityConfig</ResourceStatusReason>"));
    }

    @Test
    void createStackWithoutConfigRuleSourceFailsWithRequiredPropertyReason() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-config-required-stack-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Rule": {
                      "Type": "AWS::Config::ConfigRule",
                      "Properties": {
                        "ConfigRuleName": "cfn-rule-required-%s"
                      }
                    }
                  }
                }
                """.formatted(suffix);

        createStack(stackName, template);

        describeStackEvents(stackName)
            .body(containsString("<ResourceStatus>CREATE_FAILED</ResourceStatus>"))
            .body(containsString(
                    "<ResourceStatusReason>AWS::Config::ConfigRule requires Source</ResourceStatusReason>"));
    }

    @Test
    void configRuleTagScopeOmitsComplianceResourceTypes() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String ruleName = "cfn-rule-tagscope-" + suffix;
        String stackName = "cfn-config-tagscope-stack-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Rule": {
                      "Type": "AWS::Config::ConfigRule",
                      "Properties": {
                        "ConfigRuleName": "%s",
                        "Scope": {"TagKey": "env", "TagValue": "prod"},
                        "Source": {"Owner": "AWS", "SourceIdentifier": "REQUIRED_TAGS"}
                      }
                    }
                  }
                }
                """.formatted(ruleName);

        createStack(stackName, template);

        given()
            .contentType(CONFIG_JSON)
            .header("X-Amz-Target", CONFIG_TARGET_PREFIX + "DescribeConfigRules")
            .body("{\"ConfigRuleNames\":[\"" + ruleName + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules[0].Scope.TagKey", org.hamcrest.Matchers.equalTo("env"))
            .body("ConfigRules[0].Scope", not(hasKey("ComplianceResourceTypes")));
    }

    private void createStack(String stackName, String template) {
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
    }

    private ValidatableResponse describeStackEvents(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
