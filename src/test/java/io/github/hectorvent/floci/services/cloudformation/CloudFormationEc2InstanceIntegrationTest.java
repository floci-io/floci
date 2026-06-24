package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that CloudFormation provisions a real EC2 instance (into Ec2Service) rather than
 * stubbing it, so the exported instance id is visible to describe-instances. EC2 instances are
 * emulated as metadata (no container), so this stays Docker-free. Isolated to ap-southeast-2 so it
 * doesn't pollute the shared in-memory Ec2Service state asserted by us-east-1 EC2 tests.
 */
@QuarkusTest
class CloudFormationEc2InstanceIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/ap-southeast-2/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/ap-southeast-2/ec2/aws4_request";

    @Test
    void createStackProvisionsRealEc2Instance() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-ec2-instance-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Server": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {"ImageId": "ami-12345678", "InstanceType": "t3.micro"}
                    }
                  },
                  "Outputs": {
                    "InstanceId": {"Value": {"Ref": "Server"}}
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

        String describeStacks = given()
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

        // Ref(Server) exports a real instance id (i-...), not the stub shape.
        Matcher m = Pattern.compile("<OutputValue>(i-[0-9a-fA-F]+)</OutputValue>").matcher(describeStacks);
        assertTrue(m.find(), "expected an instance id in the stack outputs");
        String instanceId = m.group(1);

        // The instance really exists in EC2.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(instanceId));
    }
}
