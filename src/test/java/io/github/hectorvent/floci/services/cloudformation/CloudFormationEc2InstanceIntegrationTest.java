package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                    "InstanceId": {"Value": {"Ref": "Server"}},
                    "StateName": {"Value": {"Fn::GetAtt": ["Server", "State.Name"]}},
                    "StateCode": {"Value": {"Fn::GetAtt": "Server.State.Code"}}
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
        String describeInstances = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(instanceId))
            .extract().asString();

        // The schema's State is a nested object, so Fn::GetAtt exposes State.Code and State.Name:
        // the state the launch settled to, as DescribeInstances reports it, in both GetAtt forms.
        Matcher state = Pattern.compile("<instanceState>\\s*<code>(\\d+)</code>\\s*<name>([a-z-]+)</name>")
                .matcher(describeInstances);
        assertTrue(state.find(), "expected an instance state in DescribeInstances");
        assertEquals("running", state.group(2));
        assertEquals(state.group(2), output(describeStacks, "StateName"));
        assertEquals(state.group(1), output(describeStacks, "StateCode"));
    }

    private static String output(String describeStacks, String key) {
        Matcher m = Pattern.compile("<OutputKey>" + key + "</OutputKey>\\s*<OutputValue>([^<]*)</OutputValue>")
                .matcher(describeStacks);
        assertTrue(m.find(), "expected output " + key + " in " + describeStacks);
        return m.group(1);
    }

    @Test
    void updateStackResizesInstanceTypeInPlaceWithoutReplacingTheInstance() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-ec2-resize-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Server": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {"ImageId": "ami-12345678", "InstanceType": "%s"}
                    }
                  },
                  "Outputs": {
                    "InstanceId": {"Value": {"Ref": "Server"}}
                  }
                }
                """;

        cfn("CreateStack", stackName, template.formatted("t3.micro"));
        String createXml = describeStacks(stackName, "CREATE_COMPLETE");
        Matcher m = Pattern.compile("<OutputValue>(i-[0-9a-fA-F]+)</OutputValue>").matcher(createXml);
        assertTrue(m.find(), "expected an instance id in the stack outputs");
        String instanceId = m.group(1);

        cfn("UpdateStack", stackName, template.formatted("t3.small"));
        String updateXml = describeStacks(stackName, "UPDATE_COMPLETE");

        // Ref is unchanged: InstanceType is mutable, so the instance was resized in place rather
        // than replaced with a new one.
        assertTrue(updateXml.contains("<OutputValue>" + instanceId + "</OutputValue>"),
                "instance id changed on update, so it was replaced not resized: " + updateXml);

        // DescribeInstances reports the new type on the same instance.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<instanceType>t3.small</instanceType>"));
    }

    @Test
    void changingPrivateIpAddressReplacesTheInstance() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-ec2-replace-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Server": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {"ImageId": "ami-12345678", "InstanceType": "t3.micro",
                                     "PrivateIpAddress": "%s"}
                    }
                  },
                  "Outputs": {
                    "InstanceId": {"Value": {"Ref": "Server"}}
                  }
                }
                """;

        cfn("CreateStack", stackName, template.formatted("10.0.0.5"));
        String firstId = instanceIdFrom(describeStacks(stackName, "CREATE_COMPLETE"));

        // PrivateIpAddress is createOnly, so changing it replaces the instance rather than reusing it.
        cfn("UpdateStack", stackName, template.formatted("10.0.0.6"));
        String secondId = instanceIdFrom(describeStacks(stackName, "UPDATE_COMPLETE"));

        assertNotEquals(firstId, secondId, "PrivateIpAddress changed, so the instance must be replaced");

        // The replacement instance really exists in EC2.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", secondId)
        .when().post("/").then().statusCode(200)
            .body(containsString(secondId));
    }

    private static void cfn(String action, String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stackName, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static String instanceIdFrom(String stackXml) {
        Matcher m = Pattern.compile("<OutputValue>(i-[0-9a-fA-F]+)</OutputValue>").matcher(stackXml);
        assertTrue(m.find(), "expected an instance id in the stack outputs: " + stackXml);
        return m.group(1);
    }
}
