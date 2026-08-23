package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the SES V2 account provisioning details: {@code POST /v2/email/account/details}
 * (PutAccountDetails) and the {@code GET /v2/email/account} (GetAccount) {@code Details} round-trip.
 * Shapes, defaults, and validation are verified against real AWS: Details is per-region and
 * present-when-set (GetAccount omits it until configured), MailType/WebsiteURL are required and their
 * null violations are reported together, and MailType/ContactLanguage are enum-constrained. Floci has
 * no sandbox, so ReviewDetails is reported as GRANTED.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesAccountDetailsV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/ca-central-1/ses/aws4_request";

    @Test
    @Order(0)
    void getAccount_detailsAbsentUntilConfigured() {
        // A region where PutAccountDetails was never called omits the Details key entirely. Assert the
        // key is absent (not merely null): JsonPath can't tell a missing key from an explicit null, and
        // omission is the AWS-compatible behavior under test.
        given().header("Authorization", AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("$", not(hasKey("Details")));
    }

    @Test
    @Order(1)
    void putDetails_roundTripsThroughGetAccount() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"MailType":"TRANSACTIONAL","WebsiteURL":"https://example.com",
                     "ContactLanguage":"EN","UseCaseDescription":"transactional mail",
                     "AdditionalContactEmailAddresses":["ops@example.com"]}
                    """)
        .when().post("/v2/email/account/details").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("Details.MailType", equalTo("TRANSACTIONAL"))
                .body("Details.WebsiteURL", equalTo("https://example.com"))
                .body("Details.ContactLanguage", equalTo("EN"))
                .body("Details.UseCaseDescription", equalTo("transactional mail"))
                .body("Details.AdditionalContactEmailAddresses[0]", equalTo("ops@example.com"))
                .body("Details.ReviewDetails.Status", equalTo("GRANTED"))
                .body("Details.ReviewDetails.CaseId", notNullValue());
    }

    @Test
    @Order(2)
    void putDetails_missingBothRequired_reportsAggregatedError() {
        // AWS reports both null violations at once (mailType before websiteURL).
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("2 validation errors detected: Value at 'mailType' failed to "
                        + "satisfy constraint: Member must not be null; Value at 'websiteURL' failed to "
                        + "satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(3)
    void putDetails_missingWebsiteUrl_reportsSingleError() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'websiteURL' failed to "
                        + "satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(4)
    void putDetails_invalidMailTypeEnum_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"SPAM\",\"WebsiteURL\":\"https://example.com\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'mailType' failed to "
                        + "satisfy constraint: Member must satisfy enum value set: [MARKETING, TRANSACTIONAL]"));
    }

    @Test
    @Order(5)
    void putDetails_invalidContactLanguageEnum_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"https://example.com\","
                        + "\"ContactLanguage\":\"FR\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'contactLanguage' failed "
                        + "to satisfy constraint: Member must satisfy enum value set: [EN, JA]"));
    }

    @Test
    @Order(6)
    void putDetails_blankWebsiteUrl_returnsLengthError() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'websiteURL' failed to "
                        + "satisfy constraint: Member must have length greater than or equal to 1"));
    }

    @Test
    @Order(7)
    void putDetails_nonUrlWebsite_returnsFormatError() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"not a url\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("Url contains invalid format"));
    }

    @Test
    @Order(8)
    void putDetails_nonStringTypedMember_returnsBadRequest() {
        // A present-but-wrong-type typed member is rejected rather than coerced (asText would turn 123
        // into "123"), matching the identity/configuration-set string members.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":123,\"WebsiteURL\":\"https://example.com\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(9)
    void putDetails_nonArrayAdditionalContacts_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"https://example.com\","
                        + "\"AdditionalContactEmailAddresses\":\"ops@example.com\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(10)
    void putDetails_nonBooleanProductionAccess_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"https://example.com\","
                        + "\"ProductionAccessEnabled\":\"yes\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(11)
    void putDetails_websiteUrlTooLong_returnsLengthError() {
        String longUrl = "https://example.com/" + "a".repeat(1001);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"" + longUrl + "\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'websiteURL' failed to "
                        + "satisfy constraint: Member must have length less than or equal to 1000"));
    }

    @Test
    @Order(12)
    void putDetails_useCaseDescriptionTooLong_returnsLengthError() {
        String longDesc = "x".repeat(5001);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"https://example.com\","
                        + "\"UseCaseDescription\":\"" + longDesc + "\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'useCaseDescription' failed "
                        + "to satisfy constraint: Member must have length less than or equal to 5000"));
    }

    @Test
    @Order(13)
    void putDetails_tooManyContacts_returnsLengthError() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"https://example.com\","
                        + "\"AdditionalContactEmailAddresses\":[\"a1@example.com\",\"a2@example.com\","
                        + "\"a3@example.com\",\"a4@example.com\",\"a5@example.com\"]}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'additionalContactEmailAddresses' failed to satisfy constraint: Member must "
                        + "have length less than or equal to 4"));
    }

    @Test
    @Order(14)
    void putDetails_emptyContactsList_returnsLengthError() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"https://example.com\","
                        + "\"AdditionalContactEmailAddresses\":[]}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'additionalContactEmailAddresses' failed to satisfy constraint: Member must "
                        + "have length greater than or equal to 1"));
    }

    @Test
    @Order(15)
    void putDetails_contactElementTooShort_returnsElementConstraint() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"https://example.com\","
                        + "\"AdditionalContactEmailAddresses\":[\"a@b\"]}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'additionalContactEmailAddresses' failed to satisfy constraint: Member must "
                        + "satisfy constraint: [Member must have length less than or equal to 254, "
                        + "Member must have length greater than or equal to 6, Member must satisfy "
                        + "regular expression pattern: ^(.+)@(.+)$]"));
    }

    @Test
    @Order(16)
    void putDetails_multipleLengthViolations_areAggregated() {
        String longUrl = "https://example.com/" + "a".repeat(1001);
        String longDesc = "x".repeat(5001);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"MailType\":\"TRANSACTIONAL\",\"WebsiteURL\":\"" + longUrl + "\","
                        + "\"UseCaseDescription\":\"" + longDesc + "\"}")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("message", equalTo("2 validation errors detected: Value at 'websiteURL' failed to "
                        + "satisfy constraint: Member must have length less than or equal to 1000; "
                        + "Value at 'useCaseDescription' failed to satisfy constraint: Member must have "
                        + "length less than or equal to 5000"));
    }

    @Test
    @Order(17)
    void putDetails_malformedJson_returnsSerializationException() {
        // AWS reports a malformed JSON body as a SerializationException, not BadRequestException.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{ not json")
        .when().post("/v2/email/account/details").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(18)
    void putDetails_productionAccessEnabled_topLevelStaysTrueAndNotInDetails() {
        // ProductionAccessEnabled is a GetAccount top-level member, not an AccountDetails field. Floci
        // has no sandbox and runs no production-access review, so the top-level flag stays true
        // regardless of the value sent to PutAccountDetails, and it never appears inside Details. Use a
        // dedicated region so this is independent of the other ordered cases.
        String euAuth = "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-west-2/ses/aws4_request";
        given().contentType("application/json").header("Authorization", euAuth)
                .body("""
                    {"MailType":"MARKETING","WebsiteURL":"https://example.org",
                     "ProductionAccessEnabled":false}
                    """)
        .when().post("/v2/email/account/details").then().statusCode(200);

        given().header("Authorization", euAuth)
        .when().get("/v2/email/account").then().statusCode(200)
                // The top-level flag stays true even though false was sent.
                .body("ProductionAccessEnabled", equalTo(true))
                // Details is present (configured) but carries no ProductionAccessEnabled member.
                .body("Details.MailType", equalTo("MARKETING"))
                .body("Details", not(hasKey("ProductionAccessEnabled")));
    }
}
