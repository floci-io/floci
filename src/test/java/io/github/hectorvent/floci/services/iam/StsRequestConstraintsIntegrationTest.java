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
 * <p>Every operation that models a member is read that way now. The three read first are below,
 * then the seven that answered an absent member from a check of their own, which named the
 * parameter as the request writes it and read a blank value as an absent one. Both of those move
 * onto the model's form here.
 *
 * <p>Three operations were read first. {@code GetAccessKeyInfo} is the parity case: its own check
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
    /** One of the five managed policies that may scope a privileged session. */
    private static final String ROOT_TASK_POLICY =
            "arn:aws:iam::aws:policy/root-task/IAMAuditRootUserCredentials";

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
                    + " satisfy constraint: Member must have length equal to 6"));

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
                    + " equal to 5"))
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

    /**
     * An absent required member is reported under the name a constraint message spells, every one
     * the request leaves out in the order the model lists them, where the check this replaces
     * answered the first it found and named it as the request writes it. The acronym keeps the
     * model's own spelling with a lower first letter, {@code sAMLAssertion}, which is what Smithy
     * renders and what the recorded refusals carry.
     */
    @Test
    void theRequiredMembersAreNamedTheWayTheModelSpellsThem() {
        sts("AssumeRole")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("2 validation errors detected: Value null at 'roleArn' failed"
                    + " to satisfy constraint: Member must not be null; Value null at"
                    + " 'roleSessionName' failed to satisfy constraint: Member must not be null"));

        sts("AssumeRoleWithSAML")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("3 validation errors detected: Value null at 'roleArn' failed"
                    + " to satisfy constraint: Member must not be null; Value null at"
                    + " 'principalArn' failed to satisfy constraint: Member must not be null;"
                    + " Value null at 'sAMLAssertion' failed to satisfy constraint: Member must"
                    + " not be null"));
    }

    /**
     * A member sent empty is a member the request carries, so it breaks its own minimum length
     * where the check this replaces called it absent. The pattern is not broken as well: a session
     * name's own pattern matches an empty value, and only the length refuses it.
     */
    @Test
    void aBlankMemberBreaksItsLengthWhereItUsedToReadAsAbsent() {
        sts("AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::000000000000:role/Reader")
            .formParam("RoleSessionName", "")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value '' at 'roleSessionName'"
                    + " failed to satisfy constraint: Member must have length greater than or"
                    + " equal to 2"));
    }

    /**
     * {@code AssumeRoot}'s own length check on {@code TargetPrincipal} said what the model says, so
     * it comes off, and the duration and the token size come with it. The structure member arrives
     * as {@code TaskPolicyArn.arn}, so a request naming none of its fields is the member reading as
     * null. The global endpoint is still refused ahead of all of it.
     */
    @Test
    void theRootSessionsBoundsComeFromTheModel() {
        sts("AssumeRoot")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("2 validation errors detected: Value null at 'targetPrincipal'"
                    + " failed to satisfy constraint: Member must not be null; Value null at"
                    + " 'taskPolicyArn' failed to satisfy constraint: Member must not be null"));

        sts("AssumeRoot")
            .formParam("TargetPrincipal", "00000000000")
            .formParam("TaskPolicyArn.arn", ROOT_TASK_POLICY)
            .formParam("DurationSeconds", "901")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("2 validation errors detected: Value '00000000000' at"
                    + " 'targetPrincipal' failed to satisfy constraint: Member must have length"
                    + " greater than or equal to 12; Value '901' at 'durationSeconds' failed to"
                    + " satisfy constraint: Member must have value less than or equal to 900"));
    }

    /** The bounds the other three operations gained, each from its own shape. */
    @Test
    void theFederationNameAndTheEncodedMessageAreHeldToTheirShapes() {
        sts("GetFederationToken")
            .formParam("Name", "a")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value 'a' at 'name' failed to"
                    + " satisfy constraint: Member must have length greater than or equal to 2"));

        sts("GetFederationToken")
            .formParam("Name", "bad name")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value 'bad name' at 'name' failed"
                    + " to satisfy constraint: Member must satisfy regular expression pattern:"
                    + " [\\w+=,.@-]*"));

        sts("DecodeAuthorizationMessage")
            .formParam("EncodedMessage", "")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value '' at 'encodedMessage'"
                    + " failed to satisfy constraint: Member must have length greater than or"
                    + " equal to 1"));
    }

    /**
     * {@code TradeInToken} is required and carries no length at all, so a blank one is a value the
     * request sent: it reaches the lookup and is answered as a token that cannot be redeemed, where
     * the check this replaces called it absent. That is Floci's reading, nothing published saying
     * what AWS answers an empty trade-in token, and the docs record it.
     */
    @Test
    void aBlankTradeInTokenIsAValueAndReachesTheLookup() {
        sts("GetDelegatedAccessToken")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value null at 'tradeInToken'"
                    + " failed to satisfy constraint: Member must not be null"));

        sts("GetDelegatedAccessToken")
            .formParam("TradeInToken", "")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ExpiredTradeInTokenException"));
    }

    /**
     * A tag arrives as a member of a list of structures, so its fields are named under the index
     * the request spelled, and a key that is empty breaks both the length and the pattern its shape
     * declares rather than the first of them.
     */
    @Test
    void aSessionTagBreaksItsOwnShapeUnderTheIndexTheRequestSpelled() {
        sts("AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::000000000000:role/Reader")
            .formParam("RoleSessionName", "session")
            .formParam("Tags.member.1.Key", "")
            .formParam("Tags.member.1.Value", "v")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("2 validation errors detected: Value '' at 'tags.1.member.key'"
                    + " failed to satisfy constraint: Member must have length greater than or"
                    + " equal to 1; Value '' at 'tags.1.member.key' failed to satisfy constraint:"
                    + " Member must satisfy regular expression pattern:"
                    + " [\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]+"));
    }
}
