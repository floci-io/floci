package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end check that Fn::GetAtt [Vpc, DefaultSecurityGroup] resolves to the real
 * default security group id of a stack-created VPC (issue #1976) instead of passing
 * through as the literal "LogicalId.DefaultSecurityGroup".
 */
@QuarkusTest
class CloudFormationVpcDefaultSgAttrIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Test
    void getAttDefaultSecurityGroupResolvesToRealSgId() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-vpc-defsg-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Vpc": {
                      "Type": "AWS::EC2::VPC",
                      "Properties": {"CidrBlock": "10.43.0.0/16"}
                    }
                  },
                  "Outputs": {
                    "DefaultSg": {"Value": {"Fn::GetAtt": ["Vpc", "DefaultSecurityGroup"]}}
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
            .body(containsString("<OutputValue>sg-"))
            .body(not(containsString("Vpc.DefaultSecurityGroup")));
    }
}
