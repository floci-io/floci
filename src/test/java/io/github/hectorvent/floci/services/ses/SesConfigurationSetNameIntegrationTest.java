package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Configuration-set name validation messages, shared by the V1 Query and V2 REST JSON operations.
 */
@QuarkusTest
class SesConfigurationSetNameIntegrationTest {

    private static final String V2_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String V1_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String SIXTY_FIVE =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void v2_invalidCharacters_nameTheOffendingValue() {
        given()
            .header("Authorization", V2_AUTH)
        .when()
            .get("/v2/email/configuration-sets/a.b")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(
                "Invalid configuration set name <a.b>: only alphanumeric ASCII characters, '_', and '-' are allowed."));
    }

    @Test
    void v2_lengthIsCheckedBeforeCharacters() {
        given()
            .header("Authorization", V2_AUTH)
        .when()
            .delete("/v2/email/configuration-sets/b." + SIXTY_FIVE)
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Configuration set name cannot exceed 64 characters."));
    }

    @Test
    void v2_createWithEmptyName_mustBeSpecified() {
        given()
            .contentType("application/json")
            .header("Authorization", V2_AUTH)
            .body("{\"ConfigurationSetName\": \"\"}")
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("The configuration set name must be specified."));
    }

    @Test
    void v2_createWithInvalidName_rejectsBeforeStoring() {
        given()
            .contentType("application/json")
            .header("Authorization", V2_AUTH)
            .body("{\"ConfigurationSetName\": \"bad name\"}")
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("message", equalTo(
                "Invalid configuration set name <bad name>: only alphanumeric ASCII characters, '_', and '-' are allowed."));
    }

    @Test
    void v1_invalidCharacters_sameMessageAsV2() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", "a.b")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"))
            .body("ErrorResponse.Error.Message", equalTo(
                "Invalid configuration set name <a.b>: only alphanumeric ASCII characters, '_', and '-' are allowed."));
    }

    @Test
    void v1_tooLong() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "DeleteConfigurationSet")
            .formParam("ConfigurationSetName", SIXTY_FIVE)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo("Configuration set name cannot exceed 64 characters."));
    }

    @Test
    void v1_emptyName_mustBeSpecified() {
        for (String[] action : new String[][] {
            {"CreateConfigurationSet", "ConfigurationSet.Name"},
            {"DescribeConfigurationSet", "ConfigurationSetName"},
            {"DeleteConfigurationSet", "ConfigurationSetName"},
            {"UpdateConfigurationSetSendingEnabled", "ConfigurationSetName"},
        }) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", V1_AUTH)
                .formParam("Action", action[0])
                .formParam(action[1], "")
                .formParam("Enabled", "true")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body("ErrorResponse.Error.Message", equalTo("The configuration set name must be specified."));
        }
    }

    @Test
    void v1_updateSendingEnabled_validatesTheName() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "UpdateConfigurationSetSendingEnabled")
            .formParam("ConfigurationSetName", "a.b")
            .formParam("Enabled", "true")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo(
                "Invalid configuration set name <a.b>: only alphanumeric ASCII characters, '_', and '-' are allowed."));
    }

    @Test
    void v1_sendEmail_validatesTheConfigurationSetName() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "SendEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "success@simulator.amazonses.com")
            .formParam("Message.Subject.Data", "s")
            .formParam("Message.Body.Text.Data", "t")
            .formParam("ConfigurationSetName", SIXTY_FIVE)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("Configuration set name cannot exceed 64 characters."));
    }
}
