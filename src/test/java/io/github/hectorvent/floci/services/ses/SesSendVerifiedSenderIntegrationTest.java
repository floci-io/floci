package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The send operations require a verified sender, as on AWS: "The message must be sent from a
 * verified email address or domain. If you attempt to send email using a non-verified address or
 * domain, the operation results in an "Email address not verified" error."
 * (<a href="https://docs.aws.amazon.com/ses/latest/APIReference/API_SendEmail.html">SendEmail</a>).
 * An address counts as verified when the address itself, its domain or a parent domain is a verified
 * identity. The check covers every "From", "Source", "Sender" and "Return-Path" address
 * (<a href="https://docs.aws.amazon.com/ses/latest/dg/troubleshoot-error-messages.html">Email
 * sending errors</a>) the send uses, except the ones a sending-authorization ARN puts under another
 * account's identity.
 */
@QuarkusTest
class SesSendVerifiedSenderIntegrationTest {

    private static final String AUTH_V1 =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String AUTH_V2 =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String UNVERIFIED = "never-verified@unverified-sender.floci.test";
    private static final String VERIFIED = "verified@verified-sender.floci.test";
    private static final String NOT_VERIFIED_MESSAGE =
            "Email address is not verified. The following identities failed the check in region US-EAST-1: ";
    private static final String TEMPLATE = "verified-sender-check";

    // Sending authorization: the owner account verifies OWNER_ADDRESS, and the delegate account
    // sends from it.
    private static final String OWNER_ACCOUNT = "111111111111";
    private static final String DELEGATE_ACCOUNT = "222222222222";
    private static final String OWNER_ADDRESS = "user@owner.floci.test";
    private static final String OWNER_IDENTITY_ARN =
            "arn:aws:ses:us-east-1:" + OWNER_ACCOUNT + ":identity/" + OWNER_ADDRESS;
    private static final String DELEGATE_IDENTITY_ARN =
            "arn:aws:ses:us-east-1:" + DELEGATE_ACCOUNT + ":identity/" + OWNER_ADDRESS;
    private static final String DELEGATE_AUTH_V1 =
            "AWS4-HMAC-SHA256 Credential=" + DELEGATE_ACCOUNT + "/20260101/us-east-1/email/aws4_request";
    private static final String DELEGATE_AUTH_V2 =
            "AWS4-HMAC-SHA256 Credential=" + DELEGATE_ACCOUNT + "/20260101/us-east-1/ses/aws4_request";

    private static RequestSpecification query(String action) {
        return query(AUTH_V1, action);
    }

    private static RequestSpecification query(String authorization, String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .formParam("Action", action);
    }

    private static RequestSpecification sendEmail(String authorization, String source) {
        return query(authorization, "SendEmail")
                .formParam("Source", source)
                .formParam("Destination.ToAddresses.member.1", "to@example.com")
                .formParam("Message.Subject.Data", "s")
                .formParam("Message.Body.Text.Data", "b");
    }

    private static RequestSpecification sendRawEmail(String authorization, String rawMessage) {
        return query(authorization, "SendRawEmail")
                .formParam("Destinations.member.1", "to@example.com")
                .formParam("RawMessage.Data", rawMessage);
    }

    private static RequestSpecification sendTemplatedEmail(String authorization, String source) {
        return query(authorization, "SendTemplatedEmail")
                .formParam("Source", source)
                .formParam("Destination.ToAddresses.member.1", "to@example.com")
                .formParam("Template", TEMPLATE)
                .formParam("TemplateData", "{}");
    }

    private static String simpleBody(String from) {
        return """
                {
                    "FromEmailAddress": "%s",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
                }
                """.formatted(from);
    }

    private static RequestSpecification v2Request(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body);
    }

    private static void verifyEmailIdentity(String address) {
        query("VerifyEmailIdentity").formParam("EmailAddress", address)
            .when().post("/").then().statusCode(200);
    }

    private static void verifyOwnerAddress() {
        query("AWS4-HMAC-SHA256 Credential=" + OWNER_ACCOUNT + "/20260101/us-east-1/email/aws4_request",
                "VerifyEmailIdentity")
            .formParam("EmailAddress", OWNER_ADDRESS)
            .when().post("/").then().statusCode(200);
    }

    private static void createTemplate(String authorization) {
        query(authorization, "CreateTemplate")
            .formParam("Template.TemplateName", TEMPLATE)
            .formParam("Template.SubjectPart", "s")
            .formParam("Template.TextPart", "b")
        .when()
            .post("/");
    }

    /** A base64 raw message from {@code from}, with each of {@code headers} ("Name: value") above it. */
    private static String rawMessage(String from, String... headers) {
        StringBuilder raw = new StringBuilder();
        for (String header : headers) {
            raw.append(header).append("\r\n");
        }
        raw.append("From: ").append(from).append("\r\nTo: to@example.com\r\nSubject: s\r\n\r\nbody");
        return Base64.getEncoder().encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void assertSent(RequestSpecification request, String action) {
        request
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(action + "Response." + action + "Result.MessageId", notNullValue());
    }

    private static void assertSentV2(RequestSpecification request) {
        request
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    private static void assertMessageRejectedV2(RequestSpecification request, String unverified) {
        request
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"))
            .body("message", equalTo(NOT_VERIFIED_MESSAGE + unverified));
    }

    private static void assertMessageRejected(RequestSpecification request, String unverified) {
        request
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MessageRejected"))
            .body("ErrorResponse.Error.Message", equalTo(NOT_VERIFIED_MESSAGE + unverified));
    }

    private static void assertNothingCapturedFrom(String source) {
        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages.Source", everyItem(not(equalTo(source))));
    }

    @Test
    void v1SendEmail_unverifiedSource_returnsMessageRejected() {
        assertMessageRejected(sendEmail(AUTH_V1, UNVERIFIED), UNVERIFIED);

        assertNothingCapturedFrom(UNVERIFIED);
    }

    @Test
    void v1SendEmail_displayNameSourceWithVerifiedAddress_succeeds() {
        verifyEmailIdentity(VERIFIED);

        assertSent(sendEmail(AUTH_V1, "Verified Sender <" + VERIFIED + ">"), "SendEmail");
    }

    @Test
    void v1SendRawEmail_unverifiedSource_returnsMessageRejected() {
        assertMessageRejected(sendRawEmail(AUTH_V1, rawMessage(UNVERIFIED)).formParam("Source", UNVERIFIED),
                UNVERIFIED);

        assertNothingCapturedFrom(UNVERIFIED);
    }

    @Test
    void v1SendRawEmail_withoutSource_unverifiedMimeFrom_returnsMessageRejected() {
        assertMessageRejected(sendRawEmail(AUTH_V1, rawMessage(UNVERIFIED)), UNVERIFIED);
    }

    @Test
    void v1SendTemplatedEmail_unverifiedSource_returnsMessageRejected() {
        createTemplate(AUTH_V1);

        assertMessageRejected(sendTemplatedEmail(AUTH_V1, UNVERIFIED), UNVERIFIED);
    }

    @Test
    void v2SendEmail_simple_unverifiedFrom_returnsMessageRejected() {
        assertMessageRejectedV2(v2Request(AUTH_V2, simpleBody(UNVERIFIED)), UNVERIFIED);

        assertNothingCapturedFrom(UNVERIFIED);
    }

    @Test
    void v2SendEmail_raw_unverifiedMimeFrom_returnsMessageRejected() {
        assertMessageRejectedV2(v2Request(AUTH_V2, """
                {
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(rawMessage(UNVERIFIED))), UNVERIFIED);
    }

    @Test
    void v2SendEmail_verifiedEmailIdentity_succeeds() {
        v2Request(AUTH_V2, "{\"EmailIdentity\": \"v2-" + VERIFIED + "\"}")
        .when()
            .post("/v2/email/identities");

        assertSentV2(v2Request(AUTH_V2, simpleBody("v2-" + VERIFIED)));
    }

    @Test
    void v2SendEmail_addressUnderVerifiedDomain_succeeds() {
        String domain = "verified-domain.floci.test";
        SesDomainIdentityTestHelper.createVerified(domain, AUTH_V2);

        assertSentV2(v2Request(AUTH_V2, simpleBody("anyone@" + domain)));
    }

    @Test
    void v2SendEmail_addressUnderASubdomainOfAVerifiedDomain_succeeds() {
        // "if you create and verify an identity for example.com, you don't need to create separate
        // identities for a.example.com, a.b.example.com, user@example.com, user@a.example.com".
        String domain = "verified-parent.floci.test";
        SesDomainIdentityTestHelper.createVerified(domain, AUTH_V2);

        assertSentV2(v2Request(AUTH_V2, simpleBody("anyone@a.b." + domain)));
    }

    @Test
    void v1SendEmail_addressUnderADomainThatOnlyEndsLikeAVerifiedOne_returnsMessageRejected() {
        String domain = "verified-suffix.floci.test";
        SesDomainIdentityTestHelper.createVerified(domain, AUTH_V2);
        String lookalike = "anyone@not-" + domain;

        assertMessageRejected(sendEmail(AUTH_V1, lookalike), lookalike);
    }

    @Test
    void v1SendRawEmail_withoutSource_unverifiedSecondMimeFrom_returnsMessageRejected() {
        String first = "first-" + VERIFIED;
        String second = "second@unverified-sender.floci.test";
        verifyEmailIdentity(first);

        assertMessageRejected(sendRawEmail(AUTH_V1, rawMessage(first + ", " + second)), second);

        assertNothingCapturedFrom(first);
    }

    @Test
    void v1SendRawEmail_withoutSource_everyMimeFromVerified_succeeds() {
        String first = "both-first-" + VERIFIED;
        String second = "both-second-" + VERIFIED;
        verifyEmailIdentity(first);
        verifyEmailIdentity(second);

        assertSent(sendRawEmail(AUTH_V1, rawMessage(first + ", " + second)), "SendRawEmail");
    }

    @Test
    void v1SendEmail_addressUnderPendingDomain_returnsMessageRejected() {
        // A domain identity stays Pending until its DKIM records are published, and a pending
        // domain does not verify the addresses under it.
        String domain = "pending-domain.floci.test";
        query("VerifyDomainIdentity").formParam("Domain", domain)
            .when().post("/").then().statusCode(200);

        assertMessageRejected(sendEmail(AUTH_V1, "anyone@" + domain), "anyone@" + domain);
    }

    @Test
    void v1SendEmail_unverifiedReturnPath_returnsMessageRejected() {
        String source = "rp-source-" + VERIFIED;
        String returnPath = "bounces@unverified-sender.floci.test";
        verifyEmailIdentity(source);

        assertMessageRejected(sendEmail(AUTH_V1, source).formParam("ReturnPath", returnPath), returnPath);

        assertNothingCapturedFrom(source);
    }

    @Test
    void v1SendTemplatedEmail_unverifiedReturnPath_returnsMessageRejected() {
        String source = "rp-templated-" + VERIFIED;
        String returnPath = "templated-bounces@unverified-sender.floci.test";
        verifyEmailIdentity(source);
        createTemplate(AUTH_V1);

        assertMessageRejected(sendTemplatedEmail(AUTH_V1, source).formParam("ReturnPath", returnPath), returnPath);

        assertNothingCapturedFrom(source);
    }

    @Test
    void v2SendEmail_simple_unverifiedFeedbackForwardingAddress_returnsMessageRejected() {
        String source = "rp-v2-" + VERIFIED;
        String feedback = "v2-bounces@unverified-sender.floci.test";
        verifyEmailIdentity(source);

        assertMessageRejectedV2(v2Request(AUTH_V2, """
                {
                    "FromEmailAddress": "%s",
                    "FeedbackForwardingEmailAddress": "%s",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
                }
                """.formatted(source, feedback)), feedback);

        assertNothingCapturedFrom(source);
    }

    @Test
    void v2SendEmail_raw_unverifiedFeedbackForwardingAddress_returnsMessageRejected() {
        String source = "rp-v2-raw-" + VERIFIED;
        String feedback = "v2-raw-bounces@unverified-sender.floci.test";
        verifyEmailIdentity(source);

        assertMessageRejectedV2(v2Request(AUTH_V2, """
                {
                    "FeedbackForwardingEmailAddress": "%s",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(feedback, rawMessage(source))), feedback);

        assertNothingCapturedFrom(source);
    }

    @Test
    void v1SendRawEmail_verifiedSourceWithUnverifiedMimeFrom_returnsMessageRejected() {
        String source = "raw-source-" + VERIFIED;
        verifyEmailIdentity(source);

        assertMessageRejected(sendRawEmail(AUTH_V1, rawMessage(UNVERIFIED)).formParam("Source", source), UNVERIFIED);

        assertNothingCapturedFrom(source);
    }

    @Test
    void v2SendEmail_raw_verifiedFromEmailAddress_mimeFromIsNotChecked() {
        // A v2 raw send reads the From header only in place of a missing FromEmailAddress.
        String source = "raw-v2-source-" + VERIFIED;
        verifyEmailIdentity(source);

        assertSentV2(v2Request(AUTH_V2, """
                {
                    "FromEmailAddress": "%s",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(source, rawMessage(UNVERIFIED))));
    }

    @Test
    void v1SendRawEmail_unverifiedSenderHeader_returnsMessageRejected() {
        String from = "raw-sender-from-" + VERIFIED;
        String sender = "assistant@unverified-sender.floci.test";
        verifyEmailIdentity(from);

        assertMessageRejected(sendRawEmail(AUTH_V1, rawMessage(from, "Sender: " + sender)), sender);

        assertNothingCapturedFrom(from);
    }

    @Test
    void v1SendRawEmail_unverifiedReturnPathHeader_returnsMessageRejected() {
        String from = "raw-return-path-from-" + VERIFIED;
        String returnPath = "raw-bounces@unverified-sender.floci.test";
        verifyEmailIdentity(from);

        assertMessageRejected(sendRawEmail(AUTH_V1, rawMessage(from, "Return-Path: <" + returnPath + ">")),
                returnPath);

        assertNothingCapturedFrom(from);
    }

    @Test
    void v1SendRawEmail_sourceFromSenderAndReturnPathAllVerified_succeeds() {
        String source = "all-source-" + VERIFIED;
        String from = "all-from-" + VERIFIED;
        String sender = "all-sender-" + VERIFIED;
        String returnPath = "all-return-path-" + VERIFIED;
        for (String address : List.of(source, from, sender, returnPath)) {
            verifyEmailIdentity(address);
        }

        assertSent(sendRawEmail(AUTH_V1, rawMessage(from, "Sender: " + sender, "Return-Path: <" + returnPath + ">"))
                .formParam("Source", source), "SendRawEmail");
    }

    @Test
    void v1SendEmail_twoUnverifiedAddresses_oneErrorNamesBoth() {
        String returnPath = "both-bounces@unverified-sender.floci.test";

        assertMessageRejected(sendEmail(AUTH_V1, UNVERIFIED).formParam("ReturnPath", returnPath),
                UNVERIFIED + ", " + returnPath);
    }

    @Test
    void v1SendEmail_sourceArnOfAnotherAccount_succeeds() {
        verifyOwnerAddress();

        assertSent(sendEmail(DELEGATE_AUTH_V1, OWNER_ADDRESS).formParam("SourceArn", OWNER_IDENTITY_ARN),
                "SendEmail");
    }

    @Test
    void v1SendEmail_addressVerifiedOnlyInAnotherAccount_withoutSourceArn_returnsMessageRejected() {
        verifyOwnerAddress();

        assertMessageRejected(sendEmail(DELEGATE_AUTH_V1, OWNER_ADDRESS), OWNER_ADDRESS);
    }

    @Test
    void v1SendEmail_sourceArnOfTheCallersOwnAccount_returnsMessageRejected() {
        verifyOwnerAddress();

        assertMessageRejected(sendEmail(DELEGATE_AUTH_V1, OWNER_ADDRESS)
            .formParam("SourceArn", DELEGATE_IDENTITY_ARN), OWNER_ADDRESS);
    }

    @Test
    void v1SendEmail_sourceArnThatNamesNoSesIdentity_returnsMessageRejected() {
        verifyOwnerAddress();

        assertMessageRejected(sendEmail(DELEGATE_AUTH_V1, OWNER_ADDRESS)
            .formParam("SourceArn", "arn:aws:ses:us-east-1:" + OWNER_ACCOUNT + ":configuration-set/owner"),
                OWNER_ADDRESS);
        assertMessageRejected(sendEmail(DELEGATE_AUTH_V1, OWNER_ADDRESS)
            .formParam("SourceArn", "arn:aws:sns:us-east-1:" + OWNER_ACCOUNT + ":identity/" + OWNER_ADDRESS),
                OWNER_ADDRESS);
    }

    @Test
    void v1SendEmail_returnPathArnOfAnotherAccount_coversOnlyTheReturnPath() {
        verifyOwnerAddress();

        assertMessageRejected(sendEmail(DELEGATE_AUTH_V1, UNVERIFIED)
            .formParam("ReturnPath", OWNER_ADDRESS)
            .formParam("ReturnPathArn", OWNER_IDENTITY_ARN), UNVERIFIED);
    }

    @Test
    void v1SendTemplatedEmail_sourceAndReturnPathArnsOfAnotherAccount_succeeds() {
        verifyOwnerAddress();
        createTemplate(DELEGATE_AUTH_V1);

        assertSent(sendTemplatedEmail(DELEGATE_AUTH_V1, OWNER_ADDRESS)
            .formParam("SourceArn", OWNER_IDENTITY_ARN)
            .formParam("ReturnPath", OWNER_ADDRESS)
            .formParam("ReturnPathArn", OWNER_IDENTITY_ARN), "SendTemplatedEmail");
    }

    @Test
    void v1SendRawEmail_sourceArnHeaderOfAnotherAccount_coversTheFromAndReturnPath() {
        verifyOwnerAddress();

        assertSent(sendRawEmail(DELEGATE_AUTH_V1, rawMessage(OWNER_ADDRESS,
                "X-SES-SOURCE-ARN: " + OWNER_IDENTITY_ARN, "Return-Path: <" + OWNER_ADDRESS + ">")), "SendRawEmail");
    }

    @Test
    void v1SendRawEmail_fromAndReturnPathArnHeadersOfAnotherAccount_coverTheirAddresses() {
        verifyOwnerAddress();

        assertSent(sendRawEmail(DELEGATE_AUTH_V1, rawMessage(OWNER_ADDRESS,
                "X-SES-FROM-ARN: " + OWNER_IDENTITY_ARN, "X-SES-RETURN-PATH-ARN: " + OWNER_IDENTITY_ARN,
                "Return-Path: <" + OWNER_ADDRESS + ">")), "SendRawEmail");
    }

    @Test
    void v1SendRawEmail_sourceArnParameterWinsOverItsHeader() {
        verifyOwnerAddress();

        assertSent(sendRawEmail(DELEGATE_AUTH_V1,
                rawMessage(OWNER_ADDRESS, "X-SES-SOURCE-ARN: " + DELEGATE_IDENTITY_ARN))
            .formParam("Source", OWNER_ADDRESS)
            .formParam("SourceArn", OWNER_IDENTITY_ARN), "SendRawEmail");
    }

    @Test
    void v1SendRawEmail_fromArnParameterWinsOverItsHeader() {
        verifyOwnerAddress();

        assertSent(sendRawEmail(DELEGATE_AUTH_V1, rawMessage(OWNER_ADDRESS, "X-SES-FROM-ARN: " + DELEGATE_IDENTITY_ARN))
            .formParam("FromArn", OWNER_IDENTITY_ARN), "SendRawEmail");
    }

    @Test
    void v1SendRawEmail_returnPathArnParameterCoversTheReturnPathHeader() {
        verifyOwnerAddress();

        assertSent(sendRawEmail(DELEGATE_AUTH_V1, rawMessage(OWNER_ADDRESS, "Return-Path: <" + OWNER_ADDRESS + ">"))
            .formParam("FromArn", OWNER_IDENTITY_ARN)
            .formParam("ReturnPathArn", OWNER_IDENTITY_ARN), "SendRawEmail");
    }

    @Test
    void v2SendEmail_fromAndFeedbackForwardingIdentityArnsOfAnotherAccount_succeeds() {
        verifyOwnerAddress();

        assertSentV2(v2Request(DELEGATE_AUTH_V2, """
                {
                    "FromEmailAddress": "%s",
                    "FromEmailAddressIdentityArn": "%s",
                    "FeedbackForwardingEmailAddress": "%s",
                    "FeedbackForwardingEmailAddressIdentityArn": "%s",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
                }
                """.formatted(OWNER_ADDRESS, OWNER_IDENTITY_ARN, OWNER_ADDRESS, OWNER_IDENTITY_ARN)));
    }

    @Test
    void v2SendEmail_raw_fromEmailAddressIdentityArnOverridesTheArnHeaders() {
        // "For Raw emails, the FromEmailAddressIdentityArn value overrides the X-SES-SOURCE-ARN and
        // X-SES-FROM-ARN headers specified in raw email message content."
        verifyOwnerAddress();

        assertSentV2(v2Request(DELEGATE_AUTH_V2, """
                {
                    "FromEmailAddressIdentityArn": "%s",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(OWNER_IDENTITY_ARN, rawMessage(OWNER_ADDRESS,
                        "X-SES-SOURCE-ARN: " + DELEGATE_IDENTITY_ARN, "X-SES-FROM-ARN: " + DELEGATE_IDENTITY_ARN))));
    }

    private static RequestSpecification sendBulkTemplatedEmail(String authorization, String source) {
        return query(authorization, "SendBulkTemplatedEmail")
                .formParam("Source", source)
                .formParam("Template", TEMPLATE)
                .formParam("DefaultTemplateData", "{}")
                .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "to@example.com");
    }

    private static String bulkBody(String from, String extraMembers, String entries) {
        return """
                {
                    "FromEmailAddress": "%s",
                    %s
                    "DefaultContent": {"Template": {"TemplateContent": {"Subject": "s", "Text": "b"}}},
                    "BulkEmailEntries": %s
                }
                """.formatted(from, extraMembers, entries);
    }

    private static String bulkBody(String from) {
        return bulkBody(from, "", "[{\"Destination\": {\"ToAddresses\": [\"to@example.com\"]}}]");
    }

    private static void assertBulkMessageRejectedV2(RequestSpecification request, String unverified) {
        request
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"))
            .body("message", equalTo(NOT_VERIFIED_MESSAGE + unverified));
    }

    @Test
    void v2SendBulkEmail_unverifiedFrom_rejectsTheWholeCall() {
        assertBulkMessageRejectedV2(v2Request(AUTH_V2, bulkBody(UNVERIFIED)), UNVERIFIED);

        assertNothingCapturedFrom(UNVERIFIED);
    }

    @Test
    void v2SendBulkEmail_unverifiedFeedbackForwardingAddress_rejectsTheWholeCall() {
        String source = "bulk-rp-" + VERIFIED;
        String feedback = "bulk-bounces@unverified-sender.floci.test";
        verifyEmailIdentity(source);

        assertBulkMessageRejectedV2(v2Request(AUTH_V2, bulkBody(source,
                "\"FeedbackForwardingEmailAddress\": \"" + feedback + "\",",
                "[{\"Destination\": {\"ToAddresses\": [\"to@example.com\"]}}]")), feedback);

        assertNothingCapturedFrom(source);
    }

    @Test
    void v2SendBulkEmail_noEntryHasARecipient_senderIsNotChecked() {
        // AWS fails each entry on its missing destination and never reaches the sender check.
        v2Request(AUTH_V2, bulkBody(UNVERIFIED, "", "[{\"Destination\": {}}]"))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("INVALID_PARAMETER"));
    }

    @Test
    void v2SendBulkEmail_verifiedFrom_succeeds() {
        String source = "bulk-" + VERIFIED;
        verifyEmailIdentity(source);

        v2Request(AUTH_V2, bulkBody(source))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("SUCCESS"));
    }

    @Test
    void v2SendBulkEmail_fromAndFeedbackForwardingIdentityArnsOfAnotherAccount_succeeds() {
        verifyOwnerAddress();

        v2Request(DELEGATE_AUTH_V2, bulkBody(OWNER_ADDRESS,
                "\"FromEmailAddressIdentityArn\": \"" + OWNER_IDENTITY_ARN + "\","
                        + "\"FeedbackForwardingEmailAddress\": \"" + OWNER_ADDRESS + "\","
                        + "\"FeedbackForwardingEmailAddressIdentityArn\": \"" + OWNER_IDENTITY_ARN + "\",",
                "[{\"Destination\": {\"ToAddresses\": [\"to@example.com\"]}}]"))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("SUCCESS"));
    }

    @Test
    void v1SendBulkTemplatedEmail_unverifiedSource_returnsMessageRejected() {
        createTemplate(AUTH_V1);

        assertMessageRejected(sendBulkTemplatedEmail(AUTH_V1, UNVERIFIED), UNVERIFIED);

        assertNothingCapturedFrom(UNVERIFIED);
    }

    @Test
    void v1SendBulkTemplatedEmail_sourceAndReturnPathArnsOfAnotherAccount_succeeds() {
        verifyOwnerAddress();
        createTemplate(DELEGATE_AUTH_V1);

        sendBulkTemplatedEmail(DELEGATE_AUTH_V1, OWNER_ADDRESS)
            .formParam("SourceArn", OWNER_IDENTITY_ARN)
            .formParam("ReturnPath", OWNER_ADDRESS)
            .formParam("ReturnPathArn", OWNER_IDENTITY_ARN)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member[0].Status",
                    equalTo("Success"));
    }
}
