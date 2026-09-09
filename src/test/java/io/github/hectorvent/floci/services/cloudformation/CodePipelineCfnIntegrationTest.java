package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Provisions an {@code AWS::CodePipeline::Pipeline} through a CloudFormation stack and
 * asserts the specific {@code Ref} and {@code Fn::GetAtt} values — a status-only
 * assertion would pass even for an unmapped type stubbed as a successful no-op.
 */
@QuarkusTest
class CodePipelineCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260808/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "codepipeline-cfn-it";

    private static final String TEMPLATE = """
        {
          "Resources": {
            "Pipeline": {
              "Type": "AWS::CodePipeline::Pipeline",
              "Properties": {
                "Name": "cfn-provisioned-pipeline",
                "RoleArn": "arn:aws:iam::000000000000:role/cp",
                "ArtifactStore": {"Type": "S3", "Location": "cfn-artifacts"},
                "Stages": [
                  {
                    "Name": "Gate",
                    "Actions": [
                      {
                        "Name": "HumanGate",
                        "ActionTypeId": {"Category": "Approval", "Owner": "AWS",
                                         "Provider": "Manual", "Version": "1"},
                        "RunOrder": 1
                      }
                    ]
                  },
                  {
                    "Name": "Release",
                    "Actions": [
                      {
                        "Name": "ReleaseGate",
                        "ActionTypeId": {"Category": "Approval", "Owner": "AWS",
                                         "Provider": "Manual", "Version": "1"},
                        "RunOrder": 1
                      }
                    ]
                  }
                ]
              }
            }
          },
          "Outputs": {
            "PipelineRef": {"Value": {"Ref": "Pipeline"}},
            "PipelineVersion": {"Value": {"Fn::GetAtt": ["Pipeline", "Version"]}}
          }
        }
        """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void pipelineStackExposesRefAndVersionAndCreatesThePipeline() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", STACK)
            .formParam("TemplateBody", TEMPLATE)
        .when().post("/").then().statusCode(200);

        String stacks = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200).extract().asString();

        assertEquals("cfn-provisioned-pipeline", outputValue(stacks, "PipelineRef"));
        assertEquals("1", outputValue(stacks, "PipelineVersion"));

        given()
            .header("X-Amz-Target", "CodePipeline_20150709.GetPipeline")
            .contentType("application/x-amz-json-1.1")
            .body("{\"name\": \"cfn-provisioned-pipeline\"}")
        .when().post("/").then()
            .statusCode(200)
            .body("pipeline.name", equalTo("cfn-provisioned-pipeline"))
            .body("pipeline.stages[0].name", equalTo("Gate"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200);

        given()
            .header("X-Amz-Target", "CodePipeline_20150709.GetPipeline")
            .contentType("application/x-amz-json-1.1")
            .body("{\"name\": \"cfn-provisioned-pipeline\"}")
        .when().post("/").then()
            .statusCode(400);
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
