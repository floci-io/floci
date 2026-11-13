package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two documents {@code SimulatePrincipalPolicy} takes beside the principal's own policies.
 * {@code PolicyInputList} is "an optional list of additional policy documents to include in the
 * simulation", and a {@code PermissionsBoundaryPolicyInputList} document stands in for the stored
 * boundary: "if a permissions boundary is attached to an entity and you pass in a different
 * permissions boundary policy using this parameter, then the new permissions boundary policy is
 * used for the simulation".
 *
 * <p>Each test asks the same question twice, once with the documents and once without, since what
 * these members change is the answer rather than anything stored. Neither document is attached to
 * the principal, which is why the exclusion list reaches neither.
 */
@QuarkusTest
class IamSimulatePrincipalInputDocumentsIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261109/us-east-1/iam/aws4_request";

    private static final String ACCOUNT = "000000000000";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    private static final String DECISION = "SimulatePrincipalPolicyResponse"
            + ".SimulatePrincipalPolicyResult.EvaluationResults.member.EvalDecision";

    private static final String CUSTOM_DECISION = "SimulateCustomPolicyResponse"
            + ".SimulateCustomPolicyResult.EvaluationResults.member.EvalDecision";

    private static final String INPUT = "PolicyInputList.member.";
    private static final String BOUNDARY = "PermissionsBoundaryPolicyInputList.member.";

    /** One character past the 131072 a policy document is declared with. */
    private static final int PAST_THE_MAXIMUM = 131073;

    /** Everything these tests create outlives the class otherwise: the emulator is shared. */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static String document(String effect, String action) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"" + effect + "\","
                + "\"Action\":\"" + action + "\",\"Resource\":\"*\"}]}";
    }

    private static String allow(String action) {
        return document("Allow", action);
    }

    private static String deny(String action) {
        return document("Deny", action);
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

    private String attachNewPolicy(String userName, String prefix, String action) {
        String policyArn = createPolicy(prefix, action);
        iam("AttachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", policyArn)
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DetachUserPolicy").formParam("UserName", userName)
            .formParam("PolicyArn", policyArn).when().post("/").then().statusCode(200));
        return policyArn;
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

    private String putBoundary(String userName, String action) {
        String boundaryArn = createPolicy("input-boundary", action);
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

    /** The same simulation with every part a {@code PolicyType} can name left out. */
    private static RequestSpecification simulateWithEveryPartExcluded(String userName, String action) {
        return simulate(userName, action)
                .formParam("PolicyExclusionList.member.1.PolicyType", "inline")
                .formParam("PolicyExclusionList.member.2.PolicyType", "aws-managed")
                .formParam("PolicyExclusionList.member.3.PolicyType", "user-managed")
                .formParam("PolicyExclusionList.member.4.PolicyType", "permission-boundary");
    }

    private static String decision(RequestSpecification request) {
        return request.when().post("/").then().statusCode(200)
                .extract().xmlPath().getString(DECISION);
    }

    /**
     * AWS's own example for this operation: a user whose policies do not allow the action is
     * allowed it on the strength of an input document alone.
     */
    @Test
    void anInputDocumentAllowsWhatThePrincipalHasNoPolicyFor() {
        String user = createUser("input-allow");

        assertEquals("implicitDeny", decision(simulate(user, "s3:PutObject")));
        assertEquals("allowed", decision(simulate(user, "s3:PutObject")
                .formParam(INPUT + "1", allow("s3:PutObject"))));
    }

    /** The documents are additional, so the principal's own policies still decide beside them. */
    @Test
    void thePrincipalsOwnPoliciesCountBesideTheDocument() {
        String user = createUser("input-beside");
        attachNewPolicy(user, "input-reader", "s3:GetObject");

        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(INPUT + "1", allow("s3:PutObject"))));
        assertEquals("allowed", decision(simulate(user, "s3:PutObject")
                .formParam(INPUT + "1", allow("s3:PutObject"))));
    }

    /** A document is evaluated like a policy the principal holds, deny and all. */
    @Test
    void aDocumentsDenyStopsWhatAnAttachedPolicyAllows() {
        String user = createUser("input-deny");
        attachNewPolicy(user, "input-reader", "s3:GetObject");

        assertEquals("allowed", decision(simulate(user, "s3:GetObject")));
        assertEquals("explicitDeny", decision(simulate(user, "s3:GetObject")
                .formParam(INPUT + "1", deny("s3:GetObject"))));
    }

    /** Every index the request numbers is read, the bare spelling and a gap among them. */
    @Test
    void everyDocumentTheRequestNumbersIsRead() {
        String user = createUser("input-indices");

        assertEquals("allowed", decision(simulate(user, "ec2:RunInstances")
                .formParam(INPUT + "1", allow("s3:GetObject"))
                .formParam(INPUT + "2", allow("ec2:RunInstances"))));
        assertEquals("allowed", decision(simulate(user, "ec2:RunInstances")
                .formParam(INPUT + "1", allow("s3:GetObject"))
                .formParam(INPUT + "3", allow("ec2:RunInstances"))));
        assertEquals("allowed", decision(simulate(user, "s3:PutObject")
                .formParam("PolicyInputList.1", allow("s3:PutObject"))));
    }

    /**
     * "An IAM entity can only have one permissions boundary in effect at a time": a boundary
     * handed in replaces the stored one rather than joining it, so the action the stored boundary
     * refused is allowed and the one it allowed is not.
     */
    @Test
    void aBoundaryHandedInStandsInForTheStoredOne() {
        String user = createUser("input-boundary-swap");
        attachNewPolicy(user, "input-reader", "s3:GetObject");
        attachNewPolicy(user, "input-runner", "ec2:RunInstances");
        putBoundary(user, "ec2:RunInstances");

        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")));
        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(BOUNDARY + "1", allow("s3:GetObject"))));
        assertEquals("implicitDeny", decision(simulate(user, "ec2:RunInstances")
                .formParam(BOUNDARY + "1", allow("s3:GetObject"))));
    }

    /** The member is "the IAM permissions boundary policy to simulate", stored one or not. */
    @Test
    void aBoundaryHandedInAppliesWhenNoneIsStored() {
        String user = createUser("input-boundary-only");
        attachNewPolicy(user, "input-reader", "s3:GetObject");

        assertEquals("allowed", decision(simulate(user, "s3:GetObject")));
        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")
                .formParam(BOUNDARY + "1", allow("ec2:RunInstances"))));
        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")
                .formParam("PermissionsBoundaryPolicyInputList.1", allow("ec2:RunInstances"))));
    }

    /**
     * "You can input only one permissions boundary when you pass a policy to this operation", so
     * the first document decides and the rest are left out, whichever way round they arrive.
     */
    @Test
    void onlyTheFirstBoundaryDocumentIsApplied() {
        String user = createUser("input-boundary-first");
        attachNewPolicy(user, "input-reader", "s3:GetObject");

        assertEquals("allowed", decision(simulate(user, "s3:GetObject")
                .formParam(BOUNDARY + "1", allow("s3:GetObject"))
                .formParam(BOUNDARY + "2", allow("ec2:RunInstances"))));
        assertEquals("implicitDeny", decision(simulate(user, "s3:GetObject")
                .formParam(BOUNDARY + "1", allow("ec2:RunInstances"))
                .formParam(BOUNDARY + "2", allow("s3:GetObject"))));
    }

    /** Both simulators read that one boundary document through the same reader. */
    @Test
    void theCustomSimulatorReadsItsBoundaryTheSameWay() {
        String decision = iam("SimulateCustomPolicy")
                .formParam("PolicyInputList.member.1", allow("s3:GetObject"))
                .formParam("ActionNames.member.1", "s3:GetObject")
                .formParam("ResourceArns.member.1", "*")
                .formParam(BOUNDARY + "1", allow("ec2:RunInstances"))
                .formParam(BOUNDARY + "2", allow("s3:GetObject"))
            .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString(CUSTOM_DECISION);

        assertEquals("implicitDeny", decision);
    }

    /**
     * {@code PolicyExclusionList} names the policies "actually attached to the principal", and a
     * document the request handed in is attached to nothing: a simulation that leaves out every
     * part a type can name still carries it, and a boundary handed in still holds the simulation
     * down even where the stored boundary was excluded.
     */
    @Test
    void noExclusionIdentifierReachesWhatTheRequestHandedIn() {
        String user = createUser("input-exclusion");
        attachNewPolicy(user, "input-reader", "s3:GetObject");
        putUserPolicy(user, "input-own", "s3:GetObject");
        putBoundary(user, "s3:GetObject");

        assertEquals("implicitDeny", decision(simulateWithEveryPartExcluded(user, "s3:GetObject")));
        assertEquals("allowed", decision(simulateWithEveryPartExcluded(user, "s3:GetObject")
                .formParam(INPUT + "1", allow("s3:GetObject"))));
        assertEquals("implicitDeny", decision(simulateWithEveryPartExcluded(user, "s3:GetObject")
                .formParam(INPUT + "1", allow("s3:GetObject"))
                .formParam(BOUNDARY + "1", allow("ec2:RunInstances"))));
    }

    /**
     * The constraints ahead of the operation hold either document to the shape the model declares
     * for a policy document, so one past the declared maximum is answered before the simulation
     * runs rather than parsed into a document that matches nothing. Each is named under the index
     * the request numbered it with.
     */
    @Test
    void eitherDocumentIsHeldToTheShapeTheModelDeclares() {
        String user = createUser("input-shape");

        simulate(user, "s3:GetObject")
                .formParam(BOUNDARY + "1", "a".repeat(PAST_THE_MAXIMUM))
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("ValidationError"))
                .body(MESSAGE, containsString("permissionsBoundaryPolicyInputList.1.member"));

        simulate(user, "s3:GetObject")
                .formParam(INPUT + "2", "a".repeat(PAST_THE_MAXIMUM))
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("ValidationError"))
                .body(MESSAGE, containsString("policyInputList.2.member"));
    }

    /**
     * The custom simulator keeps the sentence it answers an empty list with, which is why the
     * member is carried by hand in the generated constraints there and read from the model here:
     * it is required on that operation and optional on this one.
     */
    @Test
    void theCustomSimulatorKeepsItsOwnWordingForAnEmptyList() {
        iam("SimulateCustomPolicy")
                .formParam("ActionNames.member.1", "s3:GetObject")
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("ValidationError"))
                .body(MESSAGE, equalTo("At least one PolicyInputList member is required."));
    }
}
