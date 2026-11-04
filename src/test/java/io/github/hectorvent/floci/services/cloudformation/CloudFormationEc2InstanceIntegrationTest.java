package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Inject
    Ec2Service ec2;

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/ap-southeast-2/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/ap-southeast-2/ec2/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    @Test
    void tagUpdatesPreserveServiceOwnedInstanceAndSubnetTags() {
        String region = "ap-southeast-2";
        String stackName = "cfn-ec2-owned-tags-" + Long.toString(System.nanoTime(), 36);
        String template = """
                {"Resources": {
                  "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.193.0.0/16"}},
                  "Subnet": {"Type": "AWS::EC2::Subnet", "Properties": {
                    "VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.193.1.0/24", "Tags": %s}},
                  "Server": {"Type": "AWS::EC2::Instance", "Properties": {
                    "ImageId": "ami-12345678", "InstanceType": "t3.micro",
                    "SubnetId": {"Ref": "Subnet"}, "Tags": %s}}
                }, "Outputs": {
                  "InstanceId": {"Value": {"Ref": "Server"}},
                  "SubnetId": {"Value": {"Ref": "Subnet"}}
                }}
                """;
        String original = """
                [{"Key": "Name", "Value": "before"}, {"Key": "Removed", "Value": "before"}]
                """;
        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                .formParam("Action", "CreateStack").formParam("StackName", stackName)
                .formParam("TemplateBody", template.formatted(original, original))
                .when().post("/").then().statusCode(200);
        try {
            awaitStackStatus(stackName, "CREATE_COMPLETE");
            String stack = given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                    .formParam("Action", "DescribeStacks").formParam("StackName", stackName)
                    .when().post("/").then().statusCode(200)
                    .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>")).extract().asString();
            List<String> ids = List.of(output(stack, "InstanceId"), output(stack, "SubnetId"));
            Map<String, String> managed = Map.of("aws:autoscaling:groupName", "attached-group",
                    "aws:cloudformation:stack-id", stackName, "AWS:service", "owner");
            ec2.createSystemTags(region, ids, managed.entrySet().stream()
                    .map(entry -> new Tag(entry.getKey(), entry.getValue())).toList());

            for (String desired : List.of("""
                    [{"Key": "Name", "Value": "after"}, {"Key": "Added", "Value": "after"}]
                    """, "[]")) {
                given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                        .formParam("Action", "UpdateStack").formParam("StackName", stackName)
                        .formParam("TemplateBody", template.formatted(desired, desired))
                        .when().post("/").then().statusCode(200);
                awaitStackStatus(stackName, "UPDATE_COMPLETE");
                String updated = given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                        .formParam("Action", "DescribeStacks").formParam("StackName", stackName)
                        .when().post("/").then().statusCode(200)
                        .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>")).extract().asString();
                assertEquals(ids, List.of(output(updated, "InstanceId"), output(updated, "SubnetId")));
                Map<String, String> expected = new HashMap<>(managed);
                if (!"[]".equals(desired)) {
                    expected.put("Name", "after");
                    expected.put("Added", "after");
                }
                for (String id : ids) {
                    Map<String, String> observed = new HashMap<>();
                    ec2.describeTags(region, Map.of("resource-id", List.of(id)))
                            .forEach(tag -> observed.put(tag.get("key"), tag.get("value")));
                    assertEquals(expected, observed);
                }
            }
        } finally {
            given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                    .formParam("Action", "DeleteStack").formParam("StackName", stackName)
                    .when().post("/").then().statusCode(200);
        }
    }

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

        awaitStackStatus(stackName, "CREATE_COMPLETE");
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
    void updateStackAppliesUserDataAndIamInstanceProfileInPlace() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-ec2-inplace-" + suffix;
        String firstProfile = "cfn-inplace-" + suffix;
        String secondProfile = "cfn-inplace-v2-" + suffix;
        String firstArn = createInstanceProfile(firstProfile);
        String secondArn = createInstanceProfile(secondProfile);
        String template = """
                {
                  "Resources": {
                    "Server": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {"ImageId": "ami-12345678", "InstanceType": "t3.micro",
                                     "UserData": "%s", "IamInstanceProfile": "%s"}
                    }
                  },
                  "Outputs": {
                    "InstanceId": {"Value": {"Ref": "Server"}}
                  }
                }
                """;
        try {
            cfn("CreateStack", stackName, template.formatted("first boot", firstProfile));
            String instanceId = instanceIdFrom(describeStacks(stackName, "CREATE_COMPLETE"));
            assertUserData(instanceId, "Zmlyc3QgYm9vdA==");
            assertInstanceProfile(instanceId, firstArn);

            cfn("UpdateStack", stackName, template.formatted("second boot", secondProfile));
            String updateXml = describeStacks(stackName, "UPDATE_COMPLETE");

            // Both properties are mutable: the same instance carries the new values.
            assertTrue(updateXml.contains("<OutputValue>" + instanceId + "</OutputValue>"),
                    "instance id changed on update, so it was replaced not updated in place: " + updateXml);
            assertUserData(instanceId, "c2Vjb25kIGJvb3Q=");
            assertInstanceProfile(instanceId, secondArn);
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId)
            .when().post("/").then().statusCode(200)
                .body(containsString("<name>running</name>"));
        } finally {
            deleteInstanceProfile(firstProfile);
            deleteInstanceProfile(secondProfile);
        }
    }

    private static void assertUserData(String instanceId, String encoded) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeInstanceAttribute")
            .formParam("InstanceId", instanceId)
            .formParam("Attribute", "userData")
        .when().post("/").then().statusCode(200)
            .body(containsString("<value>" + encoded + "</value>"));
    }

    private static void assertInstanceProfile(String instanceId, String arn) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeIamInstanceProfileAssociations")
            .formParam("Filter.1.Name", "instance-id")
            .formParam("Filter.1.Value.1", instanceId)
        .when().post("/").then().statusCode(200)
            .body(containsString("<arn>" + arn + "</arn>"));
    }

    private static String createInstanceProfile(String name) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "CreateInstanceProfile")
            .formParam("InstanceProfileName", name)
        .when().post("/").then().statusCode(200)
            .extract().path("CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.Arn");
    }

    private static void deleteInstanceProfile(String name) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "DeleteInstanceProfile")
            .formParam("InstanceProfileName", name)
        .when().post("/").then().statusCode(200);
    }

    @Test
    void createStackLaunchesTheInstanceAtItsDeclaredPrivateIpAddress() {
        String stackName = "cfn-ec2-private-ip-" + Long.toString(System.nanoTime(), 36);
        String template = """
                {"Resources": {
                  "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.194.0.0/16"}},
                  "Subnet": {"Type": "AWS::EC2::Subnet", "Properties": {
                    "VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.194.1.0/24"}},
                  "Server": {"Type": "AWS::EC2::Instance", "Properties": {
                    "ImageId": "ami-12345678", "InstanceType": "t3.micro",
                    "SubnetId": {"Ref": "Subnet"}, "PrivateIpAddress": "10.194.1.50"}}
                }, "Outputs": {
                  "InstanceId": {"Value": {"Ref": "Server"}},
                  "PrivateIp": {"Value": {"Fn::GetAtt": ["Server", "PrivateIp"]}}
                }}
                """;
        cfn("CreateStack", stackName, template);
        try {
            String stack = describeStacks(stackName, "CREATE_COMPLETE");
            assertEquals("10.194.1.50", output(stack, "PrivateIp"));
            assertPrivateIpAddress(output(stack, "InstanceId"), "10.194.1.50");
        } finally {
            deleteStack(stackName);
        }
    }

    @Test
    void changingPrivateIpAddressReplacesTheInstance() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-ec2-replace-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.195.0.0/16"}},
                    "Subnet": {"Type": "AWS::EC2::Subnet", "Properties": {
                      "VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.195.1.0/24"}},
                    "Server": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {"ImageId": "ami-12345678", "InstanceType": "t3.micro",
                                     "SubnetId": {"Ref": "Subnet"}, "PrivateIpAddress": "%s"}
                    }
                  },
                  "Outputs": {
                    "InstanceId": {"Value": {"Ref": "Server"}}
                  }
                }
                """;

        cfn("CreateStack", stackName, template.formatted("10.195.1.50"));
        String firstId = instanceIdFrom(describeStacks(stackName, "CREATE_COMPLETE"));

        // PrivateIpAddress is createOnly, so changing it replaces the instance rather than reusing it.
        cfn("UpdateStack", stackName, template.formatted("10.195.1.60"));
        String secondId = instanceIdFrom(describeStacks(stackName, "UPDATE_COMPLETE"));

        assertNotEquals(firstId, secondId, "PrivateIpAddress changed, so the instance must be replaced");

        // The replacement instance really exists in EC2, at the new address.
        assertPrivateIpAddress(secondId, "10.195.1.60");
    }

    @Test
    void replacingAnInstanceThatKeepsItsPrivateIpAddressRollsTheUpdateBack() {
        String stackName = "cfn-ec2-keep-ip-" + Long.toString(System.nanoTime(), 36);
        String template = """
                {"Resources": {
                  "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.196.0.0/16"}},
                  "Subnet": {"Type": "AWS::EC2::Subnet", "Properties": {
                    "VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.196.1.0/24"}},
                  "Server": {"Type": "AWS::EC2::Instance", "Properties": {
                    "ImageId": "%s", "InstanceType": "t3.micro",
                    "SubnetId": {"Ref": "Subnet"}, "PrivateIpAddress": "10.196.1.50"}}
                }, "Outputs": {
                  "InstanceId": {"Value": {"Ref": "Server"}}
                }}
                """;
        cfn("CreateStack", stackName, template.formatted("ami-12345678"));
        try {
            String firstId = instanceIdFrom(describeStacks(stackName, "CREATE_COMPLETE"));

            // A changed ImageId replaces the instance, and CloudFormation creates the replacement
            // before it deletes the original, so the address is still in use, as on AWS.
            cfn("UpdateStack", stackName, template.formatted("ami-87654321"));
            String rolledBack = describeStacks(stackName, "UPDATE_ROLLBACK_COMPLETE");

            assertEquals(firstId, instanceIdFrom(rolledBack));
            assertPrivateIpAddress(firstId, "10.196.1.50");
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStackEvents")
                .formParam("StackName", stackName)
            .when().post("/").then().statusCode(200)
                .body(containsString("Address 10.196.1.50 is in use."));
        } finally {
            deleteStack(stackName);
        }
    }

    /** Every address DescribeInstances reports for the instance and its interface is {@code expected}. */
    private static void assertPrivateIpAddress(String instanceId, String expected) {
        String describeInstances = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
        .when().post("/").then().statusCode(200)
            .body(containsString(instanceId))
            .extract().asString();
        Matcher m = Pattern.compile("<privateIpAddress>([^<]*)</privateIpAddress>").matcher(describeInstances);
        List<String> reported = new ArrayList<>();
        while (m.find()) {
            reported.add(m.group(1));
        }
        assertFalse(reported.isEmpty(), "expected a private address in " + describeInstances);
        assertTrue(reported.stream().allMatch(expected::equals),
                "expected every reported private address to be " + expected + ", got " + reported);
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

    private static void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stackName, String expectedStatus) {
        awaitStackStatus(stackName, expectedStatus);
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static void awaitStackStatus(String stackName, String expectedStatus) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName, CFN_AUTH);
        assertEquals(expectedStatus, state.status(), state.reason());
    }

    private static String instanceIdFrom(String stackXml) {
        Matcher m = Pattern.compile("<OutputValue>(i-[0-9a-fA-F]+)</OutputValue>").matcher(stackXml);
        assertTrue(m.find(), "expected an instance id in the stack outputs: " + stackXml);
        return m.group(1);
    }
}
