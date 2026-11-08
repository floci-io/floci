package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnRequests;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CloudFormationSettingsProfile;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.WafV2Requests.waf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudFormationSettingsProfile.class)
class CloudFormationWafV2LoggingConfigurationIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void loggingConfigurationCreatesWithAwsRefAndGetAttAndIsDeletedWithItsStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-logging-" + suffix;

        cfn("CreateStack", stackName, template(suffix, "AclA", "LogGroupA", false));
        CfnStackWaits.StackState created = CfnStackWaits.awaitTerminal(stackName, CFN_AUTH);
        assertEquals("CREATE_COMPLETE", created.status(),
                () -> CfnStackWaits.failedResourceReason(stackName, CFN_AUTH));

        String describeXml = describeStack(stackName);
        String aclArn = output(describeXml, "AclAArn");
        String destinationArn = output(describeXml, "LogGroupAArn");
        assertEquals(aclArn, output(describeXml, "LoggingRef"));
        assertEquals("false", output(describeXml, "ManagedByFirewallManager"));
        loggingConfiguration(aclArn)
            .statusCode(200)
            .body("LoggingConfiguration.ResourceArn", equalTo(aclArn))
            .body("LoggingConfiguration.LogDestinationConfigs", contains(destinationArn));
        assertNestedSettings(aclArn, "authorization", "KEEP");

        cfn("DeleteStack", stackName, null);
        CfnStackWaits.awaitStackDeleted(stackName, CFN_AUTH);
        loggingConfiguration(aclArn)
            .statusCode(400)
            .body("__type", equalTo("WAFNonexistentItemException"));
    }

    @Test
    void loggingConfigurationUpdatesInPlaceAndReplacesOnlyAfterCommit() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-logging-update-" + suffix;

        cfn("CreateStack", stackName, template(suffix, "AclA", "LogGroupA", false));
        assertStatus(stackName, "CREATE_COMPLETE");
        String created = describeStack(stackName);
        String aclA = output(created, "AclAArn");
        String aclB = output(created, "AclBArn");
        String logA = output(created, "LogGroupAArn");
        String logB = output(created, "LogGroupBArn");
        assertStored(aclA, logA);

        String changeSetName = "wafv2-logging-replacement-" + suffix;
        CfnRequests.cfn("CreateChangeSet", stackName)
            .header("Authorization", CFN_AUTH)
            .formParam("ChangeSetName", changeSetName)
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template(suffix, "AclB", "LogGroupB", false))
        .when().post("/").then().statusCode(200);
        String changeSetXml = CfnRequests.cfn("DescribeChangeSet", stackName)
            .header("Authorization", CFN_AUTH)
            .formParam("ChangeSetName", changeSetName)
        .when().post("/").then().statusCode(200).extract().asString();
        assertEquals("True", XmlPath.from(changeSetXml).getString(
                "DescribeChangeSetResponse.DescribeChangeSetResult.Changes.member"
                + ".find { it.ResourceChange.LogicalResourceId == 'Logging' }.ResourceChange.Replacement"));

        cfn("UpdateStack", stackName, template(suffix, "AclA", "LogGroupB", false));
        assertStatus(stackName, "UPDATE_COMPLETE");
        assertEquals(aclA, output(describeStack(stackName), "LoggingRef"));
        assertStored(aclA, logB);
        assertNestedSettings(aclA, "cookie", "DROP");

        cfn("UpdateStack", stackName, template(suffix, "AclA", "LogGroupA", true));
        assertStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");
        assertStored(aclA, logB);
        assertNestedSettings(aclA, "cookie", "DROP");

        cfn("UpdateStack", stackName, template(suffix, "AclB", "LogGroupB", false));
        assertStatus(stackName, "UPDATE_COMPLETE");
        assertEquals(aclB, output(describeStack(stackName), "LoggingRef"));
        assertStored(aclB, logB);
        assertMissing(aclA);

        cfn("UpdateStack", stackName, template(suffix, "AclA", "LogGroupA", true));
        assertStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(aclB, output(describeStack(stackName), "LoggingRef"));
        assertStored(aclB, logB);
        assertMissing(aclA);

        deleteLoggingConfiguration(aclB).statusCode(200);
        assertMissing(aclB);
        cfn("UpdateStack", stackName, template(suffix, "AclB", "LogGroupA", true));
        assertStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");
        assertTrue(CfnStackWaits.failedResourceReason(stackName, CFN_AUTH).contains("AWS::Fake::Thing"));
        assertMissing(aclB);

        cfn("DeleteStack", stackName, null);
        CfnStackWaits.awaitStackDeleted(stackName, CFN_AUTH);
        assertMissing(aclB);
    }

    @Test
    void replacementDoesNotOverwriteLoggingConfigurationOnTargetAcl() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-logging-collision-" + suffix;

        cfn("CreateStack", stackName, template(suffix, "AclA", "LogGroupA", false));
        assertStatus(stackName, "CREATE_COMPLETE");
        String created = describeStack(stackName);
        String aclA = output(created, "AclAArn");
        String aclB = output(created, "AclBArn");
        String logA = output(created, "LogGroupAArn");
        putLoggingConfiguration(aclB, logA).statusCode(200);

        cfn("UpdateStack", stackName, template(suffix, "AclB", "LogGroupB", false));
        assertStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(aclA, output(describeStack(stackName), "LoggingRef"));
        assertStored(aclA, logA);
        assertStored(aclB, logA);

        deleteLoggingConfiguration(aclB).statusCode(200);
        cfn("DeleteStack", stackName, null);
        CfnStackWaits.awaitStackDeleted(stackName, CFN_AUTH);
    }

    private static String template(String suffix, String acl, String logGroup, boolean failAfterLogging) {
        String redactedHeader = "LogGroupA".equals(logGroup) ? "authorization" : "cookie";
        String defaultBehavior = "LogGroupA".equals(logGroup) ? "KEEP" : "DROP";
        String failingResource = failAfterLogging
                ? """
                  ,
                  "ZFail": {
                    "Type": "AWS::Fake::Thing",
                    "DependsOn": "Logging",
                    "Properties": {}
                  }
                  """
                : "";
        return """
                {
                  "Resources": {
                    "AclA": {
                      "Type": "AWS::WAFv2::WebACL",
                      "Properties": {
                        "Name": "cfn-wafv2-logging-a-%1$s",
                        "Scope": "REGIONAL",
                        "DefaultAction": {"Allow": {}},
                        "VisibilityConfig": {
                          "SampledRequestsEnabled": false,
                          "CloudWatchMetricsEnabled": false,
                          "MetricName": "cfn-wafv2-logging-a-%1$s"
                        }
                      }
                    },
                    "AclB": {
                      "Type": "AWS::WAFv2::WebACL",
                      "Properties": {
                        "Name": "cfn-wafv2-logging-b-%1$s",
                        "Scope": "REGIONAL",
                        "DefaultAction": {"Allow": {}},
                        "VisibilityConfig": {
                          "SampledRequestsEnabled": false,
                          "CloudWatchMetricsEnabled": false,
                          "MetricName": "cfn-wafv2-logging-b-%1$s"
                        }
                      }
                    },
                    "LogGroupA": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {"LogGroupName": "aws-waf-logs-cfn-%1$s-a"}
                    },
                    "LogGroupB": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {"LogGroupName": "aws-waf-logs-cfn-%1$s-b"}
                    },
                    "Logging": {
                      "Type": "AWS::WAFv2::LoggingConfiguration",
                      "Properties": {
                        "ResourceArn": {"Fn::GetAtt": ["%2$s", "Arn"]},
                        "LogDestinationConfigs": [{"Fn::GetAtt": ["%3$s", "Arn"]}],
                        "RedactedFields": [{"SingleHeader": {"Name": "%5$s"}}],
                        "LoggingFilter": {
                          "DefaultBehavior": "%6$s",
                          "Filters": [{
                            "Behavior": "KEEP",
                            "Requirement": "MEETS_ANY",
                            "Conditions": [{"ActionCondition": {"Action": "BLOCK"}}]
                          }]
                        }
                      }
                    }%4$s
                  },
                  "Outputs": {
                    "AclAArn": {"Value": {"Fn::GetAtt": ["AclA", "Arn"]}},
                    "AclBArn": {"Value": {"Fn::GetAtt": ["AclB", "Arn"]}},
                    "LogGroupAArn": {"Value": {"Fn::GetAtt": ["LogGroupA", "Arn"]}},
                    "LogGroupBArn": {"Value": {"Fn::GetAtt": ["LogGroupB", "Arn"]}},
                    "LoggingRef": {"Value": {"Ref": "Logging"}},
                    "ManagedByFirewallManager": {
                      "Value": {"Fn::GetAtt": ["Logging", "ManagedByFirewallManager"]}
                    }
                  }
                }
                """.formatted(suffix, acl, logGroup, failingResource, redactedHeader, defaultBehavior);
    }

    private static String output(String describeXml, String key) {
        return XmlPath.from(describeXml).getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member"
                + ".Outputs.member.find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private void assertStatus(String stackName, String expected) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName, CFN_AUTH);
        assertEquals(expected, state.status(), state.reason());
    }

    private void assertStored(String resourceArn, String destinationArn) {
        loggingConfiguration(resourceArn)
            .statusCode(200)
            .body("LoggingConfiguration.ResourceArn", equalTo(resourceArn))
            .body("LoggingConfiguration.LogDestinationConfigs", contains(destinationArn));
    }

    private void assertNestedSettings(String resourceArn, String redactedHeader, String defaultBehavior) {
        loggingConfiguration(resourceArn)
            .statusCode(200)
            .body("LoggingConfiguration.RedactedFields[0].SingleHeader.Name", equalTo(redactedHeader))
            .body("LoggingConfiguration.LoggingFilter.DefaultBehavior", equalTo(defaultBehavior))
            .body("LoggingConfiguration.LoggingFilter.Filters[0].Conditions[0].ActionCondition.Action",
                    equalTo("BLOCK"));
    }

    private void assertMissing(String resourceArn) {
        loggingConfiguration(resourceArn)
            .statusCode(400)
            .body("__type", equalTo("WAFNonexistentItemException"));
    }

    private ValidatableResponse loggingConfiguration(String resourceArn) {
        return waf("GetLoggingConfiguration", "{\"ResourceArn\":\"" + resourceArn + "\"}");
    }

    private ValidatableResponse putLoggingConfiguration(String resourceArn, String destinationArn) {
        return waf("PutLoggingConfiguration", "{\"LoggingConfiguration\":{\"ResourceArn\":\""
                + resourceArn + "\",\"LogDestinationConfigs\":[\"" + destinationArn + "\"]}}");
    }

    private ValidatableResponse deleteLoggingConfiguration(String resourceArn) {
        return waf("DeleteLoggingConfiguration", "{\"ResourceArn\":\"" + resourceArn + "\"}");
    }

    private void cfn(String action, String stackName, String template) {
        RequestSpecification request = CfnRequests.cfn(action, stackName).header("Authorization", CFN_AUTH);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private String describeStack(String stackName) {
        return CfnRequests.cfn("DescribeStacks", stackName)
            .header("Authorization", CFN_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }
}
