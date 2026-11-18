package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Locale;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * A {@code PolicyArn} that parses as no ARN is refused on its shape rather than looked up, in the
 * two sentences AWS words it with: the attach side names the value, everything else reports
 * nothing it could parse. The cases here are the recorded ones, the order against the member's
 * declared length, and the controls that keep the refusal from reaching a value that is an ARN.
 *
 * <p>{@code docs/services/iam.md} carries the recordings and the one they supersede.
 */
@QuarkusTest
class IamMalformedPolicyArnIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261118/us-east-1/iam/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";
    private static final String TYPE = "ErrorResponse.Error.Type";

    /** The check's own value, long enough to clear the member's declared minimum of 20. */
    private static final String NOT_AN_ARN = "not-a-valid-policy-arn";

    /** What AWS answers wherever it reports nothing it could parse, the value slot left empty. */
    private static final String UNPARSEABLE = "Invalid ARN:  Could not be parsed!";

    /** The prefix AWS puts on a constraint violation, which the declared check already carries. */
    private static final String ONE_VIOLATION = "1 validation error detected: ";

    /** An AWS-managed policy every account carries, for the controls. */
    private static final String READ_ONLY = "arn:aws:iam::aws:policy/ReadOnlyAccess";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /** The trust policy a role needs to exist at all, which is the only extra any of these take. */
    private static final String TRUST_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    /**
     * A principal of {@code kind}, registered for removal. All three take their name under
     * {@code <kind>Name} and are created and deleted by {@code Create<kind>} and
     * {@code Delete<kind>}, so only the extras differ.
     */
    private String createPrincipal(String kind, String... extras) {
        String name = unique("arn-shape-" + kind.toLowerCase(Locale.ROOT));
        RequestSpecification create = iam("Create" + kind).formParam(kind + "Name", name);
        for (int i = 0; i + 1 < extras.length; i += 2) {
            create = create.formParam(extras[i], extras[i + 1]);
        }
        create.when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("Delete" + kind).formParam(kind + "Name", name)
                .when().post("/").then().statusCode(200));
        return name;
    }

    /** The one kind that carries an extra, named so the cases stay one line each. */
    private String createRole() {
        return createPrincipal("Role", "AssumeRolePolicyDocument", TRUST_POLICY);
    }

    private static void expectValidationError(RequestSpecification request, String message) {
        request.when().post("/").then().statusCode(400)
                .body(CODE, equalTo("ValidationError"))
                .body(TYPE, equalTo("Sender"))
                .body(MESSAGE, equalTo(message));
    }

    /**
     * The attach side, recorded on all three operations: the sentence carries the value exactly as
     * the request sent it.
     */
    @Test
    void attachingAMalformedPolicyArnNamesTheValue() {
        String message = "ARN " + NOT_AN_ARN + " is not valid.";
        expectValidationError(iam("AttachUserPolicy")
                .formParam("UserName", createPrincipal("User")).formParam("PolicyArn", NOT_AN_ARN), message);
        expectValidationError(iam("AttachGroupPolicy")
                .formParam("GroupName", createPrincipal("Group")).formParam("PolicyArn", NOT_AN_ARN), message);
        expectValidationError(iam("AttachRolePolicy")
                .formParam("RoleName", createRole())
                .formParam("PolicyArn", NOT_AN_ARN), message);
    }

    /**
     * The detach side, recorded on all three: the value never appears, and the slot it would fill
     * leaves two spaces after the colon.
     */
    @Test
    void detachingAMalformedPolicyArnReportsNothingItCouldParse() {
        expectValidationError(iam("DetachUserPolicy")
                .formParam("UserName", createPrincipal("User")).formParam("PolicyArn", NOT_AN_ARN), UNPARSEABLE);
        expectValidationError(iam("DetachGroupPolicy")
                .formParam("GroupName", createPrincipal("Group")).formParam("PolicyArn", NOT_AN_ARN), UNPARSEABLE);
        expectValidationError(iam("DetachRolePolicy")
                .formParam("RoleName", createRole())
                .formParam("PolicyArn", NOT_AN_ARN), UNPARSEABLE);
    }

    /** The two reads the same sentence is recorded on. */
    @Test
    void theReadsThatCarryARecordingAnswerTheSameSentence() {
        expectValidationError(iam("GetPolicy").formParam("PolicyArn", NOT_AN_ARN), UNPARSEABLE);
        expectValidationError(iam("ListEntitiesForPolicy")
                .formParam("PolicyArn", NOT_AN_ARN), UNPARSEABLE);
    }

    /**
     * The operations no recording covers answer with them, which is the reading this emulator
     * documents: one member, one parser, one answer, with the attach side as the exception.
     */
    @Test
    void theOperationsWithNoRecordingFollowTheParseSentence() {
        expectValidationError(iam("DeletePolicy").formParam("PolicyArn", NOT_AN_ARN), UNPARSEABLE);
        expectValidationError(iam("ListPolicyVersions")
                .formParam("PolicyArn", NOT_AN_ARN), UNPARSEABLE);
        expectValidationError(iam("TagPolicy").formParam("PolicyArn", NOT_AN_ARN)
                .formParam("Tags.member.1.Key", "team")
                .formParam("Tags.member.1.Value", "iam"), UNPARSEABLE);
    }

    /**
     * The declared length still answers first, which is the order #6402 settled: a value too short
     * to be any ARN breaks the constraint the model publishes and never reaches the parse check.
     */
    @Test
    void aValueUnderTheDeclaredMinimumStillAnswersTheLength() {
        expectValidationError(iam("AttachUserPolicy")
                .formParam("UserName", createPrincipal("User")).formParam("PolicyArn", "short"),
                ONE_VIOLATION + "Value at 'policyArn' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 20");
        expectValidationError(iam("GetPolicy").formParam("PolicyArn", "short"),
                ONE_VIOLATION + "Value at 'policyArn' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 20");
    }

    /**
     * The control that keeps the check narrow: a value that parses as an ARN and names no policy
     * is the 404 each operation already answered, and one that names a real policy still attaches.
     */
    @Test
    void anArnThatNamesNoPolicyIsStillNotFoundAndARealOneStillAttaches() {
        String absent = "arn:aws:iam::000000000000:policy/arn-shape-absent";
        iam("GetPolicy").formParam("PolicyArn", absent).when().post("/").then().statusCode(404)
                .body(CODE, equalTo("NoSuchEntity"));

        String userName = createPrincipal("User");
        iam("AttachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", absent)
                .when().post("/").then().statusCode(404).body(CODE, equalTo("NoSuchEntity"));
        iam("AttachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", READ_ONLY)
                .when().post("/").then().statusCode(200);
        iam("DetachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", READ_ONLY)
                .when().post("/").then().statusCode(200);
    }

    /**
     * A principal simulation handed a wildcard where an entity ARN belongs is refused on the
     * entity rather than on the member's length, which is what the recording shows for a value of
     * one character against a declared minimum of 20.
     */
    @Test
    void aWildcardPolicySourceArnIsRefusedOnTheEntity() {
        String message = "Invalid Entity Arn: * does not clearly define entity type and name.";
        iam("SimulatePrincipalPolicy").formParam("PolicySourceArn", "*")
                .formParam("ActionNames.member.1", "s3:GetObject")
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"))
                .body(TYPE, equalTo("Sender"))
                .body(MESSAGE, equalTo(message));
        iam("GetContextKeysForPrincipalPolicy").formParam("PolicySourceArn", "*")
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"))
                .body(MESSAGE, equalTo(message));
    }

    /**
     * The other two answers for that member are unchanged: a value that clears 20 characters and
     * parses as no ARN keeps the sentence its own capture settled, and a shorter one the length.
     */
    @Test
    void theOtherPolicySourceArnAnswersAreUnchanged() {
        String unparseable = "arn:aws:iam:user/bob-and-then-some";
        iam("SimulatePrincipalPolicy").formParam("PolicySourceArn", unparseable)
                .formParam("ActionNames.member.1", "s3:GetObject")
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"))
                .body(MESSAGE, equalTo("Invalid ARN provided in the request: " + unparseable));
        expectValidationError(iam("SimulatePrincipalPolicy").formParam("PolicySourceArn", "short")
                .formParam("ActionNames.member.1", "s3:GetObject"),
                ONE_VIOLATION + "Value at 'policySourceArn' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 20");
    }
}
