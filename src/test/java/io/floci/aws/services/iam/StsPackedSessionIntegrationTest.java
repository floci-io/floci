package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * What a request asks to carry into its session token, and what AWS does when the token will not
 * fit the one limit it is held to. A session over that limit is refused with
 * {@code PackedPolicyTooLarge} rather than minted, and {@code PackedPolicySize} reports the
 * percentage of the limit the session consumes, which is what the member is defined as, rather
 * than a flat zero.
 *
 * <p>{@code docs/services/iam.md} records the measure, which is this emulator's own, and the one
 * recorded size that shows AWS's assembly running larger than the bytes going in.
 */
@QuarkusTest
class StsPackedSessionIntegrationTest {

    private static final String STS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261118/us-east-1/sts/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    /** Every field inside its own declared limit, and the set as a whole far past the packed one. */
    private static final int TAGS = 50;
    private static final int KEY_LENGTH = 124;
    private static final int VALUE_LENGTH = 256;

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261118/us-east-1/iam/aws4_request";

    private static final String TRUST_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification sts(String action) {
        return given().header("Authorization", STS_AUTH).formParam("Action", action);
    }

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    /**
     * A role of this class's own, assumable by anything, because the two cases that reach the
     * operation need one to exist and a role another class creates is not this class's to rely on.
     */
    private String roleArn() {
        String roleName = "packed-session-role-" + Long.toString(System.nanoTime(), 36);
        iam("CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteRole").formParam("RoleName", roleName)
                .when().post("/").then().statusCode(200));
        return "arn:aws:iam::000000000000:role/" + roleName;
    }

    private static String repeat(char c, int length) {
        return String.valueOf(c).repeat(length);
    }

    /** A tag set of the recorded shape: 50 tags, each key 124 characters and each value 256. */
    private static RequestSpecification withRecordedTagSet(RequestSpecification request) {
        for (int i = 1; i <= TAGS; i++) {
            String index = String.format("%03d", i);
            request = request
                    .formParam("Tags.member." + i + ".Key", index + repeat('k', KEY_LENGTH - 3))
                    .formParam("Tags.member." + i + ".Value", index + repeat('v', VALUE_LENGTH - 3));
        }
        return request;
    }

    /** The length this emulator mints a token at when the request asks for nothing longer. */
    private static final int MINTED_TOKEN_LENGTH = 200;

    /** The limit a session token is held to, which the refusal names. */
    private static final int LIMIT = 4096;

    /** What the measure reports for that set: the token it would be carried in, and the set. */
    private static int recordedSetBytes() {
        return MINTED_TOKEN_LENGTH + TAGS * (KEY_LENGTH + VALUE_LENGTH);
    }

    /**
     * The request AWS refuses: every member inside its own limit and the packed state far over.
     * Nothing is minted, and the sentence names the size and the limit.
     */
    @Test
    void anOversizedSessionIsRefusedRatherThanMinted() {
        withRecordedTagSet(sts("AssumeRole")
                .formParam("RoleArn", roleArn())
                .formParam("RoleSessionName", "packed-session"))
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("PackedPolicyTooLarge"))
                .body(MESSAGE, equalTo("Serialized token too large for session. Token size of "
                        + recordedSetBytes()
                        + " bytes exceeds the maximum allowed limit of " + LIMIT + " bytes."));
    }

    /** The same holds for the federated session, which models the same error. */
    @Test
    void anOversizedFederatedSessionIsRefusedTheSameWay() {
        withRecordedTagSet(sts("GetFederationToken").formParam("Name", "packed-federated"))
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("PackedPolicyTooLarge"));
    }

    /** And for a web identity session, whose state is its documents rather than tags. */
    @Test
    void anOversizedWebIdentitySessionIsRefusedTheSameWay() {
        RequestSpecification request = sts("AssumeRoleWithWebIdentity")
                .formParam("RoleArn", roleArn())
                .formParam("RoleSessionName", "packed-web")
                .formParam("WebIdentityToken", "a.b.c");
        for (int i = 1; i <= 3; i++) {
            request = request.formParam("PolicyArns.member." + i + ".arn",
                    "arn:aws:iam::000000000000:policy/" + repeat('p', 1900));
        }
        request.when().post("/").then().statusCode(400)
                .body(CODE, equalTo("PackedPolicyTooLarge"));
    }

    /**
     * A session whose token fits is minted and reports what the session consumes, where the member
     * used to read zero whatever the request asked to carry.
     */
    @Test
    void aSessionWithinTheLimitReportsWhatItCosts() {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::"
                + repeat('b', 1000) + "/*\"}]}";
        int percent = (MINTED_TOKEN_LENGTH + document.length()) * 100 / LIMIT;
        sts("AssumeRole").formParam("RoleArn", roleArn())
                .formParam("RoleSessionName", "packed-within")
                .formParam("Policy", document)
                .when().post("/").then().statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.PackedPolicySize",
                        equalTo(Integer.toString(percent)));
    }

    /**
     * A session carrying nothing still reports what its own token costs, the member being the
     * percentage of the limit the token consumes rather than the cost of the state alone.
     */
    @Test
    void aSessionCarryingNothingReportsWhatItsTokenCosts() {
        sts("AssumeRole").formParam("RoleArn", roleArn())
                .formParam("RoleSessionName", "packed-empty")
                .when().post("/").then().statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.PackedPolicySize",
                        equalTo(Integer.toString(MINTED_TOKEN_LENGTH * 100 / LIMIT)));
    }

    /**
     * A token asked to be as long as the limit allows consumes all of it, which is the case the
     * old reading answered with a zero: {@code MinimumSessionTokenSize} raises the token "to at
     * least this size, regardless of its actual content", and nothing is carried on top of it.
     */
    @Test
    void aTokenPaddedToTheLimitReportsAllOfIt() {
        sts("AssumeRole").formParam("RoleArn", roleArn())
                .formParam("RoleSessionName", "packed-padded")
                .formParam("MinimumSessionTokenSize", Integer.toString(LIMIT))
                .when().post("/").then().statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.PackedPolicySize", equalTo("100"));
    }

    /** State on top of a token already at the limit does not fit, and the sentence names the size. */
    @Test
    void stateOnTopOfAPaddedTokenIsRefused() {
        sts("AssumeRole").formParam("RoleArn", roleArn())
                .formParam("RoleSessionName", "packed-padded-over")
                .formParam("MinimumSessionTokenSize", Integer.toString(LIMIT))
                .formParam("Tags.member.1.Key", "team")
                .formParam("Tags.member.1.Value", "payments")
                .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("PackedPolicyTooLarge"))
                .body(MESSAGE, equalTo("Serialized token too large for session. Token size of "
                        + (LIMIT + "team".length() + "payments".length())
                        + " bytes exceeds the maximum allowed limit of " + LIMIT + " bytes."));
    }
}
