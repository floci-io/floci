package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A policy document a request supplies has to read as one before anything is decided from it.
 * Four operations take {@code PolicyInputList}, the two simulators and the two context-key
 * readers, and each takes "the complete, valid JSON text of an IAM policy", so a value that is no
 * policy document is an input they answer: a 400 {@code InvalidInput} naming the entry by the
 * index it arrived under.
 *
 * <p>Two values fail that reading: one that does not parse as a JSON object, and one that parses
 * as an object carrying no {@code Statement}, "the main element for a policy. This element is
 * required." A supplied permissions boundary is held to the same rule under the one sentence that
 * names no entry, the member carrying a single document.
 *
 * <p>The 500 {@code PolicyEvaluation} the simulators model is answered by neither: it carries no
 * sender fault where {@code InvalidInput} does, and the context-key readers take the same member
 * and model no {@code PolicyEvaluation} at all.
 */
@QuarkusTest
class IamSuppliedPolicyDocumentIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261112/us-east-1/iam/aws4_request";

    private static final String ACCOUNT = "000000000000";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    private static final String INPUT = "PolicyInputList.member.";
    private static final String BOUNDARY = "PermissionsBoundaryPolicyInputList.member.";

    private static final String CUSTOM_DECISION = "SimulateCustomPolicyResponse"
            + ".SimulateCustomPolicyResult.EvaluationResults.member.EvalDecision";

    private static final String PRINCIPAL_DECISION = "SimulatePrincipalPolicyResponse"
            + ".SimulatePrincipalPolicyResult.EvaluationResults.member.EvalDecision";

    /** The sentence an entry of the list is refused with, carrying the index it arrived under. */
    private static final String ITEM_REFUSAL = "Policy input list item %s has invalid content";

    /** The sentence every other supplied document is refused with, naming no entry. */
    private static final String DOCUMENT_REFUSAL = "Syntax errors in policy.";

    /** Everything these tests create outlives the class otherwise: the emulator is shared. */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static String userArn(String userName) {
        return "arn:aws:iam::" + ACCOUNT + ":user/" + userName;
    }

    private static String allow(String action) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"" + action + "\",\"Resource\":\"*\"}]}";
    }

    private String createUser(String prefix) {
        String userName = unique(prefix);
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
            .when().post("/").then().statusCode(200));
        return userName;
    }

    private static RequestSpecification simulateCustom() {
        return iam("SimulateCustomPolicy")
                .formParam("ActionNames.member.1", "s3:GetObject")
                .formParam("ResourceArns.member.1", "*");
    }

    private static RequestSpecification simulatePrincipal(String policySourceArn) {
        return iam("SimulatePrincipalPolicy")
                .formParam("PolicySourceArn", policySourceArn)
                .formParam("ActionNames.member.1", "s3:GetObject")
                .formParam("ResourceArns.member.1", "*");
    }

    private static void expectItemRefused(RequestSpecification request, String index) {
        request.when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"))
                .body(MESSAGE, equalTo(ITEM_REFUSAL.formatted(index)));
    }

    private static void expectDocumentRefused(RequestSpecification request) {
        request.when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"))
                .body(MESSAGE, equalTo(DOCUMENT_REFUSAL));
    }

    /**
     * A text that is no JSON object carries none of the top-level elements of the policy grammar,
     * whether it is not JSON at all or JSON of another kind.
     */
    @Test
    void aDocumentThatIsNoJsonObjectIsRefusedByTheIndexItArrivedUnder() {
        expectItemRefused(simulateCustom().formParam(INPUT + "1", "{not json"), "1");
        expectItemRefused(simulateCustom().formParam(INPUT + "1", "[]"), "1");
        expectItemRefused(simulateCustom().formParam(INPUT + "1", "7"), "1");
    }

    /**
     * "The Statement element is the main element for a policy. This element is required.", so an
     * object without it is no more a policy than a text that does not parse, and the refusal does
     * not tell the two apart.
     */
    @Test
    void aDocumentCarryingNoStatementIsRefusedTheSameWay() {
        expectItemRefused(simulateCustom().formParam(INPUT + "1", "{}"), "1");
        expectItemRefused(simulateCustom()
                .formParam(INPUT + "1", "{\"Version\":\"2012-10-17\"}"), "1");
    }

    /**
     * The member carries several documents, so the refusal names which one it refused, by the
     * index the request numbered it with rather than by its place among the documents read. Both
     * spellings of the member are numbered the same way.
     */
    @Test
    void theRefusalNamesTheIndexTheRequestNumberedItWith() {
        expectItemRefused(simulateCustom()
                .formParam(INPUT + "1", allow("s3:GetObject"))
                .formParam(INPUT + "3", "{}"), "3");
        expectItemRefused(simulateCustom()
                .formParam("PolicyInputList.2", "{}"), "2");
    }

    /**
     * The rule stops at the element the grammar requires: a document carrying an empty
     * {@code Statement} describes no permission, which is a policy that allows nothing rather than
     * a value the operation cannot read.
     */
    @Test
    void aStatementElementThatGrantsNothingIsStillADocument() {
        assertEquals("implicitDeny", simulateCustom()
                .formParam(INPUT + "1", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
            .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString(CUSTOM_DECISION));
    }

    /** The principal simulator reads the same member, so it answers the same way. */
    @Test
    void thePrincipalSimulatorRefusesTheSameDocuments() {
        String user = createUser("supplied-document");

        expectItemRefused(simulatePrincipal(userArn(user)).formParam(INPUT + "1", "{not json"), "1");
        expectItemRefused(simulatePrincipal(userArn(user)).formParam(INPUT + "2", "{}"), "2");
        assertEquals("allowed", simulatePrincipal(userArn(user))
                .formParam(INPUT + "1", allow("s3:GetObject"))
            .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString(PRINCIPAL_DECISION));
    }

    /**
     * The documents are read where this package answers an input, ahead of the principal lookup,
     * so a request that both hands in an unreadable document and names nobody is answered for the
     * document it supplied rather than for the entity it did not find.
     */
    @Test
    void aSuppliedDocumentIsAnsweredAheadOfThePrincipalLookup() {
        String absent = userArn(unique("supplied-absent"));

        simulatePrincipal(absent).when().post("/").then().statusCode(404)
                .body(CODE, equalTo("NoSuchEntity"));
        expectItemRefused(simulatePrincipal(absent).formParam(INPUT + "1", "{}"), "1");
    }

    /**
     * A supplied permissions boundary is one document, so the refusal identifies none and carries
     * the sentence that names no entry.
     */
    @Test
    void aSuppliedBoundaryThatIsNoPolicyDocumentIsRefused() {
        String user = createUser("supplied-boundary");

        expectDocumentRefused(simulateCustom()
                .formParam(INPUT + "1", allow("s3:GetObject"))
                .formParam(BOUNDARY + "1", "{}"));
        expectDocumentRefused(simulatePrincipal(userArn(user))
                .formParam(BOUNDARY + "1", "{not json"));
    }

    /**
     * Both context-key readers take the same member and model {@code InvalidInput} with no
     * {@code PolicyEvaluation} at all, which is what settles the answer for the simulators too.
     */
    @Test
    void bothContextKeyReadersRefuseWhatTheSimulatorsDo() {
        String user = createUser("supplied-context-keys");

        expectItemRefused(iam("GetContextKeysForCustomPolicy")
                .formParam(INPUT + "1", "{}"), "1");
        expectItemRefused(iam("GetContextKeysForPrincipalPolicy")
                .formParam("PolicySourceArn", userArn(user))
                .formParam(INPUT + "1", "{not json"), "1");
        iam("GetContextKeysForCustomPolicy")
                .formParam(INPUT + "1", allow("s3:GetObject"))
            .when().post("/").then().statusCode(200);
    }
}
