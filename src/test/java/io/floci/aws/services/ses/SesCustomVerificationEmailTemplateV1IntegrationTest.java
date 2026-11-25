package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for the SES V1 Query-protocol custom-verification-email-template APIs.
 * Verifies CRUD and that templates are shared with the V2 store (a v1 template is visible via V2).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesCustomVerificationEmailTemplateV1IntegrationTest {

    private static final String FROM = "cvet-v1-sender@floci.test";
    private static final String NAME = "cvet-v1-a";
    private static final String MISSING_MESSAGE =
            "Provided custom verification email template name does not exist.";

    private static RequestSpecification create(String name, String subject) {
        return SesV1Requests.query("CreateCustomVerificationEmailTemplate")
                .formParam("TemplateName", name)
                .formParam("FromEmailAddress", FROM)
                .formParam("TemplateSubject", subject)
                .formParam("TemplateContent", "<html><body>verify</body></html>")
                .formParam("SuccessRedirectionURL", "https://example.com/ok")
                .formParam("FailureRedirectionURL", "https://example.com/fail");
    }

    @Test
    @Order(0)
    void setup_verifyFromIdentity() {
        SesV1Requests.query("VerifyEmailIdentity").formParam("EmailAddress", FROM).when().post("/").then().statusCode(200);
    }

    @Test
    @Order(10)
    void get_missing_returnsDoesNotExistOnV1() {
        // v1 returns CustomVerificationEmailTemplateDoesNotExist / 400 (verified against AWS), so the
        // v1 SDK's typed exception fires, not the v2 NotFoundException / 404. The message is a
        // fixed sentence that does not name the template, unlike the v2 one.
        SesV1Requests.query("GetCustomVerificationEmailTemplate").formParam("TemplateName", "cvet-v1-ghost")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>CustomVerificationEmailTemplateDoesNotExist</Code>"))
                .body(containsString("<Message>" + MISSING_MESSAGE + "</Message>"));
    }

    @Test
    @Order(12)
    void update_missing_returnsTheSameFixedSentenceOnV1() {
        SesV1Requests.query("UpdateCustomVerificationEmailTemplate")
                .formParam("TemplateName", "cvet-v1-ghost").formParam("FromEmailAddress", FROM)
                .formParam("TemplateSubject", "Verify your email")
                .formParam("TemplateContent", "<html><body>verify</body></html>")
                .formParam("SuccessRedirectionURL", "https://example.com/ok")
                .formParam("FailureRedirectionURL", "https://example.com/fail")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>CustomVerificationEmailTemplateDoesNotExist</Code>"))
                .body(containsString("<Message>" + MISSING_MESSAGE + "</Message>"));
    }

    @Test
    @Order(13)
    void delete_missing_succeedsOnV1() {
        // Probed: v1 answers 200 for a name the account does not hold, where v2 answers 404.
        SesV1Requests.query("DeleteCustomVerificationEmailTemplate").formParam("TemplateName", "cvet-v1-ghost")
        .when().post("/").then().statusCode(200)
                .body(containsString("DeleteCustomVerificationEmailTemplateResponse"));
    }

    @Test
    @Order(11)
    void create_checksTheUrlsBeforeTheSender_inTheV1Wording() {
        // Probed: the v1 sentences end with a full stop and carry no angle brackets, and an invalid
        // redirection URL is reported before an unverified sender, the success one first.
        String unverified = "cvet-v1-not-verified@floci.test";
        createWith(unverified, "https://example.com/ok", "https://example.com/fail")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>FromEmailAddressNotVerified</Code>"))
                .body(containsString(
                        "<Message>The from email address " + unverified + " is not verified.</Message>"));
        createWith(unverified, "https://example.com/ok", "not-a-url")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("<Message>The failure redirection URL is invalid.</Message>"));
        createWith(unverified, "not-a-url", "not-a-url")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Message>The success redirection URL is invalid.</Message>"));
    }

    private static RequestSpecification createWith(String from, String successUrl, String failureUrl) {
        return SesV1Requests.query("CreateCustomVerificationEmailTemplate")
                .formParam("TemplateName", "cvet-v1-refused")
                .formParam("FromEmailAddress", from)
                .formParam("TemplateSubject", "Verify your email")
                .formParam("TemplateContent", "<html><body>verify</body></html>")
                .formParam("SuccessRedirectionURL", successUrl)
                .formParam("FailureRedirectionURL", failureUrl);
    }

    @Test
    @Order(1)
    void create() {
        create(NAME, "Verify your email").when().post("/").then().statusCode(200)
                .body(containsString("CreateCustomVerificationEmailTemplateResponse"));
    }

    @Test
    @Order(2)
    void get() {
        SesV1Requests.query("GetCustomVerificationEmailTemplate").formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(200)
                .body(containsString("<TemplateName>" + NAME + "</TemplateName>"))
                .body(containsString("<FromEmailAddress>" + FROM + "</FromEmailAddress>"))
                .body(containsString("<TemplateSubject>Verify your email</TemplateSubject>"));
    }

    @Test
    @Order(3)
    void list() {
        SesV1Requests.query("ListCustomVerificationEmailTemplates")
        .when().post("/").then().statusCode(200)
                .body(containsString("<TemplateName>" + NAME + "</TemplateName>"));
    }

    @Test
    @Order(14)
    void update_checksOnlyTheMembersGivenBeforeTheTemplateExists() {
        // Probed: the name alone reaches the existence check, and a member that is given is
        // checked first, redirection URLs before the sender.
        SesV1Requests.query("UpdateCustomVerificationEmailTemplate").formParam("TemplateName", "cvet-v1-ghost")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>CustomVerificationEmailTemplateDoesNotExist</Code>"));
        SesV1Requests.query("UpdateCustomVerificationEmailTemplate").formParam("TemplateName", "cvet-v1-ghost")
                .formParam("TemplateSubject", "").formParam("TemplateContent", "")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>CustomVerificationEmailTemplateDoesNotExist</Code>"));
        SesV1Requests.query("UpdateCustomVerificationEmailTemplate").formParam("TemplateName", "cvet-v1-ghost")
                .formParam("FromEmailAddress", "nobody@unverified.test")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>FromEmailAddressNotVerified</Code>"))
                .body(containsString(
                        "<Message>The from email address nobody@unverified.test is not verified.</Message>"));
        SesV1Requests.query("UpdateCustomVerificationEmailTemplate").formParam("TemplateName", "cvet-v1-ghost")
                .formParam("FailureRedirectionURL", "not-a-url")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("<Message>The failure redirection URL is invalid.</Message>"));
        SesV1Requests.query("UpdateCustomVerificationEmailTemplate").formParam("TemplateName", "cvet-v1-ghost")
                .formParam("FromEmailAddress", "nobody@unverified.test")
                .formParam("SuccessRedirectionURL", "not-a-url")
                .formParam("FailureRedirectionURL", "not-a-url")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("<Message>The success redirection URL is invalid.</Message>"));
    }

    @Test
    @Order(15)
    void update_changesOnlyTheMembersGiven_andAnEmptySubjectClearsIt() {
        String name = "cvet-v1-partial";
        create(name, "Verify your email").when().post("/").then().statusCode(200);

        SesV1Requests.query("UpdateCustomVerificationEmailTemplate").formParam("TemplateName", name)
                .formParam("TemplateSubject", "Verify again")
        .when().post("/").then().statusCode(200);
        SesV1Requests.query("GetCustomVerificationEmailTemplate").formParam("TemplateName", name)
        .when().post("/").then().statusCode(200)
                .body(containsString("<TemplateSubject>Verify again</TemplateSubject>"))
                .body(containsString("<FromEmailAddress>" + FROM + "</FromEmailAddress>"))
                .body(containsString("<TemplateContent>&lt;html&gt;&lt;body&gt;verify&lt;/body&gt;&lt;/html&gt;"
                        + "</TemplateContent>"))
                .body(containsString("<SuccessRedirectionURL>https://example.com/ok</SuccessRedirectionURL>"))
                .body(containsString("<FailureRedirectionURL>https://example.com/fail</FailureRedirectionURL>"));

        // A refused update leaves the stored template as it was.
        SesV1Requests.query("UpdateCustomVerificationEmailTemplate").formParam("TemplateName", name)
                .formParam("TemplateSubject", "Never stored")
                .formParam("FromEmailAddress", "nobody@unverified.test")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>FromEmailAddressNotVerified</Code>"));
        SesV1Requests.query("GetCustomVerificationEmailTemplate").formParam("TemplateName", name)
        .when().post("/").then().statusCode(200)
                .body(containsString("<TemplateSubject>Verify again</TemplateSubject>"));

        SesV1Requests.query("UpdateCustomVerificationEmailTemplate").formParam("TemplateName", name)
                .formParam("TemplateSubject", "")
        .when().post("/").then().statusCode(200);
        SesV1Requests.query("GetCustomVerificationEmailTemplate").formParam("TemplateName", name)
        .when().post("/").then().statusCode(200)
                .body(not(containsString("<TemplateSubject>")))
                .body(containsString("<FromEmailAddress>" + FROM + "</FromEmailAddress>"));
        // The v2 view of the same template leaves the cleared member out instead of answering null.
        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/custom-verification-email-templates/" + name).then().statusCode(200)
                .body("$", not(hasKey("TemplateSubject")))
                .body("FromEmailAddress", equalTo(FROM));

        SesV1Requests.query("DeleteCustomVerificationEmailTemplate").formParam("TemplateName", name)
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(4)
    void update() {
        // create dup fails with the v1-native code (verified against AWS), not the v2 AlreadyExistsException
        create(NAME, "Updated via v1").when().post("/").then().statusCode(400)
                .body(containsString("<Code>CustomVerificationEmailTemplateAlreadyExists</Code>"));
        SesV1Requests.query("UpdateCustomVerificationEmailTemplate")
                .formParam("TemplateName", NAME).formParam("FromEmailAddress", FROM)
                .formParam("TemplateSubject", "Updated via v1")
                .formParam("TemplateContent", "<html><body>verify</body></html>")
                .formParam("SuccessRedirectionURL", "https://example.com/ok")
                .formParam("FailureRedirectionURL", "https://example.com/fail")
        .when().post("/").then().statusCode(200);

        SesV1Requests.query("GetCustomVerificationEmailTemplate").formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(200)
                .body(containsString("<TemplateSubject>Updated via v1</TemplateSubject>"));
    }

    @Test
    @Order(5)
    void delete() {
        SesV1Requests.query("DeleteCustomVerificationEmailTemplate").formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(200)
                .body(containsString("DeleteCustomVerificationEmailTemplateResponse"));
    }

    @Test
    @Order(7)
    void create_missingRequiredField_returnsInvalidParameterValueOnV1() {
        // A required-field failure must surface the v1-native InvalidParameterValue code in the
        // Query XML (not the v2-style BadRequestException), consistent with requireParam.
        SesV1Requests.query("CreateCustomVerificationEmailTemplate")
                .formParam("TemplateName", "cvet-v1-missing")
                .formParam("FromEmailAddress", FROM)
                // TemplateSubject omitted
                .formParam("TemplateContent", "<html><body>verify</body></html>")
                .formParam("SuccessRedirectionURL", "https://example.com/ok")
                .formParam("FailureRedirectionURL", "https://example.com/fail")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("TemplateSubject is required."));
    }

    @Test
    @Order(8)
    void update_missingTemplateName_returnsInvalidParameterValueOnV1() {
        // TemplateName is validated before the existence check, so omitting it yields the required-field
        // InvalidParameterValue rather than a NotFoundException for a null-named template.
        SesV1Requests.query("UpdateCustomVerificationEmailTemplate")
                // TemplateName omitted
                .formParam("FromEmailAddress", FROM)
                .formParam("TemplateSubject", "Updated via v1")
                .formParam("TemplateContent", "<html><body>verify</body></html>")
                .formParam("SuccessRedirectionURL", "https://example.com/ok")
                .formParam("FailureRedirectionURL", "https://example.com/fail")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("TemplateName is required."));
    }

    @Test
    @Order(6)
    void v1Template_isVisibleViaV2() {
        create("cvet-v1-shared", "Shared").when().post("/").then().statusCode(200);

        given().header("Authorization", SesV2Requests.AUTH)
        .when().get("/v2/email/custom-verification-email-templates/cvet-v1-shared")
        .then().statusCode(200)
                .body("TemplateSubject", equalTo("Shared"))
                .body("FromEmailAddress", equalTo(FROM));
    }

    @Test
    @Order(9)
    void cleanup_deleteSharedTemplate() {
        SesV1Requests.query("DeleteCustomVerificationEmailTemplate").formParam("TemplateName", "cvet-v1-shared")
        .when().post("/").then().statusCode(200);
    }
}
