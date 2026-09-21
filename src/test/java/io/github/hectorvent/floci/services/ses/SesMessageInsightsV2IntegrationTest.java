package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for {@code GET /v2/email/insights/{MessageId}} (GetMessageInsights).
 *
 * <p>Two behaviours are probe-confirmed against real SES (2026-09-21, us-east-1): the operation is
 * gated on Virtual Deliverability Manager and reports that before it looks at the message id, and a
 * missing message names the account alongside the id. Uses an isolated region because VDM is
 * account and region scoped, and enables VDM as its own ordered step so the gate can be observed
 * first.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesMessageInsightsV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-north-1/ses/aws4_request";
    private static final String SENDER = "insights-sender@example.com";
    private static final String ACCOUNT_A_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260101/eu-north-1/ses/aws4_request";
    private static final String ACCOUNT_B_AUTH =
            "AWS4-HMAC-SHA256 Credential=444455556666/20260101/eu-north-1/ses/aws4_request";

    private static String send(String destinationJson, String subject) {
        return send(AUTH, destinationJson, subject);
    }

    private static String send(String auth, String destinationJson, String subject) {
        return given().contentType("application/json").header("Authorization", auth)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": %s,
                      "EmailTags": [{"Name": "campaign", "Value": "insights"}],
                      "Content": {"Simple": {"Subject": {"Data": "%s"},
                                             "Body": {"Text": {"Data": "hi"}}}}
                    }
                    """.formatted(SENDER, destinationJson, subject))
        .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .extract().jsonPath().getString("MessageId");
    }

    @Test
    @Order(0)
    void getMessageInsights_vdmDisabled_reportsTheVdmGateBeforeTheMessageId() {
        given().header("Authorization", AUTH)
        .when().get("/v2/email/insights/not-even-an-id").then().statusCode(404)
                .body("message", containsString(
                        "To use this feature you must enable Virtual Deliverability Manager"));
    }

    @Test
    @Order(1)
    void enableVdmAndVerifyTheSender() {
        enableVdm(AUTH);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\":\"" + SENDER + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
    }

    @Test
    @Order(2)
    void getMessageInsights_unknownMessage_namesTheAccount() {
        given().header("Authorization", AUTH)
        .when().get("/v2/email/insights/no-such-message").then().statusCode(404)
                .body("message", containsString("Message <no-such-message> not found for account <"));
    }

    @Test
    @Order(3)
    void getMessageInsights_ordinaryRecipient_reportsSendThenDelivery() {
        String messageId = send("{\"ToAddresses\": [\"user@example.com\"]}", "ordinary");

        given().header("Authorization", AUTH)
        .when().get("/v2/email/insights/" + messageId).then().statusCode(200)
                .body("MessageId", equalTo(messageId))
                .body("FromEmailAddress", equalTo(SENDER))
                .body("Subject", equalTo("ordinary"))
                .body("EmailTags", hasSize(1))
                .body("EmailTags[0].Name", equalTo("campaign"))
                .body("EmailTags[0].Value", equalTo("insights"))
                .body("Insights", hasSize(1))
                .body("Insights[0].Destination", equalTo("user@example.com"))
                .body("Insights[0].Events.Type", contains("SEND", "DELIVERY"))
                .body("Insights[0].Events[0].Timestamp", notNullValue())
                .body("Insights[0].Events[0].Details", nullValue())
                // AWS reports UNKNOWN_ISP when it cannot identify the provider, which is always
                // Floci's case.
                .body("Insights[0].Isp", equalTo("UNKNOWN_ISP"));
    }

    @Test
    @Order(4)
    void getMessageInsights_bounceSimulator_carriesTheBounceDetails() {
        String messageId = send("{\"ToAddresses\": [\"bounce@simulator.amazonses.com\"]}", "bounced");

        given().header("Authorization", AUTH)
        .when().get("/v2/email/insights/" + messageId).then().statusCode(200)
                .body("Insights[0].Events.Type", contains("SEND", "BOUNCE"))
                .body("Insights[0].Events[1].Details.Bounce.BounceType", equalTo("PERMANENT"))
                .body("Insights[0].Events[1].Details.Bounce.BounceSubType", equalTo("General"))
                .body("Insights[0].Events[1].Details.Bounce.DiagnosticCode",
                        containsString("550 5.1.1"))
                .body("Insights[0].Events[1].Details.Complaint", nullValue());
    }

    @Test
    @Order(5)
    void getMessageInsights_complaintSimulator_deliversBeforeItComplains() {
        String messageId = send("{\"ToAddresses\": [\"complaint@simulator.amazonses.com\"]}", "complained");

        given().header("Authorization", AUTH)
        .when().get("/v2/email/insights/" + messageId).then().statusCode(200)
                .body("Insights[0].Events.Type", contains("SEND", "DELIVERY", "COMPLAINT"))
                .body("Insights[0].Events[2].Details.Complaint.ComplaintFeedbackType",
                        equalTo("abuse"))
                .body("Insights[0].Events[2].Details.Complaint.ComplaintSubType", nullValue())
                .body("Insights[0].Events[2].Details.Bounce", nullValue());
    }

    @Test
    @Order(6)
    void getMessageInsights_multipleRecipients_reportsOneTimelineEach() {
        // Real SES tracks nothing for a multi-recipient message, so an AWS response here would be a
        // NotFoundException. Floci deliberately records every recipient instead, because dropping
        // these would leave most local test traffic invisible.
        String messageId = send(
                "{\"ToAddresses\": [\"to@example.com\"], \"CcAddresses\": [\"cc@example.com\"]}",
                "multi");

        given().header("Authorization", AUTH)
        .when().get("/v2/email/insights/" + messageId).then().statusCode(200)
                .body("Insights", hasSize(2))
                .body("Insights.Destination", contains("to@example.com", "cc@example.com"))
                .body("Insights[1].Events.Type", contains("SEND", "DELIVERY"));
    }

    @Test
    @Order(7)
    void getMessageInsights_trailingSlash_matchesTheSameRoute() {
        // The Smithy model's requestUri is /v2/email/insights/{MessageId}/ with a trailing slash,
        // so that is the form an SDK client sends.
        String messageId = send("{\"ToAddresses\": [\"slash@example.com\"]}", "slash");

        given().header("Authorization", AUTH)
        .when().get("/v2/email/insights/" + messageId + "/").then().statusCode(200)
                .body("MessageId", equalTo(messageId));
    }

    @Test
    @Order(8)
    void getMessageInsights_rawSend_reportsTheMimeSubject() {
        String raw = "From: " + SENDER + "\r\nTo: raw@example.com\r\n"
                + "Subject: raw-mime-subject\r\n\r\nhi\r\n";
        String encoded = Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        String messageId = given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"FromEmailAddress": "%s",
                     "Destination": {"ToAddresses": ["raw@example.com"]},
                     "Content": {"Raw": {"Data": "%s"}}}
                    """.formatted(SENDER, encoded))
        .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .extract().jsonPath().getString("MessageId");

        given().header("Authorization", AUTH)
        .when().get("/v2/email/insights/" + messageId).then().statusCode(200)
                .body("Subject", equalTo("raw-mime-subject"))
                .body("Insights[0].Destination", equalTo("raw@example.com"));

        // The MIME parser yields "" for a missing Subject header; the member must stay absent.
        String noSubject = "From: " + SENDER + "\r\nTo: raw@example.com\r\n\r\nhi\r\n";
        String noSubjectId = given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"FromEmailAddress": "%s",
                     "Destination": {"ToAddresses": ["raw@example.com"]},
                     "Content": {"Raw": {"Data": "%s"}}}
                    """.formatted(SENDER, Base64.getEncoder()
                        .encodeToString(noSubject.getBytes(StandardCharsets.UTF_8))))
        .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .extract().jsonPath().getString("MessageId");

        given().header("Authorization", AUTH)
        .when().get("/v2/email/insights/" + noSubjectId).then().statusCode(200)
                .body("$", not(hasKey("Subject")));
    }

    @Test
    @Order(9)
    void getMessageInsights_suppressedRecipient_isNeverDelivered() {
        // Account-level suppression defaults to [BOUNCE, COMPLAINT], so no configuration set is
        // needed for the entry to take effect on the send.
        for (String entry : new String[] {"suppressed@example.com:COMPLAINT", "bounced@example.com:BOUNCE"}) {
            given().contentType("application/json").header("Authorization", AUTH)
                    .body("{\"EmailAddress\":\"%s\",\"Reason\":\"%s\"}"
                            .formatted(entry.split(":")[0], entry.split(":")[1]))
            .when().put("/v2/email/suppression/addresses").then().statusCode(200);
        }

        try {
            String messageId = send(
                    "{\"ToAddresses\": [\"suppressed@example.com\", \"bounced@example.com\"]}", "suppressed");

            // Neither recipient reached the relay, so the details are the suppression-specific
            // values SES reports for insights (upper snake case, unlike the published events).
            given().header("Authorization", AUTH)
            .when().get("/v2/email/insights/" + messageId).then().statusCode(200)
                    .body("Insights[0].Destination", equalTo("suppressed@example.com"))
                    .body("Insights[0].Events.Type", contains("SEND", "COMPLAINT"))
                    .body("Insights[0].Events[1].Details.Complaint.ComplaintSubType",
                            equalTo("ON_ACCOUNT_SUPPRESSION_LIST"))
                    .body("Insights[0].Events[1].Details.Complaint.ComplaintFeedbackType", nullValue())
                    .body("Insights[1].Destination", equalTo("bounced@example.com"))
                    .body("Insights[1].Events.Type", contains("SEND", "BOUNCE"))
                    .body("Insights[1].Events[1].Details.Bounce.BounceType", equalTo("PERMANENT"))
                    .body("Insights[1].Events[1].Details.Bounce.BounceSubType",
                            equalTo("ON_ACCOUNT_SUPPRESSION_LIST"))
                    .body("Insights[1].Events[1].Details.Bounce.DiagnosticCode",
                            containsString("suppression list for your account"));
        } finally {
            // A failed assertion must not leave the entries on the shared region's list.
            for (String address : new String[] {"suppressed@example.com", "bounced@example.com"}) {
                given().header("Authorization", AUTH)
                .when().delete("/v2/email/suppression/addresses/" + address);
            }
        }
    }

    @Test
    @Order(10)
    void getMessageInsights_isScopedToTheCallingAccount() {
        // A 12-digit access key id in the credential is the account id, so these are two accounts.
        // Both need VDM on, otherwise account B would be refused by the gate and the test would
        // pass without ever proving isolation.
        enableVdm(ACCOUNT_A_AUTH);
        enableVdm(ACCOUNT_B_AUTH);
        given().contentType("application/json").header("Authorization", ACCOUNT_A_AUTH)
                .body("{\"EmailIdentity\":\"" + SENDER + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        String messageId = send(ACCOUNT_A_AUTH, "{\"ToAddresses\": [\"user@example.com\"]}", "scoped");

        given().header("Authorization", ACCOUNT_A_AUTH)
        .when().get("/v2/email/insights/" + messageId).then().statusCode(200)
                .body("MessageId", equalTo(messageId));

        given().header("Authorization", ACCOUNT_B_AUTH)
        .when().get("/v2/email/insights/" + messageId).then().statusCode(404)
                .body("message", containsString("not found for account <444455556666>"));
    }

    @Test
    @Order(11)
    void cleanUpVdmAndIdentities() {
        for (String auth : new String[] {AUTH, ACCOUNT_A_AUTH, ACCOUNT_B_AUTH}) {
            disableVdm(auth);
            // Account B never verified the sender; the delete is best effort so the status is not
            // asserted.
            given().header("Authorization", auth)
            .when().delete("/v2/email/identities/" + SENDER);
        }
    }

    private static void enableVdm(String auth) {
        given().contentType("application/json").header("Authorization", auth)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"ENABLED\"}}")
        .when().put("/v2/email/account/vdm").then().statusCode(200);
    }

    private static void disableVdm(String auth) {
        given().contentType("application/json").header("Authorization", auth)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"DISABLED\"}}")
        .when().put("/v2/email/account/vdm").then().statusCode(200);
    }
}
