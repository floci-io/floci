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
 * Integration tests for the SES V1 Query-protocol custom-verification-email-template APIs.
 * Verifies CRUD and that templates are shared with the V2 store (a v1 template is visible via V2).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesCustomVerificationEmailTemplateV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String V2_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String FROM = "cvet-v1-sender@floci.test";
    private static final String NAME = "cvet-v1-a";

    private static io.restassured.specification.RequestSpecification query(String action) {
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", action);
    }

    private static io.restassured.specification.RequestSpecification create(String name, String subject) {
        return query("CreateCustomVerificationEmailTemplate")
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
        query("VerifyEmailIdentity").formParam("EmailAddress", FROM).when().post("/").then().statusCode(200);
    }

    @Test
    @Order(10)
    void get_missing_returnsDoesNotExistOnV1() {
        // v1 returns CustomVerificationEmailTemplateDoesNotExist / 400 (verified against AWS), so the
        // v1 SDK's typed exception fires — not the v2 NotFoundException / 404.
        query("GetCustomVerificationEmailTemplate").formParam("TemplateName", "cvet-v1-ghost")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>CustomVerificationEmailTemplateDoesNotExist</Code>"));
    }

    @Test
    @Order(11)
    void create_unverifiedFromAddress_returnsFromEmailAddressNotVerifiedOnV1() {
        // An unverified FromEmailAddress is FromEmailAddressNotVerified / 400 on v1 (verified against
        // AWS), not the v2 NotFoundException / 404.
        query("CreateCustomVerificationEmailTemplate")
                .formParam("TemplateName", "cvet-v1-unverified-from")
                .formParam("FromEmailAddress", "cvet-v1-not-verified@floci.test")
                .formParam("TemplateSubject", "Verify your email")
                .formParam("TemplateContent", "<html><body>verify</body></html>")
                .formParam("SuccessRedirectionURL", "https://example.com/ok")
                .formParam("FailureRedirectionURL", "https://example.com/fail")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>FromEmailAddressNotVerified</Code>"));
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
        query("GetCustomVerificationEmailTemplate").formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(200)
                .body(containsString("<TemplateName>" + NAME + "</TemplateName>"))
                .body(containsString("<FromEmailAddress>" + FROM + "</FromEmailAddress>"))
                .body(containsString("<TemplateSubject>Verify your email</TemplateSubject>"));
    }

    @Test
    @Order(3)
    void list() {
        query("ListCustomVerificationEmailTemplates")
        .when().post("/").then().statusCode(200)
                .body(containsString("<TemplateName>" + NAME + "</TemplateName>"));
    }

    @Test
    @Order(4)
    void update() {
        // create dup fails with the v1-native code (verified against AWS), not the v2 AlreadyExistsException
        create(NAME, "Updated via v1").when().post("/").then().statusCode(400)
                .body(containsString("<Code>CustomVerificationEmailTemplateAlreadyExists</Code>"));
        query("UpdateCustomVerificationEmailTemplate")
                .formParam("TemplateName", NAME).formParam("FromEmailAddress", FROM)
                .formParam("TemplateSubject", "Updated via v1")
                .formParam("TemplateContent", "<html><body>verify</body></html>")
                .formParam("SuccessRedirectionURL", "https://example.com/ok")
                .formParam("FailureRedirectionURL", "https://example.com/fail")
        .when().post("/").then().statusCode(200);

        query("GetCustomVerificationEmailTemplate").formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(200)
                .body(containsString("<TemplateSubject>Updated via v1</TemplateSubject>"));
    }

    @Test
    @Order(5)
    void delete() {
        query("DeleteCustomVerificationEmailTemplate").formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(200)
                .body(containsString("DeleteCustomVerificationEmailTemplateResponse"));
    }

    @Test
    @Order(7)
    void create_missingRequiredField_returnsInvalidParameterValueOnV1() {
        // A required-field failure must surface the v1-native InvalidParameterValue code in the
        // Query XML (not the v2-style BadRequestException), consistent with requireParam.
        query("CreateCustomVerificationEmailTemplate")
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
        query("UpdateCustomVerificationEmailTemplate")
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

        given().header("Authorization", V2_AUTH)
        .when().get("/v2/email/custom-verification-email-templates/cvet-v1-shared")
        .then().statusCode(200)
                .body("TemplateSubject", equalTo("Shared"))
                .body("FromEmailAddress", equalTo(FROM));
    }

    @Test
    @Order(9)
    void cleanup_deleteSharedTemplate() {
        query("DeleteCustomVerificationEmailTemplate").formParam("TemplateName", "cvet-v1-shared")
        .when().post("/").then().statusCode(200);
    }
}
