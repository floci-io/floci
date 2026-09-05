package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * A stack that creates the three custom domain types hands every domain to the TLS certificate
 * manager through the same service methods the API uses. A rename replaces the domain, so the
 * new name is registered too; an in-place update and the stack delete register nothing.
 */
@QuarkusTest
class CfnCustomDomainEnsureHostIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "custom-domain-ensure-host-cfn-it";
    private static final String SUFFIX = ".ensure-host-cfn-it.localhost.floci.io";
    private static final String API_DOMAIN = "api" + SUFFIX;
    private static final String RENAMED_API_DOMAIN = "gateway" + SUFFIX;
    private static final String IOT_DOMAIN = "iot" + SUFFIX;
    private static final String AUTH_DOMAIN = "auth" + SUFFIX;

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "ApiDomainName": {"Type": "String"},
            "IotStatus": {"Type": "String", "Default": "ENABLED"}
          },
          "Resources": {
            "Cert": {
              "Type": "AWS::CertificateManager::Certificate",
              "Properties": {"DomainName": "*%1$s", "ValidationMethod": "DNS"}
            },
            "ApiDomain": {
              "Type": "AWS::ApiGateway::DomainName",
              "Properties": {
                "DomainName": {"Ref": "ApiDomainName"},
                "EndpointConfiguration": {"Types": ["REGIONAL"]},
                "RegionalCertificateArn": {"Ref": "Cert"}
              }
            },
            "IotDomain": {
              "Type": "AWS::IoT::DomainConfiguration",
              "Properties": {
                "DomainConfigurationName": "ensure-host-cfn-it",
                "DomainName": "%2$s",
                "ServerCertificateArns": [{"Ref": "Cert"}],
                "DomainConfigurationStatus": {"Ref": "IotStatus"}
              }
            },
            "Pool": {
              "Type": "AWS::Cognito::UserPool",
              "Properties": {"UserPoolName": "ensure-host-cfn-it-pool"}
            },
            "AuthDomain": {
              "Type": "AWS::Cognito::UserPoolDomain",
              "Properties": {
                "Domain": "%3$s",
                "UserPoolId": {"Ref": "Pool"},
                "CustomDomainConfig": {"CertificateArn": {"Fn::GetAtt": ["Cert", "CertificateArn"]}}
              }
            }
          }
        }
        """.formatted(SUFFIX, IOT_DOMAIN, AUTH_DOMAIN);

    @InjectMock
    TlsCertificateManager certificateManager;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void stackRegistersEveryCustomDomainAndAReplacementButNotAnInPlaceUpdateOrTheDelete() throws Exception {
        cloudFormation("CreateStack", Map.of("ApiDomainName", API_DOMAIN));
        awaitStackStatus("CREATE_COMPLETE");

        verify(certificateManager).ensureHost(API_DOMAIN);
        verify(certificateManager).ensureHost(IOT_DOMAIN);
        verify(certificateManager).ensureHost(AUTH_DOMAIN);
        verifyNoMoreInteractions(certificateManager);

        cloudFormation("UpdateStack", Map.of("ApiDomainName", API_DOMAIN, "IotStatus", "DISABLED"));
        awaitStackStatus("UPDATE_COMPLETE");

        verifyNoMoreInteractions(certificateManager);

        cloudFormation("UpdateStack", Map.of("ApiDomainName", RENAMED_API_DOMAIN, "IotStatus", "DISABLED"));
        awaitStackStatus("UPDATE_COMPLETE");

        verify(certificateManager).ensureHost(RENAMED_API_DOMAIN);
        verifyNoMoreInteractions(certificateManager);

        cloudFormation("DeleteStack", Map.of());
        awaitStackDeleted();

        verifyNoMoreInteractions(certificateManager);
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

    private static String describeStack() {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().extract().asString();
    }

    /** Stack operations run on a background executor; wait for the terminal status. */
    private static void awaitStackStatus(String expected) throws InterruptedException {
        String body = "";
        for (int i = 0; i < 200; i++) {
            body = describeStack();
            if (body.contains("<StackStatus>" + expected + "</StackStatus>")) {
                return;
            }
            if (body.contains("FAILED</StackStatus>") || body.contains("ROLLBACK_COMPLETE</StackStatus>")) {
                fail("stack did not reach " + expected + ": " + body);
            }
            Thread.sleep(50);
        }
        fail("stack did not reach " + expected + " within the timeout: " + body);
    }

    private static void awaitStackDeleted() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            String body = describeStack();
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
}
