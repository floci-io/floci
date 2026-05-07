package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityAttributesV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void createEmailIdentity_setsUpDomain() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "v2-attrs.floci.test"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void putEmailIdentityMailFromAttributes_setsDomain() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "MailFromDomain": "mail.v2-attrs.floci.test",
                  "BehaviorOnMxFailure": "REJECT_MESSAGE"
                }
                """)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void getEmailIdentity_includesMailFromAttributes() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/v2-attrs.floci.test")
        .then()
            .statusCode(200)
            .body("MailFromAttributes.MailFromDomain", equalTo("mail.v2-attrs.floci.test"))
            .body("MailFromAttributes.MailFromDomainStatus", equalTo("SUCCESS"))
            .body("MailFromAttributes.BehaviorOnMxFailure", equalTo("REJECT_MESSAGE"));
    }

    @Test
    @Order(4)
    void putEmailIdentityMailFromAttributes_emptyDomain_clears() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"MailFromDomain": ""}
                """)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/v2-attrs.floci.test")
        .then()
            .statusCode(200)
            .body("MailFromAttributes.MailFromDomain", equalTo(""))
            .body("MailFromAttributes.MailFromDomainStatus", equalTo("NOT_STARTED"));
    }

    @Test
    @Order(5)
    void putEmailIdentityMailFromAttributes_unknownIdentity_returnsBadRequest() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"MailFromDomain": "mail.ghost.floci.test"}
                """)
        .when()
            .put("/v2/email/identities/ghost.floci.test/mail-from")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Identity <ghost.floci.test> does not exist."));
    }

    @Test
    @Order(11)
    void putEmailIdentityFeedbackAttributes_unknownIdentity_returnsBadRequest() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailForwardingEnabled": true}
                """)
        .when()
            .put("/v2/email/identities/ghost-feedback.floci.test/feedback")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(
                    "Identity ghost-feedback.floci.test is invalid. Must be a verified email address or domain."));
    }

    @Test
    @Order(6)
    void putEmailIdentityMailFromAttributes_invalidJson_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("[1,2,3]")
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(7)
    void putEmailIdentityMailFromAttributes_missingBody_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(8)
    void putEmailIdentityMailFromAttributes_missingMailFromDomainField_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"BehaviorOnMxFailure": "USE_DEFAULT_VALUE"}
                """)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(9)
    void putEmailIdentityMailFromAttributes_mailFromDomainAsObject_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"MailFromDomain": {"foo": "bar"}}
                """)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(10)
    void putEmailIdentityMailFromAttributes_unknownBehavior_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "MailFromDomain": "mail.v2-attrs.floci.test",
                  "BehaviorOnMxFailure": "BOGUS_VALUE"
                }
                """)
        .when()
            .put("/v2/email/identities/v2-attrs.floci.test/mail-from")
        .then()
            .body("message", containsString(
                    "Member must satisfy enum value set: [REJECT_MESSAGE, USE_DEFAULT_VALUE]"))
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(20)
    void putEmailIdentityDkimAttributes_unknownIdentity_returnsBadRequest() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"SigningEnabled": true}
                """)
        .when()
            .put("/v2/email/identities/ghost-dkim.floci.test/dkim")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(
                    "Domain ghost-dkim.floci.test is not verified for DKIM signing."));
    }

    @Test
    @Order(21)
    void putEmailIdentityDkimAttributes_emailFormatWithUnregisteredParent_returnsBadRequest() {
        // Real SES v2 reports the parent domain (not the full email identity)
        // in the error message even when the input is email-formatted.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"SigningEnabled": true}
                """)
        .when()
            .put("/v2/email/identities/orphan@no-such-parent.floci.test/dkim")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(
                    "Domain no-such-parent.floci.test is not verified for DKIM signing."));
    }

    @Test
    @Order(22)
    void putEmailIdentityDkimAttributes_emailWithRegisteredParentDomain_returnsNoOp() {
        // Real SES v2 accepts the call (200 OK) for an email-format identity
        // whose parent domain is registered, but persists nothing — DKIM is a
        // domain-level concept. The parent domain's DkimAttributes must remain
        // untouched, and no email identity is auto-created.
        Object parentDkimBefore = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/v2-attrs.floci.test")
        .then()
            .statusCode(200)
            .extract().path("DkimAttributes");

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"SigningEnabled": true}
                """)
        .when()
            .put("/v2/email/identities/orphan@v2-attrs.floci.test/dkim")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/v2-attrs.floci.test")
        .then()
            .statusCode(200)
            .body("DkimAttributes", equalTo(parentDkimBefore));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/orphan@v2-attrs.floci.test")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(23)
    void createEmailIdentity_duplicate_returnsAlreadyExists() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "v2-attrs.floci.test"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AlreadyExistsException"))
            .body("message", equalTo(
                    "Email identity v2-attrs.floci.test already exist."));
    }

    @Test
    @Order(24)
    void deleteEmailIdentity_unknownIdentity_returnsNotFound() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/v2/email/identities/ghost-delete.floci.test")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo(
                    "Email identity ghost-delete.floci.test does not exist."));
    }
}
