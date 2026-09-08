package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::CertificateManager::Certificate} through a CloudFormation stack and
 * asserts that {@code Ref} and {@code Fn::GetAtt CertificateArn} are a real ARN that ACM's
 * {@code DescribeCertificate} finds. A status-only assertion would pass for the stub arm too,
 * where the attribute resolves to the literal {@code Cert.CertificateArn}.
 *
 * <p>The second stack drives {@code CertificateExport} and
 * {@code CertificateTransparencyLoggingPreference} through create and update: the logging
 * preference changes in place, the export setting replaces the certificate, and both read back
 * from {@code DescribeCertificate}.
 */
@QuarkusTest
class AcmCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/cloudformation/aws4_request";
    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String STACK = "acm-cfn-it";
    private static final String OPTIONS_STACK = "acm-cfn-options-it";

    private static final String TEMPLATE = """
        {
          "Resources": {
            "Cert": {
              "Type": "AWS::CertificateManager::Certificate",
              "Properties": {
                "DomainName": "api.cfn-it.example.com",
                "SubjectAlternativeNames": ["www.cfn-it.example.com"],
                "ValidationMethod": "DNS",
                "DomainValidationOptions": [
                  {"DomainName": "api.cfn-it.example.com", "HostedZoneId": "Z0000000000000000000A"}
                ],
                "Tags": [{"Key": "stack", "Value": "acm-cfn-it"}]
              }
            }
          },
          "Outputs": {
            "CertRef": {"Value": {"Ref": "Cert"}},
            "CertArn": {"Value": {"Fn::GetAtt": ["Cert", "CertificateArn"]}}
          }
        }
        """;

    /** Both options are parameters so one template drives the create and both kinds of update. */
    private static final String OPTIONS_TEMPLATE = """
        {
          "Parameters": {
            "Export": {"Type": "String"},
            "TransparencyLogging": {"Type": "String"}
          },
          "Resources": {
            "Cert": {
              "Type": "AWS::CertificateManager::Certificate",
              "Properties": {
                "DomainName": "options.cfn-it.example.com",
                "ValidationMethod": "DNS",
                "CertificateExport": {"Ref": "Export"},
                "CertificateTransparencyLoggingPreference": {"Ref": "TransparencyLogging"}
              }
            }
          },
          "Outputs": {
            "CertArn": {"Value": {"Fn::GetAtt": ["Cert", "CertificateArn"]}}
          }
        }
        """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void certificateStackExposesAnArnThatDescribeCertificateFinds() throws InterruptedException {
        cloudFormation(STACK, "CreateStack", TEMPLATE, Map.of());

        String stacks = describeStacks(STACK, "CREATE_COMPLETE");
        String arn = outputValue(stacks, "CertArn");
        assertTrue(arn.startsWith("arn:aws:acm:us-east-1:"), "Fn::GetAtt CertificateArn must be an ARN: " + arn);
        assertEquals(arn, outputValue(stacks, "CertRef"));

        describeCertificate(arn).then()
            .statusCode(200)
            .body("Certificate.CertificateArn", equalTo(arn))
            .body("Certificate.DomainName", equalTo("api.cfn-it.example.com"))
            .body("Certificate.Status", equalTo("ISSUED"))
            .body("Certificate.Options.Export", equalTo("DISABLED"))
            .body("Certificate.Options.CertificateTransparencyLoggingPreference", equalTo("ENABLED"));

        cloudFormation(STACK, "DeleteStack", null, Map.of());
        awaitStackDeleted(STACK);

        describeCertificate(arn).then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void certificateOptionsFollowTheTemplateThroughCreateAndUpdate() throws InterruptedException {
        cloudFormation(OPTIONS_STACK, "CreateStack", OPTIONS_TEMPLATE, options("ENABLED", "DISABLED"));
        String arn = outputValue(describeStacks(OPTIONS_STACK, "CREATE_COMPLETE"), "CertArn");
        describeCertificate(arn).then()
            .statusCode(200)
            .body("Certificate.Status", equalTo("ISSUED"))
            .body("Certificate.Options.Export", equalTo("ENABLED"))
            .body("Certificate.Options.CertificateTransparencyLoggingPreference", equalTo("DISABLED"));

        cloudFormation(OPTIONS_STACK, "UpdateStack", OPTIONS_TEMPLATE, options("ENABLED", "ENABLED"));
        assertEquals(arn, outputValue(describeStacks(OPTIONS_STACK, "UPDATE_COMPLETE"), "CertArn"),
                "a transparency logging change updates the certificate in place");
        describeCertificate(arn).then()
            .statusCode(200)
            .body("Certificate.Options.Export", equalTo("ENABLED"))
            .body("Certificate.Options.CertificateTransparencyLoggingPreference", equalTo("ENABLED"));

        cloudFormation(OPTIONS_STACK, "UpdateStack", OPTIONS_TEMPLATE, options("DISABLED", "ENABLED"));
        String replacement = outputValue(describeStacks(OPTIONS_STACK, "UPDATE_COMPLETE"), "CertArn");
        assertNotEquals(arn, replacement, "an export change replaces the certificate");
        describeCertificate(arn).then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
        describeCertificate(replacement).then()
            .statusCode(200)
            .body("Certificate.DomainName", equalTo("options.cfn-it.example.com"))
            .body("Certificate.Options.Export", equalTo("DISABLED"))
            .body("Certificate.Options.CertificateTransparencyLoggingPreference", equalTo("ENABLED"));

        cloudFormation(OPTIONS_STACK, "DeleteStack", null, Map.of());
        awaitStackDeleted(OPTIONS_STACK);

        describeCertificate(replacement).then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Map<String, String> options(String export, String transparencyLogging) {
        return Map.of("Export", export, "TransparencyLogging", transparencyLogging);
    }

    private static void cloudFormation(String stack, String action, String templateBody,
                                       Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
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

    private static Response describeCertificate(String arn) {
        return given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("{\"CertificateArn\": \"" + arn + "\"}")
        .when().post("/");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
