package io.floci.aws.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.IamTestPolicies.EMPTY_POLICY_DOCUMENT;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The declared constraints every operation is read against, from the model rather than from a
 * hand-written check. What they change is the
 * status: a value that breaks a declared constraint used to reach the store and answer
 * {@code NoSuchEntity}, or on the second of those to be answered with a 200, where AWS's validation
 * layer runs ahead of the operation and answers {@code ValidationError}, which is the ordering
 * #6402 settled for this package.
 *
 * <p>A member the request carries empty is a value, not a member it left out: what a blank breaks
 * is the shape declared for it, and only an absent member is reported as absent. That holds for the
 * two members this package still reads by hand as well as for the ones the walk reads.
 *
 * <p>{@code AttachRolePolicy}'s {@code RoleName} is the parity case rather than a change:
 * {@code IamService.validateRoleName} answers it from the same bounds and the same pattern, and
 * both write the message IAM writes, which names the member with no value beside it: that is
 * DRIFT-13's reading, and `docs/services/iam.md` carries it.
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
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'policyArn' failed to"
                    + " satisfy constraint: Member must have length greater than or equal to 20"))
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
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'policyArn' failed"
                    + " to satisfy constraint: Member must not be null"));
    }

    /** `policyVersionIdType` publishes a pattern and no length, so a bare number breaks it. */
    @Test
    void aVersionIdOutsideItsPatternIsRefused() {
        iam("GetPolicyVersion")
            .formParam("PolicyArn", SHORTEST_ARN)
            .formParam("VersionId", "1")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'versionId' failed to"
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
            .body(MESSAGE, containsString("2 validation errors detected: Value at"
                    + " 'policyDocument' failed to satisfy constraint: Member must have length"
                    + " greater than or equal to 1"))
            .body(MESSAGE, containsString("Value at 'policyDocument' failed to satisfy"
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
        String expected = "2 validation errors detected: Value at 'userName' failed to satisfy"
                + " constraint: Member must have length less than or equal to 64; Value at"
                + " 'policyArn' failed to satisfy constraint: Member must have length greater"
                + " than or equal to 20";

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
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'roleName' failed to"
                    + " satisfy constraint: Member must satisfy regular expression pattern:"
                    + " [\\w+=,.@-]+"));
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
            .body(MESSAGE, equalTo("2 validation errors detected: Value at 'roleName' failed to"
                    + " satisfy constraint: Member must have length greater than or equal to 1;"
                    + " Value at 'roleName' failed to satisfy constraint: Member must satisfy"
                    + " regular expression pattern: [\\w+=,.@-]+"));

        // And the same empty name on `CreateRole`, which is read from the model as well now, so
        // both constraints are reported there too where the hand-written check stopped at one.
        iam("CreateRole")
            .formParam("RoleName", "")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("2 validation errors detected: Value at 'roleName' failed to"
                    + " satisfy constraint: Member must have length greater than or equal to 1;"
                    + " Value at 'roleName' failed to satisfy constraint: Member must satisfy"
                    + " regular expression pattern: [\\w+=,.@-]+"));
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
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'roleName' failed to"
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
            .formParam("PolicyDocument", EMPTY_POLICY_DOCUMENT)
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
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'filter.1.member'"
                    + " failed to satisfy constraint: Member must satisfy enum value set:"
                    + " [User, Role, Group, LocalManagedPolicy, AWSManagedPolicy]"));

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
            .body(MESSAGE, containsString("2 validation errors detected: Value at"
                    + " 'filter.2.member'"))
            .body(MESSAGE, containsString("Value at 'filter.9.member'"));
    }

    /** {@code maxItemsType} is 1 to 1000, which this operation reads nothing of today. */
    @Test
    void aMaxItemsOutsideItsRangeIsRefused() {
        iam("GetAccountAuthorizationDetails")
            .formParam("MaxItems", "1001")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'maxItems' failed to"
                    + " satisfy constraint: Member must have value less than or equal to 1000"));

        iam("GetAccountAuthorizationDetails")
            .formParam("MaxItems", "1000")
        .when().post("/").then().statusCode(200);

        // A number too large for a long is a number all the same, and 1000 is still the bound.
        iam("GetAccountAuthorizationDetails")
            .formParam("MaxItems", "9223372036854775808")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'maxItems' failed to"
                    + " satisfy constraint: Member must have value less than or equal to 1000"));
    }

    /** A marker sent empty breaks its minimum and its pattern, and both are reported. */
    @Test
    void anEmptyMarkerBreaksItsLengthAndItsPattern() {
        iam("GetAccountAuthorizationDetails")
            .formParam("Marker", "")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, containsString("2 validation errors detected: Value at 'marker'"
                    + " failed to satisfy constraint: Member must have length greater than or"
                    + " equal to 1"))
            .body(MESSAGE, containsString("Member must satisfy regular expression pattern:"));
    }

    /**
     * A map's key is held to every rule its shape declares, answered as a set under the map's own
     * name, and its value is held to its shape under the key the entry carried. The key's own
     * length and pattern were read before this, by a hand-written check the model now answers for;
     * an empty value reached the store.
     */
    @Test
    void aMapsKeyAndValueAreEachHeldToTheirShape() {
        iam("PutAccountProperties")
            .formParam("Properties.entry.1.key", "1-not-a-key")
            .formParam("Properties.entry.1.value", "v")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'properties' failed to"
                    + " satisfy constraint: Map keys must satisfy constraint: [Member must have"
                    + " length less than or equal to 50, Member must have length greater than or"
                    + " equal to 1, Member must satisfy regular expression pattern:"
                    + " ^[A-Za-z][A-Za-z0-9/_-]*$]"));

        iam("PutAccountProperties")
            .formParam("Properties.entry.1.key", "Ok/Value")
            .formParam("Properties.entry.1.value", "")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'properties.Ok/Value'"
                    + " failed to satisfy constraint: Member must have length greater than or"
                    + " equal to 1"));

        // A map the request names nowhere is the required member reading as null.
        iam("PutAccountProperties")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'properties' failed"
                    + " to satisfy constraint: Member must not be null"));
    }

    /**
     * A map whose value is a structure holding a list is read all the way down, which is the
     * deepest request this service models: {@code AcquireRole} numbers its replacement values as
     * entries and each entry's structure carries a list of its own. An entry the request numbered
     * with a key is an entry, so what the structure under it requires is answered inside it, named
     * under that key with {@code .member} after it, which is the form AWS writes for a map of
     * structures on this protocol.
     */
    @Test
    void aMapValuesStructureAndTheListInsideItAreRead() {
        iam("AcquireRole")
            .formParam("TemplateArn", "arn:aws:iam::000000000000:policy-template/t")
            .formParam("ReplacementValues.entry.1.key", "Department")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value at"
                    + " 'replacementValues.Department.member.values' failed to satisfy constraint:"
                    + " Member must not be null"));
    }

    /**
     * A structure's field may be a list, and its members structures of their own. The name of a
     * policy parameter is 5 characters at least, which nothing read before.
     */
    @Test
    void aListInsideAStructureIsReadUnderTheNamesTheRequestGives() {
        iam("CreateDelegationRequest")
            .formParam("Description", "a delegation")
            .formParam("RequestorWorkflowId", "wf-0001")
            .formParam("NotificationChannel", "channel")
            .formParam("SessionDuration", "3600")
            .formParam("Permissions.PolicyTemplateArn", "arn:aws:iam::000000000000:policy/t")
            .formParam("Permissions.Parameters.member.1.Name", "abc")
            .formParam("Permissions.Parameters.member.1.Type", "string")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value at"
                    + " 'permissions.parameters.1.member.name' failed to satisfy constraint:"
                    + " Member must have length greater than or equal to 5"));
    }

    /**
     * {@code PolicySourceArn} is read by hand on the simulators and through the walk on
     * {@code GetContextKeysForPrincipalPolicy}. A blank is a value on either, so both hold it to
     * the 20 characters {@code arnType} declares and answer in the same words.
     */
    @Test
    void aBlankArnReadByHandBreaksItsLengthRatherThanReadingAsAbsent() {
        String refusal = "1 validation error detected: Value at 'policySourceArn' failed to satisfy"
                + " constraint: Member must have length greater than or equal to 20";

        iam("SimulatePrincipalPolicy")
            .formParam("PolicySourceArn", "")
            .formParam("ActionNames.member.1", "s3:GetObject")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo(refusal));

        iam("GetContextKeysForPrincipalPolicy")
            .formParam("PolicySourceArn", "")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo(refusal));
    }

    /**
     * The member left out keeps the sentence the operation answers it with, which is the answer
     * those two actions already carried and which no capture moves.
     */
    @Test
    void thatArnLeftOutKeepsTheOperationsOwnSentence() {
        iam("SimulatePrincipalPolicy")
            .formParam("ActionNames.member.1", "s3:GetObject")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("PolicySourceArn is required."));
    }

    /**
     * {@code serviceName} declares no length, no pattern and no value set, so nothing ahead of the
     * operation holds a blank and the operation answers it: a service name nothing supports is
     * {@code NoSuchEntity}, which is what LocalStack's AWS-validated snapshots record for a
     * malformed name as well as for an unsupported one. The name is echoed as the caller sent it,
     * which is why nothing stands between the two spaces.
     *
     * <p>The user is created so the blank name is the only thing wrong with the request. IAM reads
     * the user ahead of the service name and answers a user that is not there first, which
     * LocalStack's snapshot of a request carrying neither records, so naming a user that was never
     * created would be asking for an answer a blank name is not what earns.
     */
    @Test
    void aBlankValueTheModelConstrainsInNoWayReachesTheOperation() {
        String userName = "constraints-blank-service-" + System.nanoTime();
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        try {
            iam("CreateServiceSpecificCredential")
                .formParam("UserName", userName)
                .formParam("ServiceName", "")
            .when().post("/").then().statusCode(404)
                .body(CODE, equalTo("NoSuchEntity"))
                .body(MESSAGE, equalTo("No such service  is supported for Service Specific"
                        + " Credentials"));
        } finally {
            iam("DeleteUser").formParam("UserName", userName).when().post("/");
        }
    }

    /**
     * The one member a blank reaches the store through: this write replaces the policy a role is
     * assumed under, and a blank trust policy matches nothing, so the role it was written to could
     * no longer be assumed. The shape admits the value, a minimum of 1 beside a pattern that takes
     * whitespace, so the operation answers it with the modelled {@code MalformedPolicyDocument} and
     * the stored policy stays as it was.
     */
    @Test
    void aBlankTrustPolicyIsRefusedAndLeavesTheStoredOneStanding() {
        String roleName = "constraints-blank-trust-" + System.nanoTime();
        iam("CreateRole")
            .formParam("RoleName", roleName)
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then().statusCode(200);
        try {
            iam("UpdateAssumeRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyDocument", " ")
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("MalformedPolicyDocument"))
                .body(MESSAGE, equalTo("This policy contains invalid Json"));

            iam("GetRole")
                .formParam("RoleName", roleName)
            .when().post("/").then().statusCode(200)
                .body("GetRoleResponse.GetRoleResult.Role.AssumeRolePolicyDocument",
                        containsString("sts:AssumeRole"));

            // A document the member's shape admits and this operation writes is still written.
            iam("UpdateAssumeRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyDocument", TRUST_POLICY)
            .when().post("/").then().statusCode(200);
        } finally {
            iam("DeleteRole").formParam("RoleName", roleName).when().post("/");
        }
    }

    /** The same member left out is still a member the request never carried. */
    @Test
    void thatValueLeftOutIsStillReportedAsAbsent() {
        iam("CreateServiceSpecificCredential")
            .formParam("UserName", "constraints-absent-service")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value at 'serviceName' failed to"
                    + " satisfy constraint: Member must not be null"));
    }
}
