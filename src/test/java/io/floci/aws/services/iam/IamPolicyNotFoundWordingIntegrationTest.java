package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The two sentences IAM answers for a managed policy ARN it cannot resolve, one per operation:
 * {@code was not found.} where the ARN names nothing, and {@code does not exist or is not
 * attachable.} where it names nothing the call could attach. The recorded operations are pinned
 * here with the ones that follow them, and so is the clause the detach paths used to add.
 *
 * <p>{@code docs/services/iam.md} carries the recordings and the readings.
 */
@QuarkusTest
class IamPolicyNotFoundWordingIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261118/us-east-1/iam/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    /** Well formed, this account's, and naming no policy. */
    private static final String ABSENT = "arn:aws:iam::000000000000:policy/not-found-wording";

    private static final String PLAIN = "Policy " + ABSENT + " was not found.";

    private static final String ATTACHABLE =
            "Policy " + ABSENT + " does not exist or is not attachable.";

    private static final String DOCUMENT = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static void expectNotFound(RequestSpecification request, String message) {
        request.when().post("/").then().statusCode(404)
                .body(CODE, equalTo("NoSuchEntity"))
                .body(MESSAGE, equalTo(message));
    }

    private String createUser() {
        String userName = unique("not-found-user");
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
                .when().post("/").then().statusCode(200));
        return userName;
    }

    private String createPolicy() {
        String policyName = unique("not-found-policy");
        String arn = iam("CreatePolicy").formParam("PolicyName", policyName)
                .formParam("PolicyDocument", DOCUMENT)
                .when().post("/").then().statusCode(200)
                .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> iam("DeletePolicy").formParam("PolicyArn", arn)
                .when().post("/").then().statusCode(200));
        return arn;
    }

    /** The two reads the plain sentence is recorded on, and the delete that reads with them. */
    @Test
    void thePolicyReadsAnswerTheSentenceThatNamesNothingElse() {
        expectNotFound(iam("GetPolicy").formParam("PolicyArn", ABSENT), PLAIN);
        expectNotFound(iam("TagPolicy").formParam("PolicyArn", ABSENT)
                .formParam("Tags.member.1.Key", "team")
                .formParam("Tags.member.1.Value", "iam"), PLAIN);
        expectNotFound(iam("DeletePolicy").formParam("PolicyArn", ABSENT), PLAIN);
        expectNotFound(iam("ListPolicyTags").formParam("PolicyArn", ABSENT), PLAIN);
    }

    /** The attachment family, and the version operations that read with it. */
    @Test
    void theAttachmentFamilyAnswersTheSentenceCarryingTheClause() {
        expectNotFound(iam("AttachUserPolicy").formParam("UserName", createUser())
                .formParam("PolicyArn", ABSENT), ATTACHABLE);
        expectNotFound(iam("ListEntitiesForPolicy").formParam("PolicyArn", ABSENT), ATTACHABLE);
        expectNotFound(iam("ListPolicyVersions").formParam("PolicyArn", ABSENT), ATTACHABLE);
        expectNotFound(iam("CreatePolicyVersion").formParam("PolicyArn", ABSENT)
                .formParam("PolicyDocument", DOCUMENT), ATTACHABLE);
        expectNotFound(iam("SetDefaultPolicyVersion").formParam("PolicyArn", ABSENT)
                .formParam("VersionId", "v1"), ATTACHABLE);
    }

    /** {@code GetPolicyVersion} names the version inside the same sentence. */
    @Test
    void theVersionReadNamesTheVersionInsideTheSentence() {
        expectNotFound(iam("GetPolicyVersion").formParam("PolicyArn", ABSENT)
                .formParam("VersionId", "v1"),
                "Policy " + ABSENT + " version v1 does not exist or is not attachable.");
    }

    /**
     * Detaching a policy that exists and is not attached is the plain sentence, with no clause
     * naming the user, group or role: AWS emits none, and the three paths used to.
     */
    @Test
    void detachingAPolicyThatIsNotAttachedNamesNoEntity() {
        String arn = createPolicy();
        String userName = createUser();
        expectNotFound(iam("DetachUserPolicy").formParam("UserName", userName)
                .formParam("PolicyArn", arn), "Policy " + arn + " was not found.");

        String groupName = unique("not-found-group");
        iam("CreateGroup").formParam("GroupName", groupName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteGroup").formParam("GroupName", groupName)
                .when().post("/").then().statusCode(200));
        expectNotFound(iam("DetachGroupPolicy").formParam("GroupName", groupName)
                .formParam("PolicyArn", arn), "Policy " + arn + " was not found.");

        String roleName = unique("not-found-role");
        iam("CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":"
                        + "[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},"
                        + "\"Action\":\"sts:AssumeRole\"}]}")
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteRole").formParam("RoleName", roleName)
                .when().post("/").then().statusCode(200));
        expectNotFound(iam("DetachRolePolicy").formParam("RoleName", roleName)
                .formParam("PolicyArn", arn), "Policy " + arn + " was not found.");
    }

    /** Detaching an ARN that names nothing at all answers the same sentence. */
    @Test
    void detachingAnAbsentPolicyAnswersTheSameSentence() {
        expectNotFound(iam("DetachUserPolicy").formParam("UserName", createUser())
                .formParam("PolicyArn", ABSENT), PLAIN);
    }

    /**
     * A permissions boundary that names no policy reads with the attach family, which is the
     * reading rather than a recording.
     */
    @Test
    void aPermissionsBoundaryThatNamesNoPolicyReadsWithTheAttachFamily() {
        expectNotFound(iam("CreateUser").formParam("UserName", unique("boundary-user"))
                .formParam("PermissionsBoundary", ABSENT), ATTACHABLE);
    }

    /**
     * The one case left as it was: a version id that names nothing on a policy that exists is
     * about the version rather than the ARN, and nothing records it.
     */
    @Test
    void aVersionThatNamesNothingOnAPolicyThatExistsIsUnchanged() {
        expectNotFound(iam("GetPolicyVersion").formParam("PolicyArn", createPolicy())
                .formParam("VersionId", "v7"), "Policy version v7 does not exist.");
    }

    /** The control: a policy that exists still reads, attaches and detaches. */
    @Test
    void aPolicyThatExistsIsUntouched() {
        String arn = createPolicy();
        String userName = createUser();
        iam("GetPolicy").formParam("PolicyArn", arn).when().post("/").then().statusCode(200);
        iam("AttachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", arn)
                .when().post("/").then().statusCode(200);
        iam("DetachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", arn)
                .when().post("/").then().statusCode(200);
        iam("ListEntitiesForPolicy").formParam("PolicyArn", arn)
                .when().post("/").then().statusCode(200);
    }
}
