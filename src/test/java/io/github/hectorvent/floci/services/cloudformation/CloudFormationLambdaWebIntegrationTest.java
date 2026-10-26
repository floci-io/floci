package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebCodeBuckets;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebRoles;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebZips;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stack with the three Web Function types against the real service: {@code Ref} is each ARN,
 * {@code Fn::GetAtt} exposes the attributes the registry schemas declare, a changed revision is
 * replaced and its predecessor deleted once the update commits, and deleting the stack removes the
 * function.
 */
@QuarkusTest
class CloudFormationLambdaWebIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String BUCKET = "cfn-web-code";

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    @Test
    void stackProvisionsFunctionRevisionAndEndpoint() {
        LambdaWebRoles.trustLambda(iamService, "arn:aws:iam::000000000000:role/cfn-web");
        LambdaWebCodeBuckets.create(s3Service, BUCKET);
        s3Service.putObject(BUCKET, "app.zip", LambdaWebZips.indexJs(""), "application/zip", Map.of());
        String suffix = Long.toString(System.nanoTime(), 36);
        String functionName = "cfn-web-" + suffix;
        String stackName = "cfn-web-stack-" + suffix;
        String functionArn = "arn:aws:lambda:us-east-1:000000000000:web-function/" + functionName;

        cloudFormation(stackName, "CreateStack", template(functionName, 30));
        Map<String, String> created = outputs(describeStacks(stackName, "CREATE_COMPLETE"));
        assertEquals(functionArn, created.get("FnRef"));
        assertEquals(functionArn, created.get("FnArn"));
        assertEquals("Active", created.get("FnState"));
        String revisionId = created.get("RevId");
        assertTrue(revisionId.matches("rev-[a-z0-9]{5}"), revisionId);
        assertEquals(functionArn + "/revision/" + revisionId, created.get("RevRef"));
        assertEquals(created.get("RevRef"), created.get("RevArn"));
        assertEquals("Active", created.get("RevState"));
        assertEquals(functionArn + "/endpoint/prod", created.get("EpRef"));
        assertEquals(created.get("EpRef"), created.get("EpArn"));
        assertTrue(created.get("EpDomain").matches("[a-z0-9]{16}\\.001\\.lambda-web\\.us-east-1\\..+"), created.get("EpDomain"));
        assertEquals("Active", created.get("EpState"));

        given().when().get("/2025-03-07/web-functions/" + functionName + "/endpoints/prod")
                .then().statusCode(200)
                .body("authType", equalTo("ApplicationManaged"))
                .body("revisionWeights[0].revisionId", equalTo(revisionId))
                .body("revisionWeights[0].weight", equalTo(100));
        given().when().get("/2025-03-07/tags/" + functionArn)
                .then().statusCode(200)
                .body("Tags.env", equalTo("probe"));

        // A changed timeout replaces the revision; the endpoint follows and the old revision is deleted.
        cloudFormation(stackName, "UpdateStack", template(functionName, 45));
        Map<String, String> updated = outputs(describeStacks(stackName, "UPDATE_COMPLETE"));
        String newRevisionId = updated.get("RevId");
        assertNotEquals(revisionId, newRevisionId);
        assertEquals(functionArn + "/endpoint/prod", updated.get("EpRef"), "the endpoint keeps its identity");
        await().untilAsserted(() -> given().contentType("application/json").body("{}")
                .when().post("/2025-03-07/web-functions/" + functionName + "/list-revisions")
                .then().statusCode(200)
                .body("revisions", hasSize(1))
                .body("revisions[0].revisionId", equalTo(newRevisionId)));
        given().when().get("/2025-03-07/web-functions/" + functionName + "/revisions/" + newRevisionId)
                .then().statusCode(200)
                .body("serviceConfig.timeoutSeconds", equalTo(45));

        // A failure after the next replacement rolls the endpoint back to this revision and deletes the replacement.
        cloudFormation(stackName, "UpdateStack", template(functionName, 60, BAD_REVISION));
        await().untilAsserted(() -> describeStacks(stackName, "UPDATE_ROLLBACK_COMPLETE"));
        given().when().get("/2025-03-07/web-functions/" + functionName + "/endpoints/prod")
                .then().statusCode(200)
                .body("revisionWeights[0].revisionId", equalTo(newRevisionId));
        await().untilAsserted(() -> given().contentType("application/json").body("{}")
                .when().post("/2025-03-07/web-functions/" + functionName + "/list-revisions")
                .then().statusCode(200)
                .body("revisions", hasSize(1))
                .body("revisions[0].revisionId", equalTo(newRevisionId)));

        cloudFormation(stackName, "DeleteStack", null);
        await().untilAsserted(() -> given().when().get("/2025-03-07/web-functions/" + functionName)
                .then().statusCode(404));
    }

    @Test
    void aRevisionOnCodeThatIsNotAZipFailsItsResource() {
        LambdaWebRoles.trustLambda(iamService, "arn:aws:iam::000000000000:role/cfn-web");
        LambdaWebCodeBuckets.create(s3Service, BUCKET);
        s3Service.putObject(BUCKET, "app.zip", LambdaWebZips.indexJs(""), "application/zip", Map.of());
        s3Service.putObject(BUCKET, "notzip.zip", "this is not a zip\n".getBytes(StandardCharsets.UTF_8),
                "application/zip", Map.of());
        String suffix = Long.toString(System.nanoTime(), 36);
        String functionName = "cfn-web-bad-" + suffix;
        String stackName = "cfn-web-bad-stack-" + suffix;
        String notZip = template(functionName, 30).replace("\"app.zip\"", "\"notzip.zip\"");

        cloudFormation(stackName, "CreateStack", notZip);
        await().untilAsserted(() -> describeStacks(stackName, "ROLLBACK_COMPLETE"));
        stackEvents(stackName).body(containsString(
                "<ResourceStatusReason>Failed to read zip file: zip: not a valid zip file</ResourceStatusReason>"));
        given().when().get("/2025-03-07/web-functions/" + functionName).then().statusCode(404);
        cloudFormation(stackName, "DeleteStack", null);

        // A replacement on such code fails the update, and the rollback deletes it.
        String updatedStack = stackName + "-update";
        cloudFormation(updatedStack, "CreateStack", template(functionName, 30));
        String revisionId = outputs(describeStacks(updatedStack, "CREATE_COMPLETE")).get("RevId");
        cloudFormation(updatedStack, "UpdateStack", notZip);
        await().untilAsserted(() -> describeStacks(updatedStack, "UPDATE_ROLLBACK_COMPLETE"));
        given().contentType("application/json").body("{}")
                .when().post("/2025-03-07/web-functions/" + functionName + "/list-revisions")
                .then().statusCode(200)
                .body("revisions", hasSize(1))
                .body("revisions[0].revisionId", equalTo(revisionId));

        cloudFormation(updatedStack, "DeleteStack", null);
        await().untilAsserted(() -> given().when().get("/2025-03-07/web-functions/" + functionName)
                .then().statusCode(404));
    }

    private static ValidatableResponse stackEvents(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    /** A revision whose code object does not exist, created after the endpoint, so the update fails there. */
    private static final String BAD_REVISION = """
            "Bad": {
              "Type": "AWS::Lambda::WebFunctionRevision",
              "DependsOn": ["Ep"],
              "Properties": {
                "FunctionName": {"Ref": "Fn"},
                "BuildConfig": {
                  "CodeConfig": {"S3Object": {"Bucket": "%s", "Key": "missing.zip"}},
                  "RuntimeConfig": {"Runtime": "nodejs24.x"}
                },
                "ServiceConfig": {"ExecutionRoleArn": "arn:aws:iam::000000000000:role/cfn-web"}
              }
            },
            """.formatted(BUCKET);

    private static String template(String functionName, int timeoutSeconds) {
        return template(functionName, timeoutSeconds, "");
    }

    private static String template(String functionName, int timeoutSeconds, String extraResources) {
        return """
                {
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::WebFunction",
                      "Properties": {"FunctionName": "%s", "Tags": [{"Key": "env", "Value": "probe"}]}
                    },
                    "Rev": {
                      "Type": "AWS::Lambda::WebFunctionRevision",
                      "Properties": {
                        "FunctionName": {"Ref": "Fn"},
                        "Description": "cfn revision",
                        "BuildConfig": {
                          "CodeConfig": {"S3Object": {"Bucket": "%s", "Key": "app.zip"}},
                          "RuntimeConfig": {"Runtime": "nodejs24.x"}
                        },
                        "ServiceConfig": {
                          "ExecutionRoleArn": "arn:aws:iam::000000000000:role/cfn-web",
                          "TimeoutSeconds": %d
                        }
                      }
                    },
                    %s"Ep": {
                      "Type": "AWS::Lambda::WebFunctionEndpoint",
                      "Properties": {
                        "FunctionName": {"Ref": "Fn"},
                        "EndpointName": "prod",
                        "EndpointType": "HomeRegion",
                        "AuthType": "ApplicationManaged",
                        "RevisionWeights": [{"RevisionId": {"Fn::GetAtt": ["Rev", "RevisionId"]}, "Weight": 100}]
                      }
                    }
                  },
                  "Outputs": {
                    "FnRef": {"Value": {"Ref": "Fn"}},
                    "FnArn": {"Value": {"Fn::GetAtt": ["Fn", "FunctionArn"]}},
                    "FnState": {"Value": {"Fn::GetAtt": ["Fn", "State"]}},
                    "RevRef": {"Value": {"Ref": "Rev"}},
                    "RevArn": {"Value": {"Fn::GetAtt": ["Rev", "RevisionArn"]}},
                    "RevId": {"Value": {"Fn::GetAtt": ["Rev", "RevisionId"]}},
                    "RevState": {"Value": {"Fn::GetAtt": ["Rev", "State"]}},
                    "EpRef": {"Value": {"Ref": "Ep"}},
                    "EpArn": {"Value": {"Fn::GetAtt": ["Ep", "EndpointArn"]}},
                    "EpDomain": {"Value": {"Fn::GetAtt": ["Ep", "DomainName"]}},
                    "EpState": {"Value": {"Fn::GetAtt": ["Ep", "State"]}}
                  }
                }
                """.formatted(functionName, BUCKET, timeoutSeconds, extraResources);
    }

    private static void cloudFormation(String stackName, String action, String templateBody) {
        if (templateBody == null) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName)
            .when().post("/").then().statusCode(200);
            return;
        }
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", templateBody)
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

    private static Map<String, String> outputs(String describeStacksXml) {
        return XmlParser.extractPairs(describeStacksXml, "Outputs", "OutputKey", "OutputValue");
    }
}
