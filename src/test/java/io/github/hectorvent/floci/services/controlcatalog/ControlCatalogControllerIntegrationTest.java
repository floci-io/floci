package io.github.hectorvent.floci.services.controlcatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class ControlCatalogControllerIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getControlReturnsRcpMetadataForEnterprisePerimeterControl() {
        String arn = "arn:aws:controlcatalog:::control/7mo7a2h2ebsq71l8k6uzr96ou";

        given()
                .contentType("application/json")
                .header("Authorization", auth("us-east-1"))
                .body("{\"ControlArn\":\"" + arn + "\"}")
                .when()
                .post("/get-control")
                .then()
                .statusCode(200)
                .body("Arn", equalTo(arn))
                .body("Aliases", hasItem("CT.S3.PV.5"))
                .body("Behavior", equalTo("PREVENTIVE"))
                .body("RegionConfiguration.Scope", equalTo("GLOBAL"))
                .body("Implementation.Type", equalTo("AWS::Organizations::Policy::RESOURCE_CONTROL_POLICY"))
                .body("Parameters.Name", hasItem("ExemptedPrincipalArns"));
    }

    @Test
    void getControlDistinguishesLegacyScpFromDetectiveConfigRule() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("us-west-2"))
                .body("{\"ControlArn\":\"arn:aws:controltower:us-west-2::control/AWS-GR_RESTRICT_ROOT_USER\"}")
                .when()
                .post("/get-control")
                .then()
                .statusCode(200)
                .body("Behavior", equalTo("PREVENTIVE"))
                .body("RegionConfiguration.Scope", equalTo("GLOBAL"))
                .body("Implementation.Type", equalTo("AWS::Organizations::Policy::SERVICE_CONTROL_POLICY"));

        given()
                .contentType("application/json")
                .header("Authorization", auth("us-west-2"))
                .body("{\"ControlArn\":\"arn:aws:controltower:us-west-2::control/AWS-GR_RDS_STORAGE_ENCRYPTED\"}")
                .when()
                .post("/get-control")
                .then()
                .statusCode(200)
                .body("Behavior", equalTo("DETECTIVE"))
                .body("RegionConfiguration.Scope", equalTo("REGIONAL"))
                .body("RegionConfiguration.DeployableRegions[0]", equalTo("us-west-2"))
                .body("Implementation.Type", equalTo("AWS::Config::ConfigRule"));
    }

    @Test
    void getControlRejectsMissingAndMalformedArn() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("us-east-1"))
                .body("{}")
                .when()
                .post("/get-control")
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"));

        given()
                .contentType("application/json")
                .header("Authorization", auth("us-east-1"))
                .body("{\"ControlArn\":\"not-an-arn-but-long-enough-to-pass-length-check\"}")
                .when()
                .post("/get-control")
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void getControlReturnsResourceNotFoundForUnknownWellFormedControl() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("us-east-1"))
                .body("{\"ControlArn\":\"arn:aws:controlcatalog:::control/aaaaaaaaaaaaaaaaaaaaaaaaa\"}")
                .when()
                .post("/get-control")
                .then()
                .statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void listControlsSupportsImplementationFilterAndPagination() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("us-east-1"))
                .body("{\"Filter\":{\"Implementations\":{\"Types\":[\"AWS::Organizations::Policy::RESOURCE_CONTROL_POLICY\"]}}}")
                .when()
                .post("/list-controls?maxResults=2")
                .then()
                .statusCode(200)
                .body("Controls.size()", equalTo(2))
                .body("Controls[0].Implementation.Type", equalTo("AWS::Organizations::Policy::RESOURCE_CONTROL_POLICY"))
                .body("NextToken", equalTo("2"));

        given()
                .contentType("application/json")
                .header("Authorization", auth("us-east-1"))
                .body("{}")
                .when()
                .post("/list-controls?maxResults=0")
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void healthReportsControlCatalogService() {
        given()
                .when()
                .get("/_floci/health")
                .then()
                .statusCode(200)
                .body(containsString("controlcatalog"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260904/" + region + "/controlcatalog/aws4_request";
    }
}
