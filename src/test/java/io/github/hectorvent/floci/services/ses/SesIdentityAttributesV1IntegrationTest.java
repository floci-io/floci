package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityAttributesV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";

    @Test
    @Order(1)
    void verifyDomainIdentity_setsUpDomain() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "VerifyDomainIdentity")
            .formParam("Domain", "v1-attrs.floci.test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("VerifyDomainIdentityResponse"));
    }

    @Test
    @Order(2)
    void setIdentityMailFromDomain_setsAttributes() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityMailFromDomain")
            .formParam("Identity", "v1-attrs.floci.test")
            .formParam("MailFromDomain", "mail.v1-attrs.floci.test")
            .formParam("BehaviorOnMXFailure", "RejectMessage")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("SetIdentityMailFromDomainResponse"));
    }

    @Test
    @Order(3)
    void getIdentityMailFromDomainAttributes_returnsValues() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "GetIdentityMailFromDomainAttributes")
            .formParam("Identities.member.1", "v1-attrs.floci.test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MailFromDomain>mail.v1-attrs.floci.test</MailFromDomain>"))
            .body(containsString("<MailFromDomainStatus>Success</MailFromDomainStatus>"))
            .body(containsString("<BehaviorOnMXFailure>RejectMessage</BehaviorOnMXFailure>"));
    }

    @Test
    @Order(4)
    void setIdentityMailFromDomain_emptyDomain_clears() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityMailFromDomain")
            .formParam("Identity", "v1-attrs.floci.test")
            .formParam("MailFromDomain", "")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "GetIdentityMailFromDomainAttributes")
            .formParam("Identities.member.1", "v1-attrs.floci.test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MailFromDomain></MailFromDomain>"))
            .body(containsString("<MailFromDomainStatus>Pending</MailFromDomainStatus>"));
    }

    @Test
    @Order(5)
    void setIdentityFeedbackForwardingEnabled_togglesFlag() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityFeedbackForwardingEnabled")
            .formParam("Identity", "v1-attrs.floci.test")
            .formParam("ForwardingEnabled", "false")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("SetIdentityFeedbackForwardingEnabledResponse"));
    }

    @Test
    @Order(6)
    void setIdentityHeadersInNotificationsEnabled_setsFlag() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityHeadersInNotificationsEnabled")
            .formParam("Identity", "v1-attrs.floci.test")
            .formParam("NotificationType", "Bounce")
            .formParam("Enabled", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("SetIdentityHeadersInNotificationsEnabledResponse"));
    }

    @Test
    @Order(10)
    void setIdentityMailFromDomain_unknownIdentity_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityMailFromDomain")
            .formParam("Identity", "ghost.floci.test")
            .formParam("MailFromDomain", "mail.ghost.floci.test")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"))
            .body(containsString("Identity &lt;ghost.floci.test&gt; does not exist."));
    }

    @Test
    @Order(11)
    void setIdentityMailFromDomain_missingMailFromDomain_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityMailFromDomain")
            .formParam("Identity", "v1-attrs.floci.test")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
    }

    @Test
    @Order(12)
    void setIdentityFeedbackForwardingEnabled_missingForwardingEnabled_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityFeedbackForwardingEnabled")
            .formParam("Identity", "v1-attrs.floci.test")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
    }

    @Test
    @Order(13)
    void setIdentityHeadersInNotificationsEnabled_missingEnabled_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityHeadersInNotificationsEnabled")
            .formParam("Identity", "v1-attrs.floci.test")
            .formParam("NotificationType", "Bounce")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
    }

    @Test
    @Order(14)
    void setIdentityFeedbackForwardingEnabled_invalidBoolean_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityFeedbackForwardingEnabled")
            .formParam("Identity", "v1-attrs.floci.test")
            .formParam("ForwardingEnabled", "yes")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
    }

    @Test
    @Order(15)
    void setIdentityMailFromDomain_unknownBehaviorOnMxFailure_returnsValidationError() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityMailFromDomain")
            .formParam("Identity", "v1-attrs.floci.test")
            .formParam("MailFromDomain", "mail.v1-attrs.floci.test")
            .formParam("BehaviorOnMXFailure", "BogusValue")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("Member must satisfy enum value set: [RejectMessage, UseDefaultValue]"));
    }

    @Test
    @Order(20)
    void setIdentityMailFromDomain_whitespaceMailFromDomain_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityMailFromDomain")
            .formParam("Identity", "v1-attrs.floci.test")
            .formParam("MailFromDomain", "   ")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
    }

    @Test
    @Order(19)
    void setIdentityMailFromDomain_emptyBehaviorOnMxFailure_returnsValidationError() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityMailFromDomain")
            .formParam("Identity", "v1-attrs.floci.test")
            .formParam("MailFromDomain", "mail.v1-attrs.floci.test")
            .formParam("BehaviorOnMXFailure", "")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"));
    }

    @Test
    @Order(18)
    void setIdentityHeadersInNotificationsEnabled_unknownNotificationType_returnsValidationError() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityHeadersInNotificationsEnabled")
            .formParam("Identity", "v1-attrs.floci.test")
            .formParam("NotificationType", "bounce")
            .formParam("Enabled", "true")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("Member must satisfy enum value set"));
    }

    @Test
    @Order(16)
    void setIdentityHeadersInNotificationsEnabled_unknownIdentity_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityHeadersInNotificationsEnabled")
            .formParam("Identity", "ghost-headers.floci.test")
            .formParam("NotificationType", "Bounce")
            .formParam("Enabled", "true")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"))
            .body(containsString("Identity ghost-headers.floci.test is invalid. It must be a verified email address or domain."));
    }

    @Test
    @Order(21)
    void setIdentityFeedbackForwardingEnabled_unknownIdentity_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SetIdentityFeedbackForwardingEnabled")
            .formParam("Identity", "ghost-feedback.floci.test")
            .formParam("ForwardingEnabled", "true")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"))
            .body(containsString("Identity ghost-feedback.floci.test is invalid. Must be a verified email address or domain."));
    }

    @Test
    @Order(17)
    void getIdentityNotificationAttributes_reflectsForwardingAndHeaderFlags() {
        // Order(5) disabled forwarding, Order(6) enabled headers-in-Bounce.
        // The Get call should now report those, not hard-coded defaults.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "GetIdentityNotificationAttributes")
            .formParam("Identities.member.1", "v1-attrs.floci.test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ForwardingEnabled>false</ForwardingEnabled>"))
            .body(containsString("<HeadersInBounceNotificationsEnabled>true</HeadersInBounceNotificationsEnabled>"))
            .body(containsString("<HeadersInComplaintNotificationsEnabled>false</HeadersInComplaintNotificationsEnabled>"))
            .body(containsString("<HeadersInDeliveryNotificationsEnabled>false</HeadersInDeliveryNotificationsEnabled>"));
    }

    @Test
    @Order(22)
    void getIdentityNotificationAttributes_unknownIdentity_omittedFromMap() {
        // Real AWS returns an empty NotificationAttributes map for an identity
        // that doesn't exist, rather than an entry with empty values
        // (verified against real AWS SES on 2026-06-13).
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "GetIdentityNotificationAttributes")
            .formParam("Identities.member.1", "ghost-notif.floci.test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("GetIdentityNotificationAttributesResponse"))
            .body(containsString("<NotificationAttributes></NotificationAttributes>"))
            .body(not(containsString("ghost-notif.floci.test")));
    }

    @Test
    @Order(23)
    void getIdentityNotificationAttributes_mixedKnownAndUnknown_returnsOnlyKnown() {
        // The unknown identity is dropped from the map; the known one stays.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "GetIdentityNotificationAttributes")
            .formParam("Identities.member.1", "v1-attrs.floci.test")
            .formParam("Identities.member.2", "ghost-notif2.floci.test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<key>v1-attrs.floci.test</key>"))
            .body(not(containsString("ghost-notif2.floci.test")));
    }
}
