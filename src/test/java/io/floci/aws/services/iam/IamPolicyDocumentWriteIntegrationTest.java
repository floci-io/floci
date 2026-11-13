package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.floci.aws.testing.IamTestPolicies.IDENTITY_POLICY;
import static io.floci.aws.testing.IamTestPolicies.TRUST_POLICY;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Every write that takes an IAM policy document holds it to being one. The operations all model
 * {@code MalformedPolicyDocument} and none of them answered it, so a document that was no policy
 * was stored and only told apart later, when something tried to decide with it.
 *
 * <p>Which reading an operation applies follows what the document is. A role's trust policy is held
 * to the grammar under its own validator's wording, which tells a document that does not parse from
 * one that parses and describes no statement. An inline policy is held to the grammar under the
 * identity validator's one sentence for both, and to the values the policy language defines. A
 * managed policy is held to those and to the elements each of its statements must name.
 */
@QuarkusTest
class IamPolicyDocumentWriteIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261112/us-east-1/iam/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    private static final String MALFORMED = "MalformedPolicyDocument";

    /** Everything these tests create outlives the class otherwise: the emulator is shared. */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static void expectMalformed(RequestSpecification request, String sentence) {
        request.when().post("/").then().statusCode(400)
                .body(CODE, equalTo(MALFORMED))
                .body(MESSAGE, equalTo(sentence));
    }

    private String createRole(String prefix) {
        String roleName = unique(prefix);
        iam("CreateRole")
                .formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteRole").formParam("RoleName", roleName)
            .when().post("/").then().statusCode(200));
        return roleName;
    }

    private String createUser(String prefix) {
        String userName = unique(prefix);
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
            .when().post("/").then().statusCode(200));
        return userName;
    }

    private String createGroup(String prefix) {
        String groupName = unique(prefix);
        iam("CreateGroup").formParam("GroupName", groupName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteGroup").formParam("GroupName", groupName)
            .when().post("/").then().statusCode(200));
        return groupName;
    }

    private String createManagedPolicy(String prefix) {
        String arn = iam("CreatePolicy")
                .formParam("PolicyName", unique(prefix))
                .formParam("PolicyDocument", IDENTITY_POLICY)
            .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> iam("DeletePolicy").formParam("PolicyArn", arn)
            .when().post("/").then().statusCode(200));
        return arn;
    }

    /**
     * A role's trust policy is read by a validator of IAM's own, which words a document that does
     * not parse as a JSON object apart from one that parses and describes no statement.
     */
    @Test
    void aTrustPolicyThatIsNoPolicyIsRefusedInTheTrustValidatorsWords() {
        expectMalformed(iam("CreateRole")
                .formParam("RoleName", unique("write-trust-syntax"))
                .formParam("AssumeRolePolicyDocument", "{not json"),
                "This policy contains invalid Json");
        expectMalformed(iam("CreateRole")
                .formParam("RoleName", unique("write-trust-list"))
                .formParam("AssumeRolePolicyDocument", "[]"),
                "This policy contains invalid Json");
        expectMalformed(iam("CreateRole")
                .formParam("RoleName", unique("write-trust-empty"))
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\"}"),
                "Policy has no statements");
    }

    /**
     * The update replaces the policy a role is already assumed under, so the document it is handed
     * is read before anything is written and the role keeps the policy it had.
     */
    @Test
    void anUpdateThatIsNoPolicyLeavesTheRolesOwnTrustPolicyStanding() {
        String roleName = createRole("write-trust-update");

        expectMalformed(iam("UpdateAssumeRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyDocument", " "),
                "This policy contains invalid Json");
        expectMalformed(iam("UpdateAssumeRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\"}"),
                "Policy has no statements");

        iam("GetRole").formParam("RoleName", roleName)
            .when().post("/").then().statusCode(200)
                .body("GetRoleResponse.GetRoleResult.Role.AssumeRolePolicyDocument",
                        containsString("sts:AssumeRole"));
    }

    /**
     * A trust policy is held to the grammar and to nothing else: its statements name a principal
     * and the actions it may take rather than resources, so the elements an identity statement must
     * carry are not asked of one.
     */
    @Test
    void aTrustPolicyIsNotHeldToAnIdentityStatementsElements() {
        String roleName = unique("write-trust-accepted");
        iam("CreateRole")
                .formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteRole").formParam("RoleName", roleName)
            .when().post("/").then().statusCode(200));
    }

    /** An inline policy's validator answers one sentence for both ways of being no policy. */
    @Test
    void anInlinePolicyThatIsNoPolicyIsRefusedOnEveryHolder() {
        String userName = createUser("write-inline-user");
        String groupName = createGroup("write-inline-group");
        String roleName = createRole("write-inline-role");

        expectMalformed(iam("PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", "write-inline")
                .formParam("PolicyDocument", "{not json"),
                "Syntax errors in policy.");
        expectMalformed(iam("PutGroupPolicy")
                .formParam("GroupName", groupName)
                .formParam("PolicyName", "write-inline")
                .formParam("PolicyDocument", "{}"),
                "Syntax errors in policy.");
        expectMalformed(iam("PutRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", "write-inline")
                .formParam("PolicyDocument", "7"),
                "Syntax errors in policy.");
    }

    /**
     * A managed policy's statements are walked as well: each names the actions it is about and the
     * resources those actions apply to, and a statement that is no object at all is the syntax
     * case rather than a missing element.
     */
    @Test
    void aManagedPolicysStatementsAreHeldToTheElementsTheGrammarRequires() {
        expectMalformed(iam("CreatePolicy")
                .formParam("PolicyName", unique("write-managed-actions"))
                .formParam("PolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Resource\":\"*\"}]}"),
                "Policy statement must contain actions.");
        expectMalformed(iam("CreatePolicy")
                .formParam("PolicyName", unique("write-managed-resources"))
                .formParam("PolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"s3:GetObject\"}]}"),
                "Policy statement must contain resources.");
        expectMalformed(iam("CreatePolicy")
                .formParam("PolicyName", unique("write-managed-statement"))
                .formParam("PolicyDocument", "{\"Statement\":\"allow everything\"}"),
                "Syntax errors in policy.");
    }

    /** The same reading on a new version of a policy that already exists. */
    @Test
    void aNewVersionOfAManagedPolicyIsReadTheSameWay() {
        String arn = createManagedPolicy("write-managed-version");

        expectMalformed(iam("CreatePolicyVersion")
                .formParam("PolicyArn", arn)
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{}]}"),
                "Policy statement must contain actions.");

        iam("CreatePolicyVersion")
                .formParam("PolicyArn", arn)
                .formParam("PolicyDocument", IDENTITY_POLICY)
            .when().post("/").then().statusCode(200);
    }

    /**
     * Beside the elements a document carries, the values it declares are read against the policy
     * language: a version that does not exist, an effect the language does not define, and a
     * resource named neither by ARN nor by the wildcard. Each clause has its own sentence.
     */
    @Test
    void theValuesAnIdentityPolicyDeclaresAreReadAgainstTheLanguage() {
        String userName = createUser("write-values");

        expectMalformed(iam("PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", "write-values")
                .formParam("PolicyDocument",
                        "{\"Version\":\"2019-01-01\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}"),
                "Syntax errors in policy.");
        expectMalformed(iam("PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", "write-values")
                .formParam("PolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"allow\","
                        + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}"),
                "The policy failed legacy parsing");
        expectMalformed(iam("PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", "write-values")
                .formParam("PolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"s3:GetObject\",\"Resource\":\"my-bucket\"}]}"),
                "Resource my-bucket must be in ARN format or \"*\".");

        // The version the language also defines, and a resource named by ARN, are both taken.
        iam("PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", "write-values")
                .formParam("PolicyDocument",
                        "{\"Version\":\"2008-10-17\",\"Statement\":[{\"Effect\":\"Deny\","
                        + "\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::bucket/*\"}]}")
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "write-values").when().post("/").then().statusCode(200));
    }

    /**
     * A document carrying a statement that grants nothing is still a policy: the grammar asks for
     * the element, and an empty list of statements describes no permission rather than failing to
     * be a document.
     */
    @Test
    void aDocumentWhoseStatementListIsEmptyIsStillAPolicy() {
        String userName = createUser("write-empty-statements");

        iam("PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", "write-empty")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "write-empty").when().post("/").then().statusCode(200));
    }

    /** A member the request leaves out is still answered as absent, by the constraints. */
    @Test
    void aDocumentLeftOutIsStillReportedAsAbsent() {
        iam("CreatePolicy")
                .formParam("PolicyName", unique("write-absent"))
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("ValidationError"))
                .body(MESSAGE, containsString("'policyDocument' failed to satisfy constraint:"
                        + " Member must not be null"));
    }

    /**
     * A member taking a policy document takes "the complete, valid JSON text of an IAM policy", so
     * an object with anything after it is no policy document. Jackson reads the first value and
     * stops unless it is told not to, which is what made this worth pinning on both wordings.
     */
    @Test
    void anObjectWithTextAfterItIsNoPolicyDocument() {
        String trailing = "{\"Statement\":[]}not json";

        iam("CreateRole")
            .formParam("RoleName", "pdw-trailing-" + System.nanoTime())
            .formParam("AssumeRolePolicyDocument", trailing)
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo(MALFORMED))
            .body(MESSAGE, equalTo("This policy contains invalid Json"));

        iam("CreatePolicy")
            .formParam("PolicyName", "pdw-trailing-" + System.nanoTime())
            .formParam("PolicyDocument", trailing)
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo(MALFORMED))
            .body(MESSAGE, equalTo("Syntax errors in policy."));

        // A second JSON value after the object is the same case as text that is no JSON at all:
        // the first value parses and the reader used to stop there.
        iam("CreatePolicy")
            .formParam("PolicyName", "pdw-trailing-" + System.nanoTime())
            .formParam("PolicyDocument", "{\"Statement\":[]} []")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo(MALFORMED))
            .body(MESSAGE, equalTo("Syntax errors in policy."));
    }
}
