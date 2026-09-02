package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for SES V1 Query-protocol ConfigurationSet CRUD.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";

    @Test
    @Order(1)
    void createConfigurationSet() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSet")
            .formParam("ConfigurationSet.Name", "v1-cs-alpha")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("CreateConfigurationSetResponse"));
    }

    @Test
    @Order(2)
    void createConfigurationSet_duplicateRejected() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSet")
            .formParam("ConfigurationSet.Name", "v1-cs-alpha")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .header("X-Amzn-Errortype", (String) null)
            .body(containsString("<Code>ConfigurationSetAlreadyExists</Code>"));
    }

    @Test
    @Order(3)
    void describeConfigurationSet() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", "v1-cs-alpha")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Name>v1-cs-alpha</Name>"));
    }

    @Test
    @Order(4)
    void describeConfigurationSet_unknownReturns400() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", "v1-cs-ghost")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ConfigurationSetDoesNotExist</Code>"));
    }

    @Test
    @Order(5)
    void listConfigurationSets() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSet")
            .formParam("ConfigurationSet.Name", "v1-cs-beta")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "ListConfigurationSets")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Name>v1-cs-alpha</Name>"))
            .body(containsString("<Name>v1-cs-beta</Name>"));
    }

    @Test
    @Order(6)
    void deleteConfigurationSet() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DeleteConfigurationSet")
            .formParam("ConfigurationSetName", "v1-cs-alpha")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("DeleteConfigurationSetResponse"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", "v1-cs-alpha")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ConfigurationSetDoesNotExist</Code>"));
    }

    @Test
    @Order(7)
    void deleteConfigurationSet_unknownReturns400() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DeleteConfigurationSet")
            .formParam("ConfigurationSetName", "v1-cs-ghost")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ConfigurationSetDoesNotExist</Code>"));
    }

    @Test
    @Order(8)
    void createConfigurationSet_missingName() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSet")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    @Order(9)
    void createConfigurationSet_invalidNameCharacters() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSet")
            .formParam("ConfigurationSet.Name", "bad name!")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    @Order(10)
    void createConfigurationSet_nameTooLong() {
        String longName = "a".repeat(65);
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSet")
            .formParam("ConfigurationSet.Name", longName)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    @Order(11)
    void putConfigurationSetDeliveryOptions() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSet")
            .formParam("ConfigurationSet.Name", "v1-cs-delivery")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "PutConfigurationSetDeliveryOptions")
            .formParam("ConfigurationSetName", "v1-cs-delivery")
            .formParam("DeliveryOptions.TlsPolicy", "Require")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("PutConfigurationSetDeliveryOptionsResponse"));
    }

    @Test
    @Order(12)
    void putConfigurationSetDeliveryOptions_unknownSetReturns400() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "PutConfigurationSetDeliveryOptions")
            .formParam("ConfigurationSetName", "v1-cs-ghost")
            .formParam("DeliveryOptions.TlsPolicy", "Require")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ConfigurationSetDoesNotExist</Code>"));
    }

    @Test
    @Order(13)
    void putConfigurationSetDeliveryOptions_invalidTlsPolicyReturns400() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "PutConfigurationSetDeliveryOptions")
            .formParam("ConfigurationSetName", "v1-cs-delivery")
            .formParam("DeliveryOptions.TlsPolicy", "Bogus")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            // Real AWS returns the Smithy enum ValidationError (not the v2 BadRequestException)
            // for an invalid TlsPolicy value; the single quotes are XML-escaped on the wire.
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("deliveryOptions.tlsPolicy"))
            .body(containsString("[Optional, Require]"));
    }

    /**
     * The write path already stored the TLS policy — v2 {@code GetConfigurationSet} returned it —
     * but the v1 projection never read it back, so the Terraform provider re-added the block on
     * every plan. Order 11 above sets a policy and never describes it, which is how the gap
     * survived; this covers the read.
     */
    @Test
    @Order(14)
    void describeConfigurationSetReturnsDeliveryOptions() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSet")
            .formParam("ConfigurationSet.Name", "v1-cs-delivery-readback")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Never set: AWS omits the member even when it is explicitly requested.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", "v1-cs-delivery-readback")
            .formParam("ConfigurationSetAttributeNames.member.1", "deliveryOptions")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<DeliveryOptions>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "PutConfigurationSetDeliveryOptions")
            .formParam("ConfigurationSetName", "v1-cs-delivery-readback")
            .formParam("DeliveryOptions.TlsPolicy", "Require")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // V1 spells it Require, not the V2 REQUIRE that is stored internally.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", "v1-cs-delivery-readback")
            .formParam("ConfigurationSetAttributeNames.member.1", "deliveryOptions")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DeliveryOptions>"))
            .body(containsString("<TlsPolicy>Require</TlsPolicy>"));

        // Gated on the attribute name: without it the member stays out, as AWS does.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", "v1-cs-delivery-readback")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<DeliveryOptions>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "PutConfigurationSetDeliveryOptions")
            .formParam("ConfigurationSetName", "v1-cs-delivery-readback")
            .formParam("DeliveryOptions.TlsPolicy", "Optional")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", "v1-cs-delivery-readback")
            .formParam("ConfigurationSetAttributeNames.member.1", "deliveryOptions")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<TlsPolicy>Optional</TlsPolicy>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DeleteConfigurationSet")
            .formParam("ConfigurationSetName", "v1-cs-delivery-readback")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
