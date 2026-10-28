package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The declared constraints the managed-policy family and {@code GetAccountAuthorizationDetails} are
 * now read against, from the model rather than from a hand-written check. What they change is the
 * status: a value that breaks a declared constraint used to reach the store and answer
 * {@code NoSuchEntity}, or on the second of those to be answered with a 200, where AWS's validation
 * layer runs ahead of the operation and answers {@code ValidationError}, which is the ordering
 * #6402 settled for this package.
 *
 * <p>{@code AttachRolePolicy}'s {@code RoleName} is the parity case rather than a change:
 * {@code IamService.validateRoleName} already answered it in this form, from the same bounds and
 * the same pattern, and the message is unchanged by the model taking it over.
 */
@QuarkusTest
class IamRequestConstraintsIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261025/us-east-1/iam/aws4_request";

    /** 20 characters, the shortest value `arnType` accepts, and no policy that exists. */
    private static final String SHORTEST_ARN = "arn:aws:iam::1:p/abc";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    /** `arnType` is 20 to 2048, so a shorter ARN is the 400 rather than the lookup's 404. */
    @Test
    void anArnUnderItsMinimumIsRefusedAheadOfTheLookup() {
        iam("GetPolicy")
            .formParam("PolicyArn", "arn:aws:iam::1:p/ab")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value 'arn:aws:iam::1:p/ab' at"
                    + " 'policyArn' failed to satisfy constraint: Member must have length greater"
                    + " than or equal to 20"))
            .body(not(containsString("NoSuchEntity")));

        // The same ARN one character longer clears the constraint and reaches the lookup.
        iam("GetPolicy")
            .formParam("PolicyArn", SHORTEST_ARN)
        .when().post("/").then().statusCode(404)
            .body(CODE, equalTo("NoSuchEntity"));
    }

    /** A required member the request leaves out is the validation layer's answer, not the store's. */
    @Test
    void aRequiredMemberLeftOutIsReportedAsNull() {
        iam("DeletePolicy").when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value null at 'policyArn' failed"
                    + " to satisfy constraint: Member must not be null"));
    }

    /** `policyVersionIdType` publishes a pattern and no length, so a bare number breaks it. */
    @Test
    void aVersionIdOutsideItsPatternIsRefused() {
        iam("GetPolicyVersion")
            .formParam("PolicyArn", SHORTEST_ARN)
            .formParam("VersionId", "1")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value '1' at 'versionId' failed to"
                    + " satisfy constraint: Member must satisfy regular expression pattern:"
                    + " v[1-9][0-9]*(\\.[A-Za-z0-9-]*)?"));

        iam("DeletePolicyVersion")
            .formParam("PolicyArn", SHORTEST_ARN)
            .formParam("VersionId", "v1")
        .when().post("/").then().statusCode(404)
            .body(CODE, equalTo("NoSuchEntity"));
    }

    /**
     * A document sent empty is a member that is present, so what it breaks are its own
     * constraints: the minimum length of 1 and a pattern that asks for a character, reported as
     * two violations the way #6402 reports an id that breaks both of its.
     */
    @Test
    void anEmptyPolicyDocumentBreaksItsLengthAndItsPattern() {
        iam("CreatePolicyVersion")
            .formParam("PolicyArn", SHORTEST_ARN)
            .formParam("PolicyDocument", "")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, containsString("2 validation errors detected: Value '' at"
                    + " 'policyDocument' failed to satisfy constraint: Member must have length"
                    + " greater than or equal to 1"))
            .body(MESSAGE, containsString("Value '' at 'policyDocument' failed to satisfy"
                    + " constraint: Member must satisfy regular expression pattern:"));
    }

    /**
     * Both broken members are reported in one message, in the order the model lists them rather
     * than the order the request sent them. The second half is what makes that an assertion: the
     * request puts `PolicyArn` first and the answer still leads with `userName`, which the model
     * lists first. Whether AWS orders them that way is GEN-2's capture to confirm; what is pinned
     * here is that the order comes from the model and not from the wire.
     */
    @Test
    void twoBrokenMembersAreReportedTogetherInTheModelsOrder() {
        String expected = "2 validation errors detected: Value '" + "a".repeat(65) + "' at"
                + " 'userName' failed to satisfy constraint: Member must have length less than"
                + " or equal to 64; Value 'short' at 'policyArn' failed to satisfy constraint:"
                + " Member must have length greater than or equal to 20";

        iam("AttachUserPolicy")
            .formParam("UserName", "a".repeat(65))
            .formParam("PolicyArn", "short")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo(expected));

        iam("AttachUserPolicy")
            .formParam("PolicyArn", "short")
            .formParam("UserName", "a".repeat(65))
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo(expected));
    }

    /**
     * The parity case: the message for a role name outside its pattern is the one
     * {@code validateRoleName} already answered, from the same bounds, so what changed is where it
     * is read rather than what it says.
     */
    @Test
    void aRoleNameOutsideItsPatternKeepsTheMessageTheServiceAlreadyAnswered() {
        iam("AttachRolePolicy")
            .formParam("RoleName", "two words")
            .formParam("PolicyArn", SHORTEST_ARN)
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value 'two words' at 'roleName'"
                    + " failed to satisfy constraint: Member must satisfy regular expression"
                    + " pattern: [\\w+=,.@-]+"));
    }

    /**
     * An empty name is the one input where reading the model changes what this service answers
     * rather than only when it answers. {@code validateRoleName} stops at the first constraint an
     * empty value breaks, so it reports the minimum length alone; the model reports the pattern it
     * also breaks, which is how #6402 reports an id breaking two of its own, and how
     * {@code GetMFADevice} already answers an empty {@code UserName}. The operations still on the
     * hand-written check keep the single violation until a later wave takes them over, and
     * {@code CreateRole} below is what holds that boundary still.
     *
     * <p>The order of the two is this package's, matching {@code GetMFADevice}, rather than a
     * claim about AWS: the captured messages in the Identity Center guide show length before
     * pattern in one and pattern before length in another, so an exact order is stricter than any
     * source states.
     */
    @Test
    void anEmptyRoleNameBreaksTwoConstraintsWhereTheHandWrittenCheckStopsAtOne() {
        iam("AttachRolePolicy")
            .formParam("RoleName", "")
            .formParam("PolicyArn", SHORTEST_ARN)
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("2 validation errors detected: Value '' at 'roleName' failed to"
                    + " satisfy constraint: Member must have length greater than or equal to 1;"
                    + " Value '' at 'roleName' failed to satisfy constraint: Member must satisfy"
                    + " regular expression pattern: [\\w+=,.@-]+"));

        // The same empty name on an operation this wave leaves alone, reported the older way.
        iam("CreateRole")
            .formParam("RoleName", "")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value '' at 'roleName' failed to"
                    + " satisfy constraint: Member must have length greater than or equal to 1"));
    }

    /**
     * A name of one space is a character long, so it clears the minimum and breaks only the
     * pattern. It is worth pinning because this package elsewhere reads a blank required member as
     * absent, and the model does not: the request carries it, so it is a value.
     */
    @Test
    void aBlankRoleNameBreaksOnlyItsPatternRatherThanReadingAsAbsent() {
        iam("DetachRolePolicy")
            .formParam("RoleName", " ")
            .formParam("PolicyArn", SHORTEST_ARN)
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value ' ' at 'roleName' failed to"
                    + " satisfy constraint: Member must satisfy regular expression pattern:"
                    + " [\\w+=,.@-]+"));
    }

    /** The other end of `arnType`: 2048 characters are accepted and 2049 are not. */
    @Test
    void anArnAtItsMaximumIsAcceptedAndOneCharacterLongerIsNot() {
        iam("GetPolicy")
            .formParam("PolicyArn", "arn:aws:iam::1:p/" + "a".repeat(2031))
        .when().post("/").then().statusCode(404)
            .body(CODE, equalTo("NoSuchEntity"));

        iam("GetPolicy")
            .formParam("PolicyArn", "arn:aws:iam::1:p/" + "a".repeat(2032))
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, containsString("at 'policyArn' failed to satisfy constraint: Member must"
                    + " have length less than or equal to 2048"))
            .body(not(containsString("NoSuchEntity")));
    }

    /**
     * A boolean is declared and not checked: the model names no constraint for one, so a value
     * that is neither true nor false reaches the operation, which reads anything but "true" as
     * false. Pinned rather than fixed, since what AWS answers for one is unrecorded here.
     */
    @Test
    void aBooleanOutsideTrueAndFalseIsLeftToTheOperation() {
        iam("CreatePolicyVersion")
            .formParam("PolicyArn", SHORTEST_ARN)
            .formParam("PolicyDocument", "{}")
            .formParam("SetAsDefault", "maybe")
        .when().post("/").then().statusCode(404)
            .body(CODE, equalTo("NoSuchEntity"));
    }

    /**
     * {@code GetAccountAuthorizationDetails} is the first operation read through the list walk, and
     * none of its three members was read at all before: a {@code Filter} outside the model's enum,
     * a {@code MaxItems} outside its range and a {@code Marker} breaking its length all reached the
     * operation and were answered with a 200.
     */
    @Test
    void aFilterMemberOutsideItsEnumIsRefusedWhereItUsedToBeIgnored() {
        iam("GetAccountAuthorizationDetails")
            .formParam("Filter.member.1", "Users")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value 'Users' at"
                    + " 'filter.1.member' failed to satisfy constraint: Member must satisfy enum"
                    + " value set: [User, Role, Group, LocalManagedPolicy, AWSManagedPolicy]"));

        iam("GetAccountAuthorizationDetails")
            .formParam("Filter.member.1", "User")
        .when().post("/").then().statusCode(200)
            .body(containsString("GetAccountAuthorizationDetailsResult"));
    }

    /**
     * Each member of a list is named under the index the request spelled rather than the position
     * it sits at, and the two are reported together in one message.
     */
    @Test
    void twoBrokenListMembersAreReportedTogetherUnderTheIndexesTheRequestSpelled() {
        iam("GetAccountAuthorizationDetails")
            .formParam("Filter.member.2", "Users")
            .formParam("Filter.member.9", "Policies")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, containsString("2 validation errors detected: Value 'Users' at"
                    + " 'filter.2.member'"))
            .body(MESSAGE, containsString("Value 'Policies' at 'filter.9.member'"));
    }

    /** {@code maxItemsType} is 1 to 1000, which this operation reads nothing of today. */
    @Test
    void aMaxItemsOutsideItsRangeIsRefused() {
        iam("GetAccountAuthorizationDetails")
            .formParam("MaxItems", "1001")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value '1001' at 'maxItems' failed"
                    + " to satisfy constraint: Member must have value less than or equal to 1000"));

        iam("GetAccountAuthorizationDetails")
            .formParam("MaxItems", "1000")
        .when().post("/").then().statusCode(200);

        // A number too large for a long is a number all the same, and 1000 is still the bound.
        iam("GetAccountAuthorizationDetails")
            .formParam("MaxItems", "9223372036854775808")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value '9223372036854775808' at"
                    + " 'maxItems' failed to satisfy constraint: Member must have value less than"
                    + " or equal to 1000"));
    }

    /** A marker sent empty breaks its minimum and its pattern, and both are reported. */
    @Test
    void anEmptyMarkerBreaksItsLengthAndItsPattern() {
        iam("GetAccountAuthorizationDetails")
            .formParam("Marker", "")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, containsString("2 validation errors detected: Value '' at 'marker'"
                    + " failed to satisfy constraint: Member must have length greater than or"
                    + " equal to 1"))
            .body(MESSAGE, containsString("Member must satisfy regular expression pattern:"));
    }
}
