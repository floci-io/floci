package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.floci.aws.services.iam.IamWordingRequests.DOCUMENT;
import static io.floci.aws.services.iam.IamWordingRequests.expectNotFound;
import static io.floci.aws.services.iam.IamWordingRequests.iam;
import static io.floci.aws.services.iam.IamWordingRequests.unique;

/**
 * The sentences IAM answers for a managed policy ARN it cannot resolve, one per operation:
 * {@code was not found.} where the ARN names nothing, {@code does not exist or is not
 * attachable.} where it names nothing the call could attach, and {@code Scope ARN: <arn> does not
 * exist or is not attachable.} for a permissions boundary. The recorded operations are pinned
 * here with the ones that follow them, and so is the clause the detach paths used to add.
 *
 * <p>{@code docs/services/iam.md} carries the recordings and the readings.
 */
@QuarkusTest
class IamPolicyNotFoundWordingIntegrationTest {

    /** Well formed, this account's, and naming no policy. */
    private static final String ABSENT = "arn:aws:iam::000000000000:policy/not-found-wording";

    private static final String PLAIN = "Policy " + ABSENT + " was not found.";

    private static final String ATTACHABLE =
            "Policy " + ABSENT + " does not exist or is not attachable.";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final IamWordingRequests requests = new IamWordingRequests(cleanup);

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
        expectNotFound(iam("AttachUserPolicy").formParam("UserName", requests.createUser())
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
        String arn = requests.createPolicy();
        expectNotFound(iam("DetachUserPolicy").formParam("UserName", requests.createUser())
                .formParam("PolicyArn", arn), "Policy " + arn + " was not found.");
        expectNotFound(iam("DetachGroupPolicy").formParam("GroupName", requests.createGroup())
                .formParam("PolicyArn", arn), "Policy " + arn + " was not found.");
        expectNotFound(iam("DetachRolePolicy").formParam("RoleName", requests.createRole())
                .formParam("PolicyArn", arn), "Policy " + arn + " was not found.");
    }

    /** Detaching an ARN that names nothing at all answers the same sentence. */
    @Test
    void detachingAnAbsentPolicyAnswersTheSameSentence() {
        expectNotFound(iam("DetachUserPolicy").formParam("UserName", requests.createUser())
                .formParam("PolicyArn", ABSENT), PLAIN);
    }

    /** A permissions boundary that names no policy answers the Scope ARN sentence, as recorded. */
    @Test
    void aPermissionsBoundaryThatNamesNoPolicyAnswersTheScopeSentence() {
        String scope = "Scope ARN: " + ABSENT + " does not exist or is not attachable.";
        expectNotFound(iam("CreateUser").formParam("UserName", unique("boundary-user"))
                .formParam("PermissionsBoundary", ABSENT), scope);
        expectNotFound(iam("PutUserPermissionsBoundary").formParam("UserName", requests.createUser())
                .formParam("PermissionsBoundary", ABSENT), scope);
        expectNotFound(iam("PutRolePermissionsBoundary").formParam("RoleName", requests.createRole())
                .formParam("PermissionsBoundary", ABSENT), scope);
    }

    /** A version id that names nothing on a policy that exists is named inside the attach sentence. */
    @Test
    void aVersionThatNamesNothingOnAPolicyThatExistsAnswersTheAttachSentence() {
        String arn = requests.createPolicy();
        for (String action : new String[] {"GetPolicyVersion", "DeletePolicyVersion", "SetDefaultPolicyVersion"}) {
            expectNotFound(iam(action).formParam("PolicyArn", arn).formParam("VersionId", "v7"),
                    "Policy " + arn + " version v7 does not exist or is not attachable.");
        }
    }

    /** The control: a policy that exists still reads, attaches and detaches. */
    @Test
    void aPolicyThatExistsIsUntouched() {
        String arn = requests.createPolicy();
        String userName = requests.createUser();
        iam("GetPolicy").formParam("PolicyArn", arn).when().post("/").then().statusCode(200);
        iam("AttachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", arn)
                .when().post("/").then().statusCode(200);
        iam("DetachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", arn)
                .when().post("/").then().statusCode(200);
        iam("ListEntitiesForPolicy").formParam("PolicyArn", arn)
                .when().post("/").then().statusCode(200);
    }
}
