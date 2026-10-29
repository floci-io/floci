package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The declared constraints STS reads from its model rather than from a check written by hand.
 *
 * <p>Three operations are read that way. {@code GetAccessKeyInfo} is the parity case: its own check
 * answered {@code accessKeyId} from the same bounds and the same pattern, and what changes is that
 * a value breaking both is answered for both, which is the reading #6402 settled for this package.
 * {@code GetSessionToken} brings members nothing read at all, so a {@code SerialNumber}, a
 * {@code TokenCode} or a {@code MinimumSessionTokenSize} outside its shape answers 400 where it
 * used to be accepted and ignored. {@code GetWebIdentityToken} keeps every message it already
 * answered, {@link WebIdentityTokenIntegrationTest} holding those, and what is new here is where
 * the two signing algorithms the reference names are reported once the model holds the rest.
 *
 * <p>What the model does not say is what counts as a number: a value that is no number at all
 * stays this package's own {@code InvalidParameterValue}, and a digit string is held to the range
 * whatever width it overflows.
 */
@QuarkusTest
class StsRequestConstraintsIntegrationTest {

    private static final String STS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261027/us-east-1/sts/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";
    private static final String SESSION_KEY =
            "GetSessionTokenResponse.GetSessionTokenResult.Credentials.AccessKeyId";

    private static RequestSpecification sts(String action) {
        return given().header("Authorization", STS_AUTH).formParam("Action", action);
    }

    /**
     * {@code serialNumberType} and {@code tokenCodeType} are declared and were read by nothing, so
     * a request naming either is now held to it. Both are reported together, in the order the model
     * lists them, and a well-formed pair is still accepted and still unused: the MFA check itself
     * is not built.
     */
    @Test
    void theMfaMembersAreReadWhereNothingReadThemBefore() {
        sts("GetSessionToken")
            .formParam("SerialNumber", "Serial#")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("2 validation errors detected: Value 'Serial#' at"
                    + " 'serialNumber' failed to satisfy constraint: Member must have length"
                    + " greater than or equal to 9; Value 'Serial#' at 'serialNumber' failed to"
                    + " satisfy constraint: Member must satisfy regular expression pattern:"
                    + " [\\w+=/:,.@-]*"));

        sts("GetSessionToken")
            .formParam("SerialNumber", "bad#serial")
            .formParam("TokenCode", "12345")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("2 validation errors detected: Value 'bad#serial' at"
                    + " 'serialNumber' failed to satisfy constraint: Member must satisfy regular"
                    + " expression pattern: [\\w+=/:,.@-]*; Value '12345' at 'tokenCode' failed to"
                    + " satisfy constraint: Member must have length greater than or equal to 6"));

        sts("GetSessionToken")
            .formParam("TokenCode", "abcdef")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, containsString("Value 'abcdef' at 'tokenCode' failed to satisfy"
                    + " constraint: Member must satisfy regular expression pattern: [\\d]*"));

        sts("GetSessionToken")
            .formParam("SerialNumber", "arn:aws:iam::000000000000:mfa/user")
            .formParam("TokenCode", "123456")
        .when().post("/").then().statusCode(200)
            .body(SESSION_KEY, containsString("ASIA"));
    }

    /**
     * {@code MinimumSessionTokenSize} is 0 to 4096, which {@code AssumeRoot} already answered from
     * a check of its own and this operation answered not at all. The size it asks for is still
     * ignored here, the token staying the length this emulator mints, which is a gap of its own.
     */
    @Test
    void aMinimumSessionTokenSizeOutsideItsRangeIsRefused() {
        sts("GetSessionToken")
            .formParam("MinimumSessionTokenSize", "4097")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value '4097' at"
                    + " 'minimumSessionTokenSize' failed to satisfy constraint: Member must have"
                    + " value less than or equal to 4096"));

        sts("GetSessionToken")
            .formParam("MinimumSessionTokenSize", "two thousand")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("InvalidParameterValue"))
            .body(MESSAGE, containsString("Value two thousand for parameter"
                    + " MinimumSessionTokenSize is invalid. Reason: Must be an integer."));

        sts("GetSessionToken")
            .formParam("MinimumSessionTokenSize", "1024")
        .when().post("/").then().statusCode(200);
    }

    /**
     * The range is the model's and reads the same as the check it replaces. A digit string too
     * large for an {@code int} is a number all the same, so it breaks the maximum rather than
     * answering that it is no integer, which is the reading #6625 settled for an oversized
     * {@code MaxItems}.
     */
    @Test
    void theDurationKeepsItsAnswerAndAnOversizedOneIsHeldToItsRange() {
        sts("GetSessionToken")
            .formParam("DurationSeconds", "899")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value '899' at 'durationSeconds'"
                    + " failed to satisfy constraint: Member must have value greater than or equal"
                    + " to 900"));

        sts("GetSessionToken")
            .formParam("DurationSeconds", "99999999999")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, containsString("Value '99999999999' at 'durationSeconds' failed to"
                    + " satisfy constraint: Member must have value less than or equal to 129600"));

        sts("GetSessionToken")
            .formParam("DurationSeconds", "notanumber")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("InvalidParameterValue"))
            .body(MESSAGE, containsString("Reason: Must be an integer."));
    }

    /**
     * The id's own check stopped at the first constraint it broke. The model reports every one,
     * which is what AWS answers in a single message, so an id that is both too short and outside
     * its pattern is answered for both.
     */
    @Test
    void anAccessKeyIdBreakingTwoConstraintsIsAnsweredForBoth() {
        sts("GetAccessKeyInfo")
            .formParam("AccessKeyId", "AKIA-SHORT")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("2 validation errors detected: Value 'AKIA-SHORT' at"
                    + " 'accessKeyId' failed to satisfy constraint: Member must have length greater"
                    + " than or equal to 16; Value 'AKIA-SHORT' at 'accessKeyId' failed to satisfy"
                    + " constraint: Member must satisfy regular expression pattern: [\\w]*"));
    }

    /**
     * {@code jwtAlgorithmType} publishes a length and no enum, so the two values the reference
     * names are a reading. It is reported where that member sits in the model rather than after
     * the members listed below it, and a value the declared length already refuses is not read
     * against the set as well.
     */
    @Test
    void theDocumentedSigningAlgorithmsAreReportedWhereTheMemberSits() {
        sts("GetWebIdentityToken")
            .formParam("Audience.member.1", "app")
            .formParam("SigningAlgorithm", "HS256")
            .formParam("Tags.member.1.Key", "Team")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("2 validation errors detected: Value 'HS256' at"
                    + " 'signingAlgorithm' failed to satisfy constraint: Member must satisfy enum"
                    + " value set: [RS256, ES384]; Value null at 'tags.1.member.value' failed to"
                    + " satisfy constraint: Member must not be null"));

        sts("GetWebIdentityToken")
            .formParam("Audience.member.1", "app")
            .formParam("SigningAlgorithm", "HS25")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value 'HS25' at"
                    + " 'signingAlgorithm' failed to satisfy constraint: Member must have length"
                    + " greater than or equal to 5"))
            .body(MESSAGE, not(containsString("enum value set")));
    }

    /**
     * The operation's own refusals keep running first: session credentials cannot ask for a session
     * token, and that is the answer whatever else the request carries.
     */
    @Test
    void aSessionCredentialIsStillRefusedAheadOfTheConstraints() {
        String sessionKey = sts("GetSessionToken").when().post("/").then().statusCode(200)
                .extract().path(SESSION_KEY);

        given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + sessionKey
                    + "/20261027/us-east-1/sts/aws4_request")
            .formParam("Action", "GetSessionToken")
            .formParam("SerialNumber", "Serial#")
        .when().post("/").then().statusCode(403)
            .body(CODE, equalTo("AccessDenied"))
            .body(MESSAGE, containsString("Cannot call GetSessionToken with session credentials"));
    }
}
