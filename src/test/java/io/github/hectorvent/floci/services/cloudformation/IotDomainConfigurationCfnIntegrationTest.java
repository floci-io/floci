package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::IoT::DomainConfiguration} through a CloudFormation stack and asserts
 * that {@code Ref} and every {@code Fn::GetAtt} attribute carry what {@code DescribeDomainConfiguration}
 * reports, rather than the literal {@code Domain.Arn} the stub arm would leave behind. Also
 * covers the in-place status update and that deleting the stack removes the configuration.
 */
@QuarkusTest
class IotDomainConfigurationCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "iot-domain-cfn-it";
    private static final String NAME = "iot-cfn-it-domain";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-1111-1111-1111-111111111111";

    private static String template(String status) {
        return """
            {
              "Resources": {
                "Domain": {
                  "Type": "AWS::IoT::DomainConfiguration",
                  "Properties": {
                    "DomainConfigurationName": "%s",
                    "DomainName": "iot.cfn-it.example.com",
                    "ServiceType": "DATA",
                    "ServerCertificateArns": ["%s"],
                    "AuthorizerConfig": {"DefaultAuthorizerName": "cfn-it-authorizer", "AllowAuthorizerOverride": true},
                    "DomainConfigurationStatus": "%s",
                    "Tags": [{"Key": "stack", "Value": "iot-domain-cfn-it"}]
                  }
                }
              },
              "Outputs": {
                "DomainRef": {"Value": {"Ref": "Domain"}},
                "DomainArn": {"Value": {"Fn::GetAtt": ["Domain", "Arn"]}},
                "DomainType": {"Value": {"Fn::GetAtt": ["Domain", "DomainType"]}},
                "ServerCertificates": {"Value": {"Fn::GetAtt": ["Domain", "ServerCertificates"]}}
              }
            }
            """.formatted(NAME, CERTIFICATE_ARN, status);
    }

    @Test
    void domainConfigurationStackExposesRealAttributesUpdatesInPlaceAndDeletesTheConfiguration() throws Exception {
        cloudFormation("CreateStack", template("ENABLED"));

        String stacks = describeStacks("CREATE_COMPLETE");
        String arn = outputValue(stacks, "DomainArn");
        assertEquals(NAME, outputValue(stacks, "DomainRef"));
        assertTrue(arn.startsWith("arn:aws:iot:us-east-1:000000000000:domainconfiguration/" + NAME + "/"),
                "Fn::GetAtt Arn must be the configuration ARN: " + arn);
        assertEquals("CUSTOMER_MANAGED", outputValue(stacks, "DomainType"));
        JsonNode certificates = new ObjectMapper().readTree(outputValue(stacks, "ServerCertificates"));
        assertEquals(CERTIFICATE_ARN, certificates.get(0).path("ServerCertificateArn").asText());
        assertEquals("VALID", certificates.get(0).path("ServerCertificateStatus").asText());

        given()
        .when()
            .get("/domainConfigurations/" + NAME)
        .then()
            .statusCode(200)
            .body("domainConfigurationArn", equalTo(arn))
            .body("domainConfigurationStatus", equalTo("ENABLED"))
            .body("domainName", equalTo("iot.cfn-it.example.com"))
            .body("domainType", equalTo("CUSTOMER_MANAGED"))
            .body("authorizerConfig.defaultAuthorizerName", equalTo("cfn-it-authorizer"))
            .body("authorizerConfig.allowAuthorizerOverride", equalTo(true));
        given()
            .queryParam("resourceArn", arn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", hasItem("stack"));

        cloudFormation("UpdateStack", template("DISABLED"));

        describeStacks("UPDATE_COMPLETE");
        given()
        .when()
            .get("/domainConfigurations/" + NAME)
        .then()
            .statusCode(200)
            .body("domainConfigurationArn", equalTo(arn))
            .body("domainConfigurationStatus", equalTo("DISABLED"));

        cloudFormation("DeleteStack", null);
        awaitStackDeleted();

        given()
        .when()
            .get("/domainConfigurations/" + NAME)
        .then()
            .statusCode(404)
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

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
