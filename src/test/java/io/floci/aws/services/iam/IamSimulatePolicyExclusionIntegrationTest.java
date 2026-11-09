package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code SimulatePrincipalPolicy}'s {@code PolicyExclusionList}, the member that asks "what the
 * simulation result would be if a policy were removed, without changing which policies are actually
 * attached to the principal". Each test leaves the principal as it found it, which is the half of
 * that sentence a decision cannot show.
 *
 * <p>An entry identifies policies by type, by ARN or by an inline policy's name and attachment, and
 * the reference states both answers it gives: a syntactically invalid identifier fails the request
 * with {@code InvalidInput}, and a valid one matching no attached policy is ignored. The
 * constraints ahead of the operation already hold each member of an entry to its own shape, which
 * two of these pin, since that is where an unknown type and a wildcard in the attachment name are
 * answered.
 */
@QuarkusTest
class IamSimulatePolicyExclusionIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261109/us-east-1/iam/aws4_request";

    private static final String ACCOUNT = "000000000000";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    private static final String DECISION = "SimulatePrincipalPolicyResponse"
            + ".SimulatePrincipalPolicyResult.EvaluationResults.member.EvalDecision";

    private static final String ATTACHED = "ListAttachedUserPoliciesResponse"
            + ".ListAttachedUserPoliciesResult.AttachedPolicies.member.PolicyArn";

    /** The AWS managed policy whose bundled document allows {@code s3:Get*}. */
    private static final String S3_READ_ONLY = "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess";

    private static final String ENTRY = "PolicyExclusionList.member.1";
    private static final String INLINE = ENTRY + ".InlinePolicyIdentifier";

    /** Everything these tests create outlives the class otherwise: the emulator is shared. */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static String allow(String action) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"" + action + "\",\"Resource\":\"*\"}]}";
    }

    private static String userArn(String userName) {
        return "arn:aws:iam::" + ACCOUNT + ":user/" + userName;
    }

    private String createUser(String prefix) {
        String userName = unique(prefix);
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
            .when().post("/").then().statusCode(200));
        return userName;
    }

    private String createPolicy(String prefix, String action) {
        String arn = iam("CreatePolicy").formParam("PolicyName", unique(prefix))
                .formParam("PolicyDocument", allow(action))
            .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> iam("DeletePolicy").formParam("PolicyArn", arn)
            .when().post("/").then().statusCode(200));
        return arn;
    }

    private String attachPolicy(String userName, String policyArn) {
        iam("AttachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", policyArn)
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DetachUserPolicy").formParam("UserName", userName)
            .formParam("PolicyArn", policyArn).when().post("/").then().statusCode(200));
        return policyArn;
    }

    private String attachNewPolicy(String userName, String prefix, String action) {
        return attachPolicy(userName, createPolicy(prefix, action));
    }

    private String putUserPolicy(String userName, String prefix, String action) {
        String policyName = unique(prefix);
        iam("PutUserPolicy").formParam("UserName", userName).formParam("PolicyName", policyName)
                .formParam("PolicyDocument", allow(action))
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", policyName).when().post("/").then().statusCode(200));
        return policyName;
    }

    /** A group carrying one inline policy, with the user in it. */
    private String groupWithInlinePolicy(String userName, String policyName, String action) {
        String groupName = unique("exclusion-group");
        iam("CreateGroup").formParam("GroupName", groupName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteGroup").formParam("GroupName", groupName)
            .when().post("/").then().statusCode(200));
        iam("PutGroupPolicy").formParam("GroupName", groupName).formParam("PolicyName", policyName)
                .formParam("PolicyDocument", allow(action))
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteGroupPolicy").formParam("GroupName", groupName)
            .formParam("PolicyName", policyName).when().post("/").then().statusCode(200));
        iam("AddUserToGroup").formParam("GroupName", groupName).formParam("UserName", userName)
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("RemoveUserFromGroup").formParam("GroupName", groupName)
            .formParam("UserName", userName).when().post("/").then().statusCode(200));
        return groupName;
    }

    private String putBoundary(String userName, String action) {
        String boundaryArn = createPolicy("exclusion-boundary", action);
        iam("PutUserPermissionsBoundary").formParam("UserName", userName)
                .formParam("PermissionsBoundary", boundaryArn)
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUserPermissionsBoundary").formParam("UserName", userName)
            .when().post("/").then().statusCode(200));
        return boundaryArn;
    }

    private static RequestSpecification simulate(String userName, String action) {
        return iam("SimulatePrincipalPolicy")
                .formParam("PolicySourceArn", userArn(userName))
                .formParam("ActionNames.member.1", action)
                .formParam("ResourceArns.member.1", "*");
    }

    private static String decision(RequestSpecification request) {
        return request.when().post("/").then().statusCode(200)
                .extract().xmlPath().getString(DECISION);
    }

    private static ValidatableResponse refused(RequestSpecification request) {
        return request.when().post("/").then().statusCode(400);
    }

    private static List<String> attachedPolicyArns(String userName) {
        return iam("ListAttachedUserPolicies").formParam("UserName", userName)
            .when().post("/").then().statusCode(200)
                .extract().xmlPath().getList(ATTACHED, String.class);
    }

    /**
     * The decision an attached managed policy gives, and the decision it gives once the request
     * names its ARN. The listing afterwards is the other half of what the member promises: the
     * policy is still attached.
     */
    @Test
    void anArnIdentifierLeavesOutTheManagedPolicyItNames() {
        String user = createUser("exclusion-arn");
        String policyArn = attachNewPolicy(user, "exclusion-reader", "s3:GetObject");

        assertEquals("allowed", decision(simulate(user, "s3:GetObject")));
        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyArn", policyArn)));
        assertEquals(List.of(policyArn), attachedPolicyArns(user));
    }

    /**
     * A wildcard in the resource name reaches the policies it matches and no others, matched a
     * field at a time so that one written in the resource cannot cross into the account.
     */
    @Test
    void aWildcardArnLeavesOutOnlyTheMatchingPolicies() {
        String user = createUser("exclusion-glob");
        attachNewPolicy(user, "devreader", "s3:GetObject");
        attachNewPolicy(user, "prodreader", "ec2:RunInstances");

        RequestSpecification excluded = simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyArn", "arn:aws:iam::" + ACCOUNT + ":policy/devreader*");
        assertEquals("implicitDeny", decision(excluded));
        assertEquals("allowed", decision(simulate(user, "ec2:RunInstances")
                .formParam(ENTRY + ".PolicyArn", "arn:aws:iam::" + ACCOUNT + ":policy/devreader*")));
    }

    /**
     * An inline policy is named by its own name together with the type and name of the entity
     * carrying it, so the same name on another entity is a different policy: the attachment is
     * part of the identifier rather than decoration on it.
     */
    @Test
    void theInlineIdentifierNamesThePolicyWithItsAttachment() {
        String user = createUser("exclusion-inline");
        String policyName = putUserPolicy(user, "ownreader", "s3:GetObject");

        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")
                .formParam(INLINE + ".PolicyName", policyName)
                .formParam(INLINE + ".AttachmentType", "user")
                .formParam(INLINE + ".AttachmentName", user)));
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(INLINE + ".PolicyName", policyName)
                .formParam(INLINE + ".AttachmentType", "role")
                .formParam(INLINE + ".AttachmentName", user)));
    }

    /** A user is simulated with its groups' policies, so an identifier reaches a group's too. */
    @Test
    void theInlineIdentifierReachesAGroupsPolicy() {
        String user = createUser("exclusion-group-member");
        String policyName = unique("groupreader");
        String group = groupWithInlinePolicy(user, policyName, "s3:GetObject");

        assertEquals("allowed", decision(simulate(user, "s3:GetObject")));
        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")
                .formParam(INLINE + ".PolicyName", policyName)
                .formParam(INLINE + ".AttachmentType", "group")
                .formParam(INLINE + ".AttachmentName", group)));
    }

    /**
     * {@code PolicyType} is the part a policy plays rather than the kind of document it is, and
     * "all policies of the specified type are matched": the AWS managed policy goes out with
     * {@code aws-managed} and the customer managed one stays, and the other way round.
     */
    @Test
    void aPolicyTypeLeavesOutEveryPolicyPlayingThatPart() {
        String user = createUser("exclusion-type");
        attachPolicy(user, S3_READ_ONLY);
        attachNewPolicy(user, "exclusion-own", "ec2:RunInstances");

        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyType", "aws-managed")));
        assertEquals("allowed", decision(simulate(user, "ec2:RunInstances")
                .formParam(ENTRY + ".PolicyType", "aws-managed")));
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyType", "user-managed")));
        assertEquals("implicitDeny", decision(simulate(user, "ec2:RunInstances")
                .formParam(ENTRY + ".PolicyType", "user-managed")));
    }

    /** An inline policy is reached by the type the entity attaches it as, not by a managed one. */
    @Test
    void theInlineTypeLeavesOutAnEntitysOwnPolicies() {
        String user = createUser("exclusion-inline-type");
        putUserPolicy(user, "inlinereader", "s3:GetObject");

        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyType", "inline")));
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyType", "user-managed")));
    }

    /**
     * A boundary caps what the identity policies grant, so excluding it is what the member is for:
     * by the part it plays, and by the ARN that names the policy itself, which names it wherever it
     * enters the simulation.
     */
    @Test
    void thePermissionBoundaryGoesOutByItsPartOrByItsArn() {
        String user = createUser("exclusion-boundary");
        putUserPolicy(user, "boundedreader", "s3:GetObject");
        String boundaryArn = putBoundary(user, "ec2:RunInstances");

        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")));
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyType", "permission-boundary")));
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyArn", boundaryArn)));
    }

    /**
     * "Syntactically valid identifiers that don't match any attached policy are ignored", and
     * identifiers that target RCPs are ignored too. A simulation carries no SCP ceiling here, so
     * {@code scp} has nothing to match either.
     */
    @Test
    void anIdentifierThatMatchesNothingIsIgnored() {
        String user = createUser("exclusion-miss");
        attachNewPolicy(user, "exclusion-kept", "s3:GetObject");

        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyArn", "arn:aws:iam::" + ACCOUNT + ":policy/no-such")));
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyType", "rcp")));
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyType", "scp")));
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(INLINE + ".PolicyName", "no-such")
                .formParam(INLINE + ".AttachmentType", "user")
                .formParam(INLINE + ".AttachmentName", user)));
    }

    /**
     * A malformed ARN is one of the two the member names, answered in the wording this operation
     * already answers a malformed {@code PolicySourceArn} with.
     */
    @Test
    void aMalformedPolicyArnIsRefused() {
        String user = createUser("exclusion-malformed");

        refused(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyArn", "arn:aws:iam:policy/not-an-arn"))
            .body(CODE, equalTo("InvalidInput"))
            .body(MESSAGE, containsString("is not a valid ARN"));
    }

    /** The other one: a wildcard outside the resource name portion the member admits it in. */
    @Test
    void aWildcardOutsideTheResourceNameIsRefused() {
        String user = createUser("exclusion-badglob");

        refused(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyArn", "arn:aws:iam::*:policy/reader"))
            .body(CODE, equalTo("InvalidInput"))
            .body(MESSAGE, containsString("policyExclusionList.1.member.policyArn"))
            .body(MESSAGE, containsString("resource name portion"));
    }

    /** "Use at most one {@code *}", so a second one is a position the member does not admit. */
    @Test
    void aSecondWildcardIsRefused() {
        String user = createUser("exclusion-twoglobs");

        refused(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyArn", "arn:aws:iam::" + ACCOUNT + ":policy/*read*"))
            .body(CODE, equalTo("InvalidInput"))
            .body(MESSAGE, containsString("at most one"));
    }

    /**
     * The resource type sits ahead of the resource name, so a wildcard written in it is a position
     * the member does not admit, however closely it reads like the policy it would reach.
     */
    @Test
    void aWildcardInTheResourceTypeIsRefused() {
        String user = createUser("exclusion-typeglob");
        String policyArn = attachNewPolicy(user, "exclusion-reader", "s3:GetObject");

        for (String identifier : List.of(policyArn.replace(":policy/", ":poli?y/"),
                policyArn.replace(":policy/", ":polic*/"))) {
            refused(simulate(user, "s3:GetObject").formParam(ENTRY + ".PolicyArn", identifier))
                .body(CODE, equalTo("InvalidInput"))
                .body(MESSAGE, containsString("policyExclusionList.1.member.policyArn"))
                .body(MESSAGE, containsString("resource name portion"));
        }
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")));
    }

    /**
     * A field left empty is the other way an ARN is malformed, the declared length being all the
     * constraints ahead of the operation hold the member to. The region and the account are not
     * among the fields an ARN must carry, so one left empty matches no policy instead.
     */
    @Test
    void anArnLeavingOutAFieldEveryArnCarriesIsRefused() {
        String user = createUser("exclusion-emptyfield");
        attachNewPolicy(user, "exclusion-reader", "s3:GetObject");

        for (String identifier : List.of("arn:::::abcdefghijklmnop",
                "arn::iam::" + ACCOUNT + ":policy/reader",
                "arn:aws:iam::" + ACCOUNT + ":")) {
            refused(simulate(user, "s3:GetObject").formParam(ENTRY + ".PolicyArn", identifier))
                .body(CODE, equalTo("InvalidInput"))
                .body(MESSAGE, containsString("is not a valid ARN"));
        }
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyArn", "arn:aws:iam:::policy/reader")));
    }

    /** The union rule: "Specify exactly one of PolicyType, PolicyArn, or InlinePolicyIdentifier". */
    @Test
    void anEntryNamingTwoOfTheThreeIsRefused() {
        String user = createUser("exclusion-both");

        refused(simulate(user, "s3:GetObject")
                .formParam(ENTRY + ".PolicyType", "inline")
                .formParam(ENTRY + ".PolicyArn", "arn:aws:iam::" + ACCOUNT + ":policy/reader"))
            .body(CODE, equalTo("InvalidInput"))
            .body(MESSAGE, containsString("exactly one of PolicyType, PolicyArn or "
                    + "InlinePolicyIdentifier"));
    }

    /** The same rule from the other side: an entry the request numbers and leaves empty. */
    @Test
    void anEntryNamingNoneOfTheThreeIsRefused() {
        String user = createUser("exclusion-empty");

        refused(simulate(user, "s3:GetObject").formParam(ENTRY, ""))
            .body(CODE, equalTo("InvalidInput"))
            .body(MESSAGE, containsString("policyExclusionList.1.member"));
    }

    /**
     * The value set belongs to the constraints ahead of the operation, which read the entry's
     * members from the model: an unknown type never reaches the exclusion.
     */
    @Test
    void theConstraintsAnswerAnUnknownPolicyType() {
        String user = createUser("exclusion-badtype");

        refused(simulate(user, "s3:GetObject").formParam(ENTRY + ".PolicyType", "managed"))
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, containsString("policyExclusionList.1.member.policyType"))
            .body(MESSAGE, containsString("Member must satisfy enum value set: [inline, "
                    + "aws-managed, user-managed, permission-boundary, scp, rcp]"));
    }

    /**
     * {@code AttachmentName} is documented to take "at most one {@code *} ... and any number of
     * {@code ?}" and declared with a pattern that admits neither, which is a conflict in what AWS
     * publishes about one member. The declared pattern is what this emulator holds a caller to, so
     * a wildcard there is refused rather than matched: the divergence, and what would settle it,
     * are in {@code docs/services/iam.md}.
     */
    @Test
    void aWildcardInTheAttachmentNameIsRefusedByTheDeclaredPattern() {
        String user = createUser("exclusion-attachglob");

        refused(simulate(user, "s3:GetObject")
                .formParam(INLINE + ".PolicyName", "reader")
                .formParam(INLINE + ".AttachmentType", "user")
                .formParam(INLINE + ".AttachmentName", "exclusion-attach*"))
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, containsString("attachmentName"))
            .body(MESSAGE, containsString("Member must satisfy regular expression pattern"));
    }

    /** The bare spelling of a list is one the constraints read, so the exclusion reads it too. */
    @Test
    void theBareSpellingOfTheListIsReadToo() {
        String user = createUser("exclusion-bare");
        String policyArn = attachNewPolicy(user, "exclusion-bare-reader", "s3:GetObject");

        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")
                .formParam("PolicyExclusionList.1.PolicyArn", policyArn)));
    }

    private static RequestSpecification changeOwnPassword(String userName) {
        return iam("SimulatePrincipalPolicy")
                .formParam("PolicySourceArn", userArn(userName))
                .formParam("ActionNames.member.1", "iam:ChangePassword")
                .formParam("ResourceArns.member.1", userArn(userName));
    }

    /**
     * The account-level grant the password policy carries is attached to nothing, where the member
     * names the policies "actually attached to the principal", so no identifier reaches it: a
     * simulation leaving out every part a policy can play still carries the grant.
     */
    @Test
    void theAccountLevelGrantIsNotAnAttachedPolicy() {
        String user = createUser("exclusion-grant");
        iam("UpdateAccountPasswordPolicy").formParam("AllowUsersToChangePassword", "true")
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteAccountPasswordPolicy")
            .when().post("/").then().statusCode(200));

        assertEquals("allowed", decision(changeOwnPassword(user)));
        assertEquals("allowed", decision(changeOwnPassword(user)
                .formParam(ENTRY + ".PolicyType", "inline")
                .formParam("PolicyExclusionList.member.2.PolicyType", "user-managed")
                .formParam("PolicyExclusionList.member.3.PolicyType", "aws-managed")
                .formParam("PolicyExclusionList.member.4.PolicyType", "permission-boundary")));
    }
}
