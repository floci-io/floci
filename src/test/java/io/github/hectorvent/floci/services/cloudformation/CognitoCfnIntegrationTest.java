package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::Cognito::UserPoolDomain} through a CloudFormation stack, laid out the
 * way CDK emits a custom domain: a user pool, the domain with an ACM certificate, and a Route 53
 * alias record whose target is {@code Fn::GetAtt CloudFrontDistribution}. Asserts that the
 * attribute is the CloudFront name {@code DescribeUserPoolDomain} reports rather than the literal
 * {@code Domain.CloudFrontDistribution} the stub arm would leave in the record, that a certificate
 * change updates the domain in place so the alias target survives, and that deleting the stack
 * removes the domain before the pool that owns it.
 */
@QuarkusTest
class CognitoCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "cognito-cfn-it";
    private static final String DOMAIN = "auth.cognito-cfn-it.example.com";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-1111-1111-1111-111111111111";
    private static final String RENEWED_CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/22222222-2222-2222-2222-222222222222";

    private static String template(String certificateArn) {
        return """
            {
              "Resources": {
                "Pool": {
                  "Type": "AWS::Cognito::UserPool",
                  "Properties": {"UserPoolName": "cognito-cfn-it-pool"}
                },
                "Domain": {
                  "Type": "AWS::Cognito::UserPoolDomain",
                  "Properties": {
                    "Domain": "%s",
                    "UserPoolId": {"Ref": "Pool"},
                    "CustomDomainConfig": {"CertificateArn": "%s"},
                    "ManagedLoginVersion": 2
                  }
                },
                "Zone": {
                  "Type": "AWS::Route53::HostedZone",
                  "Properties": {"Name": "cognito-cfn-it.example.com"}
                },
                "AuthAlias": {
                  "Type": "AWS::Route53::RecordSet",
                  "Properties": {
                    "HostedZoneId": {"Ref": "Zone"},
                    "Name": "%s",
                    "Type": "A",
                    "AliasTarget": {
                      "DNSName": {"Fn::GetAtt": ["Domain", "CloudFrontDistribution"]},
                      "HostedZoneId": "Z2FDTNDATAQYW2"
                    }
                  }
                }
              },
              "Outputs": {
                "PoolId": {"Value": {"Ref": "Pool"}},
                "DomainRef": {"Value": {"Ref": "Domain"}},
                "AliasTarget": {"Value": {"Fn::GetAtt": ["Domain", "CloudFrontDistribution"]}}
              }
            }
            """.formatted(DOMAIN, certificateArn, DOMAIN);
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void userPoolDomainStackExposesTheCloudFrontNameUpdatesInPlaceAndDeletesBeforeThePool() throws Exception {
        cloudFormation("CreateStack", template(CERTIFICATE_ARN));

        String stacks = describeStacks("CREATE_COMPLETE");
        String poolId = outputValue(stacks, "PoolId");
        String aliasTarget = outputValue(stacks, "AliasTarget");
        assertEquals(DOMAIN, outputValue(stacks, "DomainRef"));
        assertTrue(aliasTarget.endsWith(".cloudfront.net"),
                "Fn::GetAtt CloudFrontDistribution must be a CloudFront name: " + aliasTarget);

        JsonNode description = describeDomain();
        assertEquals(poolId, description.path("UserPoolId").asText());
        assertEquals(aliasTarget, description.path("CloudFrontDistribution").asText());
        assertEquals(CERTIFICATE_ARN, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertEquals(2, description.path("ManagedLoginVersion").asInt());

        cloudFormation("UpdateStack", template(RENEWED_CERTIFICATE_ARN));

        describeStacks("UPDATE_COMPLETE");
        description = describeDomain();
        assertEquals(RENEWED_CERTIFICATE_ARN, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertEquals(aliasTarget, description.path("CloudFrontDistribution").asText(),
                "a certificate change must keep the CloudFront distribution the alias record points at");

        cloudFormation("DeleteStack", null);
        awaitStackDeleted();

        cognitoAction("DescribeUserPoolDomain", "{\"Domain\": \"" + DOMAIN + "\"}")
            .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
            .then()
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static void cloudFormation(String action, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
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

    private static JsonNode describeDomain() throws Exception {
        return cognitoJson("DescribeUserPoolDomain", "{\"Domain\": \"" + DOMAIN + "\"}").path("DomainDescription");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
