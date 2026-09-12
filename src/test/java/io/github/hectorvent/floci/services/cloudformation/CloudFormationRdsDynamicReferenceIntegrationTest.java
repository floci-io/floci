package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RDS master credentials are the only properties where {@code {{resolve:ssm-secure:...}}} is
 * legal. Every other property resolves dynamic references through the template engine's general
 * stage, which rejects {@code ssm-secure} outright, so the two RDS credential properties opt out
 * of that stage and resolve their own references with the elevated permission.
 *
 * <p>That opt-out is invisible to the rest of the suite. {@code RdsCfnProvisionerTest} builds its
 * engine without a dynamic-reference resolver, so the general stage is disabled there and its
 * assertions hold whether or not the opt-out survives.
 * {@code CloudFormationDynamicReferenceIntegrationTest} covers the general path and the
 * {@code ssm-secure} rejection, but has no RDS case. Without the tests below, dropping the opt-out
 * leaves the whole suite green and breaks {@code ssm-secure} on the one resource that accepts it.
 */
@QuarkusTest
@TestProfile(CloudFormationRdsDynamicReferenceIntegrationTest.RdsMockProfile.class)
class CloudFormationRdsDynamicReferenceIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * The regression gate. A master password reading from an SSM {@code SecureString} must reach
     * {@code CREATE_COMPLETE}. If the credential properties stop opting out of the general stage,
     * that stage rejects the reference and the stack rolls back instead.
     */
    @Test
    void createStack_rdsMasterPasswordResolvesSsmSecureDynamicReference() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String parameterName = "/cfn-rds-dynref/secure-password-" + suffix;
        String stackName = "cfn-rds-dynref-secure-stack-" + suffix;
        String databaseId = "cfn-rds-dynref-secure-db-" + suffix;
        putParameter(parameterName, "s3cret-from-ssm", "SecureString");
        boolean stackCreated = false;

        try {
            createStack(stackName, template(databaseId,
                    "{{resolve:ssm-secure:" + parameterName + "}}"));
            stackCreated = true;

            String describeXml = describeStack(stackName);
            assertTrue(describeXml.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"),
                    "an ssm-secure master password is legal on RDS and must provision: " + describeXml);
        } finally {
            if (stackCreated) {
                deleteStack(stackName);
                awaitStackDeleted(stackName);
            }
            deleteParameter(parameterName);
        }
    }

    /**
     * The other direction. Opting out of the general stage must not cost the credential properties
     * the ordinary {@code ssm} reference, which they resolve themselves.
     */
    @Test
    void createStack_rdsMasterPasswordResolvesPlainSsmDynamicReference() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String parameterName = "/cfn-rds-dynref/plain-password-" + suffix;
        String stackName = "cfn-rds-dynref-plain-stack-" + suffix;
        String databaseId = "cfn-rds-dynref-plain-db-" + suffix;
        putParameter(parameterName, "plain-from-ssm", "String");
        boolean stackCreated = false;

        try {
            createStack(stackName, template(databaseId,
                    "{{resolve:ssm:" + parameterName + "}}"));
            stackCreated = true;

            String describeXml = describeStack(stackName);
            assertTrue(describeXml.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"),
                    "a plain ssm master password must still resolve: " + describeXml);
        } finally {
            if (stackCreated) {
                deleteStack(stackName);
                awaitStackDeleted(stackName);
            }
            deleteParameter(parameterName);
        }
    }

    private static String template(String databaseId, String masterPassword) {
        return """
                {
                  "Resources": {
                    "Database": {
                      "Type": "AWS::RDS::DBInstance",
                      "Properties": {
                        "DBInstanceIdentifier": "%s",
                        "Engine": "postgres",
                        "MasterUsername": "dbadmin",
                        "MasterUserPassword": "%s",
                        "DBName": "appdb",
                        "DBInstanceClass": "db.t3.micro",
                        "AllocatedStorage": 20
                      }
                    }
                  }
                }
                """.formatted(databaseId, masterPassword);
    }

    private static void putParameter(String name, String value, String type) {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "%s",
                    "Value": "%s",
                    "Type": "%s",
                    "Overwrite": true
                }
                """.formatted(name, value, type))
        .when().post("/").then().statusCode(200);
    }

    private static void deleteParameter(String name) {
        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/");
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
