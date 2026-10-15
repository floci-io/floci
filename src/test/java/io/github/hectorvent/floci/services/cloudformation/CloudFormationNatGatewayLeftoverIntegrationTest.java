package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * A NAT gateway the template no longer names, left by a rollback that skipped it, still holds its
 * Elastic IP and its subnet, so DeleteStack has to delete it before either of them, though only
 * its hidden attributes say what it uses.
 */
@QuarkusTest
class CloudFormationNatGatewayLeftoverIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261015/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261015/us-east-1/ec2/aws4_request";

    @InjectSpy
    Ec2Service ec2Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void deleteStackDeletesASkippedNatGatewayBeforeItsElasticIpSubnetAndVpc() {
        String stack = "nat-leftover-" + Long.toString(System.nanoTime(), 36);
        AtomicBoolean failNatDeletes = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failNatDeletes.get()) {
                throw new AwsException("InternalError", "simulated NAT gateway delete failure", 500);
            }
            return invocation.callRealMethod();
        }).when(ec2Service).deleteNatGateway(anyString(), anyString());
        try {
            Map<String, String> created = createStack(stack, template(stack, ""));
            String nat = """
                ,
                    "Nat": {"Type": "AWS::EC2::NatGateway",
                            "Properties": {"SubnetId": {"Ref": "Subnet"},
                                           "AllocationId": {"Fn::GetAtt": ["Eip", "AllocationId"]}}},
                    "BadSecret": {"Type": "AWS::SecretsManager::Secret", "DependsOn": "Nat",
                                  "Properties": {"Name": "%1$s-secret", "SecretString": "explicit",
                                                 "GenerateSecretString": {"PasswordLength": 32}}}""".formatted(stack);
            request("UpdateStack", "StackName", stack, "TemplateBody", template(stack, nat)).statusCode(200);
            String reason = CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_FAILED").reason();
            assertTrue(reason.contains("Nat"), reason);
            failNatDeletes.set(false);

            request("ContinueUpdateRollback", "StackName", stack, "ResourcesToSkip.member.1", "Nat").statusCode(200);
            CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(1, XmlParser.extractAll(ec2("DescribeNatGateways", "Filter.1.Name", "subnet-id",
                    "Filter.1.Value.1", created.get("Subnet")).statusCode(200).extract().asString(), "natGatewayId")
                    .size(), "the skipped gateway is left in place");

            request("DeleteStack", "StackName", stack).statusCode(200);
            CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
            ec2("DescribeVpcs", "VpcId.1", created.get("Vpc")).statusCode(400)
                    .body(containsString("InvalidVpcID.NotFound"));
            assertEquals(List.of(), XmlParser.extractAll(ec2("DescribeAddresses", "PublicIp.1", created.get("Eip"))
                    .extract().asString(), "allocationId"));
        } finally {
            failNatDeletes.set(false);
            reset(ec2Service);
            // Best effort: a failed run may leave the stack DELETE_FAILED or mid-rollback.
            if (request("DescribeStacks", "StackName", stack).extract().statusCode() == 200) {
                request("DeleteStack", "StackName", stack);
            }
        }
    }

    /** A VPC with one subnet and an Elastic IP, plus whatever an update adds. */
    private static String template(String stack, String extraResources) {
        return """
            {
              "Resources": {
                "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.96.0.0/16",
                        "Tags": [{"Key": "floci-it", "Value": "%1$s"}]}},
                "Subnet": {"Type": "AWS::EC2::Subnet",
                           "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.96.1.0/24",
                                          "AvailabilityZone": "us-east-1a"}},
                "Eip": {"Type": "AWS::EC2::EIP", "Properties": {"Domain": "vpc"}}%2$s
              },
              "Outputs": {
                "Vpc": {"Value": {"Ref": "Vpc"}},
                "Subnet": {"Value": {"Ref": "Subnet"}},
                "Eip": {"Value": {"Ref": "Eip"}}
              }
            }""".formatted(stack, extraResources);
    }

    private static Map<String, String> createStack(String stack, String templateBody) {
        request("CreateStack", "StackName", stack, "TemplateBody", templateBody).statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE");
        String xml = request("DescribeStacks", "StackName", stack).statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "member", "OutputKey", "OutputValue");
    }

    private static ValidatableResponse ec2(String action, String... values) {
        return send(EC2_AUTH, action, values);
    }

    private static ValidatableResponse request(String action, String... values) {
        return send(CFN_AUTH, action, values);
    }

    private static ValidatableResponse send(String auth, String action, String... values) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", action);
        for (int i = 0; i < values.length; i += 2) {
            parameters.put(values[i], values[i + 1]);
        }
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", auth)
                .formParams(parameters).when().post("/").then().log().ifValidationFails();
    }
}
