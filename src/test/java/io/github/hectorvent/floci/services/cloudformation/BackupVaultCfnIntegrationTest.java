package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions two {@code AWS::Backup::BackupVault} resources through a CloudFormation stack, one
 * named with a KMS key and tags and one with no properties at all. Asserts that {@code Ref} and
 * {@code Fn::GetAtt BackupVaultName} are the name and {@code Fn::GetAtt BackupVaultArn} the ARN
 * {@code DescribeBackupVault} serves, that an update changes tags on the same vault, and that
 * deleting the stack removes both vaults.
 */
@QuarkusTest
class BackupVaultCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String BACKUP_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/backup/aws4_request";
    private static final String STACK = "backup-vault-cfn-it";
    private static final String NAMED = "backup-vault-cfn-it-named";
    private static final String KMS = "arn:aws:kms:us-east-1:000000000000:key/11111111-2222-3333-4444-555555555555";

    private static final String TEMPLATE = """
        {
          "Parameters": {"TagValue": {"Type": "String"}},
          "Resources": {
            "Named": {
              "Type": "AWS::Backup::BackupVault",
              "Properties": {
                "BackupVaultName": "%s",
                "EncryptionKeyArn": "%s",
                "BackupVaultTags": {"stack": {"Ref": "TagValue"}, "team": "core"}
              }
            },
            "Unnamed": {"Type": "AWS::Backup::BackupVault"}
          },
          "Outputs": {
            "NamedRef": {"Value": {"Ref": "Named"}},
            "NamedName": {"Value": {"Fn::GetAtt": ["Named", "BackupVaultName"]}},
            "NamedArn": {"Value": {"Fn::GetAtt": ["Named", "BackupVaultArn"]}},
            "UnnamedRef": {"Value": {"Ref": "Unnamed"}}
          }
        }
        """.formatted(NAMED, KMS);

    @Test
    void createUpdateAndDeleteBackupVaults() throws InterruptedException {
        cloudFormation("CreateStack", Map.of("TagValue", "v1"));
        String created = describeStacks("CREATE_COMPLETE");
        String namedArn = outputValue(created, "NamedArn");
        String unnamed = outputValue(created, "UnnamedRef");

        // Ref and Fn::GetAtt BackupVaultName are the name; the ARN is what the service reports.
        assertEquals(NAMED, outputValue(created, "NamedRef"));
        assertEquals(NAMED, outputValue(created, "NamedName"));
        describeVault(NAMED)
            .statusCode(200)
            .body("BackupVaultArn", equalTo(namedArn))
            .body("EncryptionKeyArn", equalTo(KMS))
            .body("Tags.stack", equalTo("v1"))
            .body("Tags.team", equalTo("core"));

        // A vault with no properties gets a CloudFormation-style generated name within the 50-char limit.
        describeVault(unnamed)
            .statusCode(200)
            .body("BackupVaultName", startsWith(STACK + "-Unnamed-"))
            .body("EncryptionKeyArn", nullValue());

        cloudFormation("UpdateStack", Map.of("TagValue", "v2"));
        String updated = describeStacks("UPDATE_COMPLETE");

        // The tag changes on the same vault; nothing is replaced.
        assertEquals(namedArn, outputValue(updated, "NamedArn"));
        assertEquals(unnamed, outputValue(updated, "UnnamedRef"));
        describeVault(NAMED)
            .statusCode(200)
            .body("Tags.stack", equalTo("v2"))
            .body("Tags.team", equalTo("core"));

        cloudFormation("DeleteStack", Map.of());
        awaitStackDeleted();

        describeVault(NAMED).statusCode(404);
        describeVault(unnamed).statusCode(404);
    }

    private static void cloudFormation(String action, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    /** DeleteStack runs asynchronously; a successful delete removes the stack entirely. */
    private static void awaitStackDeleted() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + STACK + " was not deleted within the timeout");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse describeVault(String name) {
        return given().header("Authorization", BACKUP_AUTH).when().get("/backup-vaults/" + name).then();
    }
}
