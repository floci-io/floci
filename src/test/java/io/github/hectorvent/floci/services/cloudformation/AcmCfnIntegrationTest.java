package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisions an {@code AWS::CertificateManager::Certificate} through a CloudFormation stack and
 * asserts that {@code Ref} and {@code Fn::GetAtt CertificateArn} are a real ARN that ACM's
 * {@code DescribeCertificate} finds. A status-only assertion would pass for the stub arm too,
 * where the attribute resolves to the literal {@code Cert.CertificateArn}.
 */
@QuarkusTest
class AcmCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/cloudformation/aws4_request";
    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String STACK = "acm-cfn-it";

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

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void certificateStackExposesAnArnThatDescribeCertificateFinds() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", STACK)
            .formParam("TemplateBody", TEMPLATE)
        .when().post("/").then().statusCode(200);

        String stacks = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        String arn = outputValue(stacks, "CertArn");
        assertTrue(arn.startsWith("arn:aws:acm:us-east-1:"), "Fn::GetAtt CertificateArn must be an ARN: " + arn);
        assertEquals(arn, outputValue(stacks, "CertRef"));

        describeCertificate(arn).then()
            .statusCode(200)
            .body("Certificate.CertificateArn", equalTo(arn))
            .body("Certificate.DomainName", equalTo("api.cfn-it.example.com"))
            .body("Certificate.Status", equalTo("ISSUED"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200);

        describeCertificate(arn).then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static io.restassured.response.Response describeCertificate(String arn) {
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
