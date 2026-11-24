package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.floci.aws.services.ses.SesV2Requests.v2;
import static org.hamcrest.Matchers.equalTo;

/**
 * Integration tests for {@code POST /v2/email/email-address-insights/} (GetEmailAddressInsights).
 * The verdict rules are covered by {@link SesMailboxValidationTest}; these pin the wire shape, the
 * missing VDM gate and the request errors. Uses a region no other test enables VDM in.
 */
@QuarkusTest
class SesEmailAddressInsightsV2IntegrationTest {

    private static final String AUTH = SesV2Requests.authFor("ca-west-1");
    private static final String PATH = "/v2/email/email-address-insights/";

    @Test
    void answersEveryVerdictWithoutVdm() {
        v2(AUTH)
                .body("{\"EmailAddress\":\"admin@example.com\"}")
        .when().post(PATH).then().statusCode(200)
                .body("MailboxValidation.IsValid.ConfidenceVerdict", equalTo("LOW"))
                .body("MailboxValidation.Evaluations.HasValidSyntax.ConfidenceVerdict", equalTo("HIGH"))
                .body("MailboxValidation.Evaluations.HasValidDnsRecords.ConfidenceVerdict", equalTo("LOW"))
                .body("MailboxValidation.Evaluations.MailboxExists.ConfidenceVerdict", equalTo("MEDIUM"))
                .body("MailboxValidation.Evaluations.IsRoleAddress.ConfidenceVerdict", equalTo("HIGH"))
                .body("MailboxValidation.Evaluations.IsDisposable.ConfidenceVerdict", equalTo("LOW"))
                .body("MailboxValidation.Evaluations.IsRandomInput.ConfidenceVerdict", equalTo("LOW"));
    }

    @Test
    void malformedAddressAnswersOkWithLowVerdicts() {
        v2(AUTH)
                .body("{\"EmailAddress\":\"no-at-sign\"}")
        .when().post(PATH).then().statusCode(200)
                .body("MailboxValidation.IsValid.ConfidenceVerdict", equalTo("LOW"))
                .body("MailboxValidation.Evaluations.HasValidSyntax.ConfidenceVerdict", equalTo("LOW"));
    }

    @Test
    void pathWithoutTrailingSlashIsServed() {
        v2(AUTH)
                .body("{\"EmailAddress\":\"alice@gmail.com\"}")
        .when().post("/v2/email/email-address-insights").then().statusCode(200)
                .body("MailboxValidation.IsValid.ConfidenceVerdict", equalTo("HIGH"));
    }

    @Test
    void emptyAddressIsRejected() {
        v2(AUTH)
                .body("{\"EmailAddress\":\"\"}")
        .when().post(PATH).then().statusCode(400)
                .header("x-amzn-ErrorType", equalTo("BadRequestException"))
                .body("message", equalTo("Email address must not be null nor empty"));
    }

    @Test
    void missingAddressIsRejected() {
        v2(AUTH)
                .body("{}")
        .when().post(PATH).then().statusCode(400)
                .body("message", equalTo("Email address must not be null nor empty"));
    }

    @Test
    void nonStringAddressIsASerializationError() {
        v2(AUTH)
                .body("{\"EmailAddress\":1}")
        .when().post(PATH).then().statusCode(400)
                .header("x-amzn-ErrorType", equalTo("SerializationException"));
    }
}
