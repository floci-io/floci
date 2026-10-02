package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AWS::WAFv2::WebACLAssociation through a stack: Ref is {@code ResourceArn|WebACLArn}, switching the
 * ACL replaces the association without the cleanup of the old pair disassociating the resource,
 * and deleting the stack disassociates it. Every assertion reads WAFv2 back, not only the stack.
 */
@QuarkusTest
class CloudFormationWafV2WebAclAssociationIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String WAF_JSON = "application/x-amz-json-1.1";
    private static final String WAF_TARGET_PREFIX = "AWSWAF_20190729.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void associationFollowsTheTemplateAcrossReplacementAndStackDelete() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-assoc-" + suffix;

        cfn("CreateStack", stackName, template(suffix, "AclA"));
        String created = describeStack(stackName)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();
        String poolArn = output(created, "PoolArn");
        String aclAArn = output(created, "AclAArn");
        String aclBArn = output(created, "AclBArn");

        assertEquals(poolArn + "|" + aclAArn, output(created, "AssocRef"));
        webAclForResource(poolArn).body("WebACL.ARN", equalTo(aclAArn));

        cfn("UpdateStack", stackName, template(suffix, "AclB"));
        String updated = describeStack(stackName)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("ROLLBACK")))
            .extract().asString();

        assertEquals(poolArn + "|" + aclBArn, output(updated, "AssocRef"));
        // The cleanup deleted the old pair, which must not have disassociated the pool from AclB.
        webAclForResource(poolArn).body("WebACL.ARN", equalTo(aclBArn));
        resourcesForWebAcl(aclAArn).body("ResourceArns", empty());
        resourcesForWebAcl(aclBArn).body("ResourceArns", contains(poolArn));

        cfn("DeleteStack", stackName, null);

        webAclForResource(poolArn).body("WebACL", nullValue());
        waf("ListWebACLs", "{\"Scope\":\"REGIONAL\"}")
            .statusCode(200)
            .body("WebACLs.ARN", not(hasItem(aclAArn)))
            .body("WebACLs.ARN", not(hasItem(aclBArn)));
    }

    private static String template(String suffix, String associatedAcl) {
        return """
                {
                  "Resources": {
                    "Pool": {
                      "Type": "AWS::Cognito::UserPool",
                      "Properties": {"UserPoolName": "cfn-assoc-pool-%1$s"}
                    },
                    "AclA": {
                      "Type": "AWS::WAFv2::WebACL",
                      "Properties": {
                        "Name": "cfn-assoc-a-%1$s",
                        "Scope": "REGIONAL",
                        "DefaultAction": {"Allow": {}},
                        "VisibilityConfig": {
                          "SampledRequestsEnabled": false,
                          "CloudWatchMetricsEnabled": false,
                          "MetricName": "cfn-assoc-a-%1$s"
                        }
                      }
                    },
                    "AclB": {
                      "Type": "AWS::WAFv2::WebACL",
                      "Properties": {
                        "Name": "cfn-assoc-b-%1$s",
                        "Scope": "REGIONAL",
                        "DefaultAction": {"Block": {}},
                        "VisibilityConfig": {
                          "SampledRequestsEnabled": false,
                          "CloudWatchMetricsEnabled": false,
                          "MetricName": "cfn-assoc-b-%1$s"
                        }
                      }
                    },
                    "Assoc": {
                      "Type": "AWS::WAFv2::WebACLAssociation",
                      "Properties": {
                        "ResourceArn": {"Fn::GetAtt": ["Pool", "Arn"]},
                        "WebACLArn": {"Fn::GetAtt": ["%2$s", "Arn"]}
                      }
                    }
                  },
                  "Outputs": {
                    "AssocRef": {"Value": {"Ref": "Assoc"}},
                    "PoolArn": {"Value": {"Fn::GetAtt": ["Pool", "Arn"]}},
                    "AclAArn": {"Value": {"Fn::GetAtt": ["AclA", "Arn"]}},
                    "AclBArn": {"Value": {"Fn::GetAtt": ["AclB", "Arn"]}}
                  }
                }
                """.formatted(suffix, associatedAcl);
    }

    private static String output(String describeXml, String key) {
        return XmlPath.from(describeXml).getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member"
                + ".Outputs.member.find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private void cfn(String action, String stackName, String template) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private ValidatableResponse describeStack(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private ValidatableResponse webAclForResource(String resourceArn) {
        return waf("GetWebACLForResource", "{\"ResourceArn\":\"" + resourceArn + "\"}").statusCode(200);
    }

    private ValidatableResponse resourcesForWebAcl(String webAclArn) {
        return waf("ListResourcesForWebACL",
                "{\"WebACLArn\":\"" + webAclArn + "\",\"ResourceType\":\"COGNITO_USER_POOL\"}").statusCode(200);
    }

    private ValidatableResponse waf(String action, String body) {
        return given()
            .contentType(WAF_JSON)
            .header("X-Amz-Target", WAF_TARGET_PREFIX + action)
            .body(body)
        .when()
            .post("/")
        .then();
    }
}
