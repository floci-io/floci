package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import io.github.hectorvent.floci.services.cloudfront.model.KeyGroup;
import io.github.hectorvent.floci.services.cloudfront.model.PublicKey;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * AWS::CloudFront::PublicKey, KeyGroup and Function through the stack lifecycle, with the template
 * from issue #4652: the exact {@code Ref} and {@code Fn::GetAtt} values, in-place updates, the
 * replacement of a function whose {@code Name} changes, and DeleteStack.
 */
@QuarkusTest
class CloudFormationCloudFrontKeysAndFunctionsIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void publicKeyKeyGroupAndFunctionFollowTheStackLifecycle() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cloudfront-keys-" + suffix;
        String pem = rsaPublicKeyPem();
        String functionName = "cfn-function-" + suffix;

        stackCall("CreateStack", stackName, template(suffix, pem, "first", functionName, "v1"));
        assertStatus(stackName, "CREATE_COMPLETE");
        String describe = describeStacks(stackName);

        String publicKeyId = output(describe, "PublicKeyRef");
        assertEquals(publicKeyId, output(describe, "PublicKeyId"));
        PublicKey key = cloudFrontService.getPublicKey(publicKeyId);
        assertEquals("cfn-key-" + suffix, key.getName());
        assertEquals("first", key.getComment());
        assertEquals(key.getCreatedTime().toString(), output(describe, "PublicKeyCreatedTime"));

        String keyGroupId = output(describe, "KeyGroupRef");
        assertEquals(keyGroupId, output(describe, "KeyGroupId"));
        KeyGroup group = cloudFrontService.getKeyGroup(keyGroupId);
        assertEquals(List.of(publicKeyId), group.getItems());
        assertEquals(group.getLastModifiedTime().toString(), output(describe, "KeyGroupLastModifiedTime"));

        String functionArn = cloudFrontService.arn("function/" + functionName);
        assertEquals(functionArn, output(describe, "FunctionRef"));
        assertEquals(functionArn, output(describe, "FunctionArn"));
        assertEquals(functionArn, output(describe, "FunctionMetadataArn"));
        assertEquals("LIVE", output(describe, "FunctionStage"));
        assertEquals("v1", cloudFrontService.describeFunction(functionName, "LIVE").getFunctionCode());
        assertEquals(Map.of("Env", "test"), cloudFrontService.listTagsForResource(functionArn));

        String otherStack = stackName + "-other";
        stackCall("CreateStack", otherStack, template(suffix + "-other", rsaPublicKeyPem(), "other",
                functionName, "other code"));
        assertStatus(otherStack, "ROLLBACK_COMPLETE");
        assertEquals("v1", cloudFrontService.describeFunction(functionName, null).getFunctionCode(),
                "a second stack cannot take the name, and its rollback keeps the first function");

        stackCall("UpdateStack", stackName, template(suffix, pem, "second", functionName, "v2"));
        assertStatus(stackName, "UPDATE_COMPLETE");
        describe = describeStacks(stackName);
        assertEquals(publicKeyId, output(describe, "PublicKeyRef"), "a comment change keeps the key");
        assertEquals("second", cloudFrontService.getPublicKey(publicKeyId).getComment());
        assertEquals(keyGroupId, output(describe, "KeyGroupRef"));
        assertEquals(functionArn, output(describe, "FunctionRef"), "a code change keeps the function");
        assertEquals("v2", cloudFrontService.describeFunction(functionName, null).getFunctionCode());
        assertEquals("v2", cloudFrontService.describeFunction(functionName, "LIVE").getFunctionCode());

        String renamed = functionName + "-renamed";
        stackCall("UpdateStack", stackName, template(suffix, pem, "second", renamed, "v2"));
        assertStatus(stackName, "UPDATE_COMPLETE");
        String renamedArn = output(describeStacks(stackName), "FunctionRef");
        assertNotEquals(functionArn, renamedArn, "a Name change replaces the function");
        assertEquals(cloudFrontService.arn("function/" + renamed), renamedArn);
        assertFunctionGone(functionName);
        assertEquals(Map.of(), cloudFrontService.listTagsForResource(functionArn));

        deleteStack(stackName);
        assertFunctionGone(renamed);
        assertEquals("NoSuchResource",
                assertThrows(AwsException.class, () -> cloudFrontService.getKeyGroup(keyGroupId)).getErrorCode());
        assertEquals("NoSuchPublicKey",
                assertThrows(AwsException.class, () -> cloudFrontService.getPublicKey(publicKeyId)).getErrorCode());
    }

    private void assertFunctionGone(String name) {
        AwsException e = assertThrows(AwsException.class, () -> cloudFrontService.describeFunction(name, null));
        assertEquals("NoSuchFunctionExists", e.getErrorCode());
    }

    private static String rsaPublicKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String body = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    /** The template from issue #4652, with unique names and the outputs the assertions read. */
    private static String template(String suffix, String pem, String keyComment, String functionName,
                                   String code) {
        return """
                {
                  "Resources": {
                    "PublicKey": {
                      "Type": "AWS::CloudFront::PublicKey",
                      "Properties": {
                        "PublicKeyConfig": {
                          "Name": "cfn-key-%1$s",
                          "CallerReference": "cfn-key-%1$s-1",
                          "EncodedKey": "%2$s",
                          "Comment": "%3$s"
                        }
                      }
                    },
                    "KeyGroup": {
                      "Type": "AWS::CloudFront::KeyGroup",
                      "Properties": {
                        "KeyGroupConfig": {
                          "Name": "cfn-key-group-%1$s",
                          "Items": [{"Ref": "PublicKey"}]
                        }
                      }
                    },
                    "Function": {
                      "Type": "AWS::CloudFront::Function",
                      "Properties": {
                        "Name": "%4$s",
                        "AutoPublish": true,
                        "FunctionCode": "%5$s",
                        "FunctionConfig": {"Comment": "example", "Runtime": "cloudfront-js-2.0"},
                        "Tags": [{"Key": "Env", "Value": "test"}]
                      }
                    }
                  },
                  "Outputs": {
                    "PublicKeyRef": {"Value": {"Ref": "PublicKey"}},
                    "PublicKeyId": {"Value": {"Fn::GetAtt": ["PublicKey", "Id"]}},
                    "PublicKeyCreatedTime": {"Value": {"Fn::GetAtt": ["PublicKey", "CreatedTime"]}},
                    "KeyGroupRef": {"Value": {"Ref": "KeyGroup"}},
                    "KeyGroupId": {"Value": {"Fn::GetAtt": ["KeyGroup", "Id"]}},
                    "KeyGroupLastModifiedTime": {"Value": {"Fn::GetAtt": ["KeyGroup", "LastModifiedTime"]}},
                    "FunctionRef": {"Value": {"Ref": "Function"}},
                    "FunctionArn": {"Value": {"Fn::GetAtt": ["Function", "FunctionARN"]}},
                    "FunctionMetadataArn": {"Value": {"Fn::GetAtt": ["Function", "FunctionMetadata.FunctionARN"]}},
                    "FunctionStage": {"Value": {"Fn::GetAtt": ["Function", "Stage"]}}
                  }
                }
                """.formatted(suffix, pem.replace("\n", "\\n"), keyComment, functionName, code);
    }

    private static void assertStatus(String stackName, String expected) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
        assertEquals(expected, state.status(), state.reason());
    }

    private static void stackCall(String action, String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.awaitStackDeleted(stackName);
    }

    private static String describeStacks(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }

    private static String output(String describeStacks, String key) {
        String value = XmlParser.extractPairs(describeStacks, "Outputs", "OutputKey", "OutputValue").get(key);
        return value != null ? value : fail("Output " + key + " missing from: " + describeStacks);
    }
}
