package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * A nested stack the template no longer names, left by a rollback that skipped it, still holds a
 * subnet its child created in a VPC the parent passed it, so DeleteStack has to delete it before
 * that VPC, though only the parameters it passed its child say what it uses.
 */
@QuarkusTest
class CloudFormationNestedStackLeftoverIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261019/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261019/us-east-1/ec2/aws4_request";

    private static final String CHILD_TEMPLATE = """
        {
          "Parameters": {"VpcId": {"Type": "String"}, "Label": {"Type": "String"}},
          "Resources": {
            "Subnet": {"Type": "AWS::EC2::Subnet",
                       "Properties": {"VpcId": {"Ref": "VpcId"}, "CidrBlock": "10.97.1.0/24",
                                      "AvailabilityZone": "us-east-1a"}}
          }
        }""";

    @InjectSpy
    Ec2Service ec2Service;

    @Inject
    CloudFormationService cloudFormationService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void deleteStackDeletesASkippedNestedStackBeforeTheVpcItPassedItsChild() {
        String stack = "nested-leftover-" + Long.toString(System.nanoTime(), 36);
        String bucket = stack + "-templates";
        given().when().put("/" + bucket).then().statusCode(200);
        given().contentType("application/json").body(CHILD_TEMPLATE)
                .when().put("/" + bucket + "/child.json").then().statusCode(200);
        AtomicBoolean failSubnetDeletes = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failSubnetDeletes.get()) {
                throw new AwsException("InternalError", "simulated subnet delete failure", 500);
            }
            return invocation.callRealMethod();
        }).when(ec2Service).deleteSubnet(anyString(), anyString());
        try {
            Map<String, String> created = createStack(stack, template(""));
            String network = """
                ,
                    "Network": {"Type": "AWS::CloudFormation::Stack",
                                "Properties": {"TemplateURL": "http://localhost/%1$s/child.json",
                                               "Parameters": {"VpcId": {"Ref": "Vpc"},
                                                              "Label": "%2$s-label"}}},
                    "BadSecret": {"Type": "AWS::SecretsManager::Secret", "DependsOn": "Network",
                                  "Properties": {"Name": "%2$s-secret", "SecretString": "explicit",
                                                 "GenerateSecretString": {"PasswordLength": 32}}}"""
                    .formatted(bucket, stack);
            request("UpdateStack", "StackName", stack, "TemplateBody", template(network)).statusCode(200);
            String reason = CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_FAILED").reason();
            assertTrue(reason.contains("Network"), reason);
            failSubnetDeletes.set(false);

            CfnStackWaits.continueUpdateRollback(stack, CFN_AUTH, "Network");
            CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(1, XmlParser.extractAll(ec2("DescribeSubnets", "Filter.1.Name", "vpc-id",
                    "Filter.1.Value.1", created.get("Vpc")).statusCode(200).extract().asString(), "subnetId")
                    .size(), "the skipped nested stack's subnet is left in place");
            // The VPC its definition named is kept for the leftover; the literal label names nothing.
            Map<String, String> kept = cloudFormationService.describeStacks(stack, "us-east-1").getFirst()
                    .getResources().get("Network").getAttributes();
            assertEquals(created.get("Vpc"), kept.get("__FlociUsesResource.Vpc"));
            assertFalse(kept.containsValue(stack + "-label"), kept::toString);

            request("DeleteStack", "StackName", stack).statusCode(200);
            CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
            ec2("DescribeVpcs", "VpcId.1", created.get("Vpc")).statusCode(400)
                    .body(containsString("InvalidVpcID.NotFound"));
        } finally {
            failSubnetDeletes.set(false);
            reset(ec2Service);
            // Best effort: a failed run may leave the stack DELETE_FAILED or mid-rollback.
            if (request("DescribeStacks", "StackName", stack).extract().statusCode() == 200) {
                request("DeleteStack", "StackName", stack);
            }
            given().when().delete("/" + bucket + "/child.json");
            given().when().delete("/" + bucket);
        }
    }

    /** A VPC, plus whatever an update adds. */
    private static String template(String extraResources) {
        return """
            {
              "Resources": {
                "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.97.0.0/16"}}%1$s
              },
              "Outputs": {
                "Vpc": {"Value": {"Ref": "Vpc"}}
              }
            }""".formatted(extraResources);
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
