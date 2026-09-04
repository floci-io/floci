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
import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsAction;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::Cognito::UserPoolDomain} through a CloudFormation stack, laid out the
 * way CDK emits a custom domain: a user pool, an {@code AWS::CertificateManager::Certificate}, the
 * domain referencing the certificate, and a Route 53 alias record whose target is
 * {@code Fn::GetAtt CloudFrontDistribution}. Asserts that the attribute is the CloudFront name
 * {@code DescribeUserPoolDomain} reports rather than the literal {@code Domain.CloudFrontDistribution}
 * the stub arm would leave in the record, that rotating to a new certificate resource updates the
 * domain in place so the alias target survives, that a changed domain name replaces the domain, and
 * that deleting the stack removes the domain before the pool that owns it. A prefix domain, which
 * has no distribution of its own, resolves the attribute to an empty string rather than the literal.
 */
@QuarkusTest
class CognitoCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/cloudformation/aws4_request";
    private static final String CUSTOM_STACK = "cognito-cfn-it";
    private static final String PREFIX_STACK = "cognito-cfn-prefix-it";
    private static final String DOMAIN = "auth.cognito-cfn-it.example.com";
    private static final String REPLACEMENT_DOMAIN = "login.cognito-cfn-it.example.com";
    private static final String PREFIX_DOMAIN = "cognito-cfn-it-prefix";

    /**
     * The certificate carries the logical id so a rotation can add a new certificate resource and
     * drop the old one, which is how a certificate is replaced without a moment where the domain
     * points at a certificate that is being deleted. A wildcard name keeps a domain rename from
     * replacing the certificate as well.
     */
    private static String customDomainTemplate(String domain, String certificateLogicalId) {
        return """
            {
              "Resources": {
                "Pool": {
                  "Type": "AWS::Cognito::UserPool",
                  "Properties": {"UserPoolName": "cognito-cfn-it-pool"}
                },
                "%1$s": {
                  "Type": "AWS::CertificateManager::Certificate",
                  "Properties": {"DomainName": "*.cognito-cfn-it.example.com", "ValidationMethod": "DNS"}
                },
                "Domain": {
                  "Type": "AWS::Cognito::UserPoolDomain",
                  "Properties": {
                    "Domain": "%2$s",
                    "UserPoolId": {"Ref": "Pool"},
                    "CustomDomainConfig": {"CertificateArn": {"Fn::GetAtt": ["%1$s", "CertificateArn"]}},
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
                    "Name": "%2$s",
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
                "CertArn": {"Value": {"Fn::GetAtt": ["%1$s", "CertificateArn"]}},
                "AliasTarget": {"Value": {"Fn::GetAtt": ["Domain", "CloudFrontDistribution"]}}
              }
            }
            """.formatted(certificateLogicalId, domain);
    }

    private static final String PREFIX_DOMAIN_TEMPLATE = """
            {
              "Resources": {
                "Pool": {
                  "Type": "AWS::Cognito::UserPool",
                  "Properties": {"UserPoolName": "cognito-cfn-prefix-it-pool"}
                },
                "Domain": {
                  "Type": "AWS::Cognito::UserPoolDomain",
                  "Properties": {
                    "Domain": "%s",
                    "UserPoolId": {"Ref": "Pool"}
                  }
                }
              },
              "Outputs": {
                "PoolId": {"Value": {"Ref": "Pool"}},
                "DomainRef": {"Value": {"Ref": "Domain"}},
                "CloudFront": {"Value": {"Fn::GetAtt": ["Domain", "CloudFrontDistribution"]}}
              }
            }
            """.formatted(PREFIX_DOMAIN);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void customDomainStackExposesTheCloudFrontNameRotatesTheCertificateReplacesOnRenameAndDeletesBeforeThePool()
            throws Exception {
        cloudFormation(CUSTOM_STACK, "CreateStack", customDomainTemplate(DOMAIN, "Cert"));

        String stacks = describeStacks(CUSTOM_STACK, "CREATE_COMPLETE");
        String poolId = outputValue(stacks, "PoolId");
        String aliasTarget = outputValue(stacks, "AliasTarget");
        String certificateArn = outputValue(stacks, "CertArn");
        assertEquals(DOMAIN, outputValue(stacks, "DomainRef"));
        assertTrue(aliasTarget.endsWith(".cloudfront.net"),
                "Fn::GetAtt CloudFrontDistribution must be a CloudFront name: " + aliasTarget);
        assertTrue(certificateArn.startsWith("arn:aws:acm:us-east-1:"),
                "the certificate must be provisioned rather than stubbed: " + certificateArn);
        assertCertificateStatus(certificateArn, "ISSUED");

        JsonNode description = describeDomain(DOMAIN);
        assertEquals(poolId, description.path("UserPoolId").asText());
        assertEquals(aliasTarget, description.path("CloudFrontDistribution").asText());
        assertEquals(certificateArn, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertEquals(2, description.path("ManagedLoginVersion").asInt());

        // Rotation: a new certificate resource takes over and the old one leaves the template. The
        // domain moves to the new certificate in place, and the old certificate is removed once
        // nothing references it.
        cloudFormation(CUSTOM_STACK, "UpdateStack", customDomainTemplate(DOMAIN, "RenewedCert"));

        stacks = describeStacks(CUSTOM_STACK, "UPDATE_COMPLETE");
        String renewedCertificateArn = outputValue(stacks, "CertArn");
        assertNotEquals(certificateArn, renewedCertificateArn);
        description = describeDomain(DOMAIN);
        assertEquals(renewedCertificateArn, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertEquals(aliasTarget, description.path("CloudFrontDistribution").asText(),
                "a certificate change must keep the CloudFront distribution the alias record points at");
        assertCertificateIsGone(certificateArn);

        // Domain is createOnly: a new name is a replacement, created before the old domain goes.
        cloudFormation(CUSTOM_STACK, "UpdateStack", customDomainTemplate(REPLACEMENT_DOMAIN, "RenewedCert"));

        stacks = describeStacks(CUSTOM_STACK, "UPDATE_COMPLETE");
        assertEquals(REPLACEMENT_DOMAIN, outputValue(stacks, "DomainRef"));
        String replacementTarget = outputValue(stacks, "AliasTarget");
        assertTrue(replacementTarget.endsWith(".cloudfront.net"), replacementTarget);
        assertNotEquals(aliasTarget, replacementTarget, "the replacement is a new domain with its own distribution");
        assertDomainIsGone(DOMAIN);
        description = describeDomain(REPLACEMENT_DOMAIN);
        assertEquals(poolId, description.path("UserPoolId").asText());
        assertEquals(replacementTarget, description.path("CloudFrontDistribution").asText());
        assertEquals(renewedCertificateArn, description.path("CustomDomainConfig").path("CertificateArn").asText());

        cloudFormation(CUSTOM_STACK, "DeleteStack", null);
        awaitStackDeleted(CUSTOM_STACK);

        assertDomainIsGone(REPLACEMENT_DOMAIN);
        assertCertificateIsGone(renewedCertificateArn);
        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
            .then()
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void prefixDomainStackResolvesAnEmptyCloudFrontDistribution() throws Exception {
        cloudFormation(PREFIX_STACK, "CreateStack", PREFIX_DOMAIN_TEMPLATE);

        String stacks = describeStacks(PREFIX_STACK, "CREATE_COMPLETE");
        assertEquals(PREFIX_DOMAIN, outputValue(stacks, "DomainRef"));
        assertEquals("", outputValue(stacks, "CloudFront"),
                "a prefix domain has no distribution of its own, and must not resolve to the literal attribute name");

        JsonNode description = describeDomain(PREFIX_DOMAIN);
        assertEquals(outputValue(stacks, "PoolId"), description.path("UserPoolId").asText());
        assertFalse(description.has("CloudFrontDistribution"));
        assertFalse(description.has("CustomDomainConfig"));

        cloudFormation(PREFIX_STACK, "DeleteStack", null);
        awaitStackDeleted(PREFIX_STACK);

        assertDomainIsGone(PREFIX_DOMAIN);
    }

    private static void cloudFormation(String stack, String action, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stack, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    /** DeleteStack runs asynchronously; a successful delete removes the stack entirely. */
    private static void awaitStackDeleted(String stack) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + stack + " was not deleted within the timeout");
    }

    private static JsonNode describeDomain(String domain) throws Exception {
        return cognitoJson("DescribeUserPoolDomain", "{\"Domain\": \"" + domain + "\"}").path("DomainDescription");
    }

    private static void assertDomainIsGone(String domain) {
        cognitoAction("DescribeUserPoolDomain", "{\"Domain\": \"" + domain + "\"}")
            .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static void assertCertificateStatus(String certificateArn, String status) {
        awsAction("CertificateManager", "DescribeCertificate", "{\"CertificateArn\": \"" + certificateArn + "\"}")
            .then()
            .statusCode(200)
            .body("Certificate.Status", equalTo(status));
    }

    private static void assertCertificateIsGone(String certificateArn) {
        awsAction("CertificateManager", "DescribeCertificate", "{\"CertificateArn\": \"" + certificateArn + "\"}")
            .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
