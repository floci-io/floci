package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Integration test for SES per-configuration-set Sending toggle. Covers both:
 *   - v2 PUT /v2/email/configuration-sets/{name}/sending (PutConfigurationSetSendingOptions)
 *   - v1 Query Action=UpdateConfigurationSetSendingEnabled
 * and verifies the toggle is enforced by SendEmail (v2 outbound-emails) with a
 * SendingPausedException response, and that the state is visible on both
 *   - v2 GetConfigurationSet (SendingOptions block)
 *   - v1 DescribeConfigurationSet (ReputationOptions block, reputationOptions attribute)
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetSendingOptionsV2IntegrationTest {

    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String CS = "cs-sending-options";
    private static final String SENDER = "sender@example.com";

    @Test
    @Order(1)
    void createConfigurationSet() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when()
                .post("/v2/email/configuration-sets")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void getConfigurationSet_defaultSendingEnabledTrue() {
        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/configuration-sets/" + CS)
        .then()
                .statusCode(200)
                .body("ConfigurationSetName", equalTo(CS))
                .body("SendingOptions.SendingEnabled", equalTo(true));
    }

    @Test
    @Order(3)
    void sendEmail_defaultEnabled_succeeds() {
        sendEmail(CS).then().statusCode(200);
    }

    @Test
    @Order(4)
    void putSendingOptions_disableViaV2() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"SendingEnabled\":false}")
        .when()
                .put("/v2/email/configuration-sets/" + CS + "/sending")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(5)
    void getConfigurationSet_reflectsDisabled() {
        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/configuration-sets/" + CS)
        .then()
                .statusCode(200)
                .body("SendingOptions.SendingEnabled", equalTo(false));
    }

    @Test
    @Order(6)
    void v2SendEmail_whenDisabled_isRejectedWithSendingPausedException() {
        sendEmail(CS)
        .then()
                .statusCode(400)
                .body("__type", equalTo("SendingPausedException"))
                .body("message", equalTo("Sending is paused for configuration set " + CS));
    }

    @Test
    @Order(7)
    void v1SendEmail_whenDisabled_isRejectedWithConfigurationSetSendingPausedException() {
        // v1 Query API returns the longer ConfigurationSetSendingPausedException error code
        // (matching the AWS v1 SES wire contract). The v2 controller's remapV1Exception
        // narrows this to SendingPausedException for v2 callers only.
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", SES_AUTH)
                .formParam("Action", "SendEmail")
                .formParam("Source", SENDER)
                .formParam("Destination.ToAddresses.member.1", "recipient@example.com")
                .formParam("Message.Subject.Data", "v1-disabled")
                .formParam("Message.Body.Text.Data", "hi")
                .formParam("ConfigurationSetName", CS)
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body(containsString("<Code>ConfigurationSetSendingPausedException</Code>"))
                .body(containsString(
                        "<Message>Sending is paused for configuration set " + CS + "</Message>"));
    }

    @Test
    @Order(8)
    void v1_describeConfigurationSet_reputationOptions_reflectsDisabled() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", SES_AUTH)
                .formParam("Action", "DescribeConfigurationSet")
                .formParam("ConfigurationSetName", CS)
                .formParam("ConfigurationSetAttributeNames.member.1", "reputationOptions")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body(containsString("<SendingEnabled>false</SendingEnabled>"));
    }

    @Test
    @Order(9)
    void v1_updateConfigurationSetSendingEnabled_reEnables() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", SES_AUTH)
                .formParam("Action", "UpdateConfigurationSetSendingEnabled")
                .formParam("ConfigurationSetName", CS)
                .formParam("Enabled", "true")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(10)
    void sendEmail_afterReEnable_succeeds() {
        sendEmail(CS).then().statusCode(200);
    }

    @Test
    @Order(11)
    void getConfigurationSet_reflectsReEnabled() {
        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/configuration-sets/" + CS)
        .then()
                .statusCode(200)
                .body("SendingOptions.SendingEnabled", equalTo(true));
    }

    @Test
    @Order(12)
    void putSendingOptions_missingSendingEnabled_returns400() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{}")
        .when()
                .put("/v2/email/configuration-sets/" + CS + "/sending")
        .then()
                .statusCode(400)
                .body("message", containsString("SendingEnabled"));
    }

    @Test
    @Order(13)
    void putSendingOptions_nonBooleanSendingEnabled_returns400() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"SendingEnabled\":\"yes\"}")
        .when()
                .put("/v2/email/configuration-sets/" + CS + "/sending")
        .then()
                .statusCode(400)
                .body("message", containsString("SendingEnabled"));
    }

    @Test
    @Order(14)
    void putSendingOptions_unknownConfigSet_returns404_NotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"SendingEnabled\":false}")
        .when()
                .put("/v2/email/configuration-sets/does-not-exist/sending")
        .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Configuration set <does-not-exist> does not exist."));
    }

    @Test
    @Order(15)
    void v1_updateConfigurationSetSendingEnabled_nonBooleanEnabled_returnsInvalidParameterValue() {
        // parseRequiredBoolean enforces AWS-style "true"|"false". Anything else (e.g.
        // "yes") must surface as InvalidParameterValue instead of being silently
        // coerced to false (which would disable sending without the caller realizing).
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", SES_AUTH)
                .formParam("Action", "UpdateConfigurationSetSendingEnabled")
                .formParam("ConfigurationSetName", CS)
                .formParam("Enabled", "yes")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Enabled"));
    }

    private static io.restassured.response.Response sendEmail(String configSet) {
        return given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["recipient@example.com"]},
                      "ConfigurationSetName": "%s",
                      "Content": {
                        "Simple": {
                          "Subject": {"Data": "send-toggle"},
                          "Body": {"Text": {"Data": "hi"}}
                        }
                      }
                    }
                    """.formatted(SENDER, configSet))
        .when()
                .post("/v2/email/outbound-emails");
    }
}
