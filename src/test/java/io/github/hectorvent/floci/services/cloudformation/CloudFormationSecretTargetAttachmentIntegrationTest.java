package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** End-to-end coverage for attaching and detaching an external secret from an RDS target. */
@QuarkusTest
@TestProfile(CloudFormationSecretTargetAttachmentIntegrationTest.RdsMockProfile.class)
class CloudFormationSecretTargetAttachmentIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String SM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void attachmentAddsConnectionDataAndDetachPreservesTheSecret() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String secretName = "sta-secret-" + suffix;
        String databaseId = "sta-database-" + suffix;
        String stackName = "sta-stack-" + suffix;
        String secretArn = createSecret(secretName);
        boolean stackCreated = false;

        try {
            String template = """
                    {
                      "Resources": {
                        "Database": {
                          "Type": "AWS::RDS::DBInstance",
                          "Properties": {
                            "DBInstanceIdentifier": "%s",
                            "Engine": "postgres",
                            "MasterUsername": "dbadmin",
                            "MasterUserPassword": "database-password",
                            "DBName": "appdb",
                            "DBInstanceClass": "db.t3.micro",
                            "AllocatedStorage": 20
                          }
                        },
                        "Attachment": {
                          "Type": "AWS::SecretsManager::SecretTargetAttachment",
                          "Properties": {
                            "SecretId": "%s",
                            "TargetType": "AWS::RDS::DBInstance",
                            "TargetId": {"Ref": "Database"}
                          }
                        }
                      },
                      "Outputs": {
                        "AttachmentRef": {"Value": {"Ref": "Attachment"}}
                      }
                    }
                    """.formatted(databaseId, secretName);

            createStack(stackName, template);
            stackCreated = true;
            String describeXml = describeStack(stackName);
            assertTrue(describeXml.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"),
                    "stack should be CREATE_COMPLETE: " + describeXml);
            assertEquals(secretArn, outputValue(describeXml, "AttachmentRef"));

            JsonNode attached = getSecretJson(secretName);
            assertEquals("admin", attached.path("username").asText());
            assertEquals("initial-password", attached.path("password").asText());
            assertEquals("keep", attached.path("custom").asText());
            assertEquals("postgres", attached.path("engine").asText());
            assertEquals("localhost", attached.path("host").asText());
            assertTrue(attached.path("port").asInt() > 0);
            assertEquals("appdb", attached.path("dbname").asText());
            assertEquals(databaseId, attached.path("dbInstanceIdentifier").asText());

            deleteStack(stackName);
            awaitStackDeleted(stackName);
            stackCreated = false;

            given()
                .header("X-Amz-Target", "secretsmanager.DescribeSecret")
                .contentType(SM_CONTENT_TYPE)
                .body("{\"SecretId\":\"" + secretName + "\"}")
            .when().post("/").then().statusCode(200);

            JsonNode detached = getSecretJson(secretName);
            assertEquals("admin", detached.path("username").asText());
            assertEquals("initial-password", detached.path("password").asText());
            assertEquals("keep", detached.path("custom").asText());
            assertFalse(detached.has("engine"));
            assertFalse(detached.has("host"));
            assertFalse(detached.has("port"));
            assertFalse(detached.has("dbname"));
            assertFalse(detached.has("dbInstanceIdentifier"));
        } finally {
            if (stackCreated) {
                deleteStack(stackName);
                awaitStackDeleted(stackName);
            }
            forceDeleteSecret(secretName);
        }
    }

    @Test
    void createRollbackDetachesConnectionDataFromAnExternalSecret() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String secretName = "sta-rollback-secret-" + suffix;
        String databaseId = "sta-rollback-database-" + suffix;
        String stackName = "sta-rollback-stack-" + suffix;
        createSecret(secretName);
        boolean stackCreated = false;

        try {
            String template = """
                    {
                      "Resources": {
                        "Database": {
                          "Type": "AWS::RDS::DBInstance",
                          "Properties": {
                            "DBInstanceIdentifier": "%s",
                            "Engine": "postgres",
                            "MasterUsername": "dbadmin",
                            "MasterUserPassword": "database-password",
                            "DBName": "appdb",
                            "DBInstanceClass": "db.t3.micro",
                            "AllocatedStorage": 20
                          }
                        },
                        "Attachment": {
                          "Type": "AWS::SecretsManager::SecretTargetAttachment",
                          "Properties": {
                            "SecretId": "%s",
                            "TargetType": "AWS::RDS::DBInstance",
                            "TargetId": {"Ref": "Database"}
                          }
                        },
                        "BrokenAttachment": {
                          "Type": "AWS::SecretsManager::SecretTargetAttachment",
                          "DependsOn": "Attachment",
                          "Properties": {
                            "SecretId": "missing-secret-%s",
                            "TargetType": "AWS::RDS::DBInstance",
                            "TargetId": {"Ref": "Database"}
                          }
                        }
                      }
                    }
                    """.formatted(databaseId, secretName, suffix);

            createStack(stackName, template);
            stackCreated = true;
            String describeXml = describeStack(stackName);
            assertTrue(describeXml.contains("<StackStatus>ROLLBACK_COMPLETE</StackStatus>"),
                    "stack should be ROLLBACK_COMPLETE: " + describeXml);

            JsonNode rolledBack = getSecretJson(secretName);
            assertEquals("admin", rolledBack.path("username").asText());
            assertEquals("initial-password", rolledBack.path("password").asText());
            assertEquals("keep", rolledBack.path("custom").asText());
            assertFalse(rolledBack.has("engine"));
            assertFalse(rolledBack.has("host"));
            assertFalse(rolledBack.has("port"));
            assertFalse(rolledBack.has("dbname"));
            assertFalse(rolledBack.has("dbInstanceIdentifier"));
        } finally {
            if (stackCreated) {
                deleteStack(stackName);
                awaitStackDeleted(stackName);
            }
            forceDeleteSecret(secretName);
        }
    }

    private static String createSecret(String secretName) throws Exception {
        String request = MAPPER.createObjectNode()
                .put("Name", secretName)
                .put("SecretString",
                        "{\"username\":\"admin\",\"password\":\"initial-password\",\"custom\":\"keep\"}")
                .toString();
        String response = given()
            .header("X-Amz-Target", "secretsmanager.CreateSecret")
            .contentType(SM_CONTENT_TYPE)
            .body(request)
        .when().post("/").then().statusCode(200).extract().asString();
        return MAPPER.readTree(response).path("ARN").asText();
    }

    private static JsonNode getSecretJson(String secretName) throws Exception {
        String response = given()
            .header("X-Amz-Target", "secretsmanager.GetSecretValue")
            .contentType(SM_CONTENT_TYPE)
            .body("{\"SecretId\":\"" + secretName + "\"}")
        .when().post("/").then().statusCode(200).extract().asString();
        return MAPPER.readTree(MAPPER.readTree(response).path("SecretString").asText());
    }

    private static void forceDeleteSecret(String secretName) {
        given()
            .header("X-Amz-Target", "secretsmanager.DeleteSecret")
            .contentType(SM_CONTENT_TYPE)
            .body("{\"SecretId\":\"" + secretName + "\",\"ForceDeleteWithoutRecovery\":true}")
        .when().post("/").then().statusCode(200);
    }

    private static void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private static String describeStack(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    private static void awaitStackDeleted(String stackName) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + stackName + " was not deleted within the timeout");
    }

    public static final class RdsMockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.mock", "true");
        }
    }
}
