package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies AWS::SecretsManager::SecretTargetAttachment provisions with the CloudFormation-documented
 * return value: {@code Ref} resolves to the <em>complete</em> ARN of the secret named in
 * {@code SecretId} (with the trailing {@code -6char} suffix), so downstream
 * {@code Secret.fromSecretCompleteArn(...)} consumers resolve correctly. Previously the resource type
 * fell through the default branch and its {@code Ref} yielded nothing usable. Metadata-only, Docker-free.
 */
@QuarkusTest
class CloudFormationSecretTargetAttachmentIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    private String createStackAndDescribe(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);

        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static String outputValue(String xml, String key) {
        Matcher m = Pattern.compile(
                "<OutputKey>" + Pattern.quote(key) + "</OutputKey>\\s*<OutputValue>(.*?)</OutputValue>",
                Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void secretTargetAttachmentRefResolvesToCompleteSecretArn() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "secret-target-attachment-stack-" + suffix;
        String secretName = "sta-secret-" + suffix;

        String template = """
                {
                  "Resources": {
                    "MySecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "Properties": {
                        "Name": "%s",
                        "SecretString": "{\\"username\\":\\"admin\\",\\"password\\":\\"p\\"}"
                      }
                    },
                    "MyAttachment": {
                      "Type": "AWS::SecretsManager::SecretTargetAttachment",
                      "Properties": {
                        "SecretId": {"Ref": "MySecret"},
                        "TargetType": "AWS::RDS::DBInstance",
                        "TargetId": "some-db-instance"
                      }
                    }
                  },
                  "Outputs": {
                    "SecretArn": {"Value": {"Ref": "MySecret"}},
                    "AttachmentRef": {"Value": {"Ref": "MyAttachment"}}
                  }
                }
                """.formatted(secretName);

        String xml = createStackAndDescribe(stackName, template);
        assertTrue(xml.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"),
                "stack should be CREATE_COMPLETE");

        String secretArn = outputValue(xml, "SecretArn");
        String attachmentRef = outputValue(xml, "AttachmentRef");

        assertTrue(secretArn != null && secretArn.contains(":secret:" + secretName + "-"),
                "secret Ref should be the complete ARN with a uniqueness suffix, was: " + secretArn);
        assertEquals(secretArn, attachmentRef,
                "SecretTargetAttachment Ref must resolve to the complete secret ARN (same as the secret's Ref)");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }
}
