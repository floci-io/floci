package io.floci.aws.services.iam;

import io.floci.aws.testing.IamSimulationRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.floci.aws.testing.IamSimulationRequests.answer;
import static io.floci.aws.testing.IamSimulationRequests.level;
import static io.floci.aws.testing.IamSimulationRequests.strings;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a result says about the statements that decided it, the keys it wanted, and the two policy
 * types whose effect it reports on their own.
 *
 * <p>{@code MatchedStatements} is "a list of the statements in the input policies that determine
 * the result for this scenario", where "even if multiple statements allow the operation on the
 * resource, if only one statement denies that operation, then the explicit deny overrides any
 * allow. In addition, the deny statement is the only entry included in the result". It names each
 * statement by the policy it came from and the place it sits in that policy's text, and it leaves
 * out what AWS leaves out: the field "doesn't include statements from service control policies
 * (SCPs)", and "only statements from identity-based and resource-based policies appear here", so a
 * permissions boundary reports its own effect under {@code PermissionsBoundaryDecisionDetail}
 * instead, which is what AWS's own same-account example shows.
 *
 * <p>{@code MissingContextValues} is "a list of context keys that are required by the included
 * input policies but that were not provided by one of the input parameters", with the same
 * exclusion: a key "referenced only by an SCP" is not returned.
 */
@QuarkusTest
class IamSimulateResultDetailIntegrationTest {

    private static final String CREDENTIAL = "test";

    private static final String ACCOUNT = "000000000000";

    private static final String CUSTOM = "SimulateCustomPolicyResponse.SimulateCustomPolicyResult"
            + ".EvaluationResults.member";
    private static final String CUSTOM_RESOURCES = CUSTOM + ".ResourceSpecificResults.member";
    private static final String PRINCIPAL = "SimulatePrincipalPolicyResponse"
            + ".SimulatePrincipalPolicyResult.EvaluationResults.member";

    private static final String BUCKET = "arn:aws:s3:::my-test-bucket";
    private static final String OTHER_BUCKET = "arn:aws:s3:::my-other-bucket";
    private static final String INPUT_1 = "PolicyInputList.member.1";

    /**
     * AWS's own example document for {@code SimulatePrincipalPolicy}, spelled as its published
     * request supplies it. Its published response reports the statement at line 3, column 16
     * through line 7, column 4.
     */
    private static final String PUBLISHED_EXAMPLE = """
            {
              "Version":"2012-10-17",
              "Statement":{
                "Effect":"Allow",
                "Action":"s3:PutObject",
                "Resource":"arn:aws:s3:::my-test-bucket"
              }
            }""";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void theSuppliedDocumentThatAllowedIsNamedWhereAwsNamesIt() {
        XmlPath answer = answer(custom("s3:PutObject", BUCKET).formParam(INPUT_1, PUBLISHED_EXAMPLE));

        assertEquals(List.of("allowed"), strings(answer, CUSTOM + ".EvalDecision"));
        assertEquals(List.of("PolicyInputList.1"),
                strings(answer, CUSTOM + ".MatchedStatements.member.SourcePolicyId"));
        // A supplied document carries no type, as AWS's own example reports it.
        assertEquals(List.of(), strings(answer, CUSTOM + ".MatchedStatements.member.SourcePolicyType"));
        assertEquals(List.of("3"), strings(answer, CUSTOM + ".MatchedStatements.member.StartPosition.Line"));
        assertEquals(List.of("16"), strings(answer, CUSTOM + ".MatchedStatements.member.StartPosition.Column"));
        assertEquals(List.of("7"), strings(answer, CUSTOM + ".MatchedStatements.member.EndPosition.Line"));
        assertEquals(List.of("4"), strings(answer, CUSTOM + ".MatchedStatements.member.EndPosition.Column"));
    }

    @Test
    void theStatementThatDeniedIsTheOnlyOneReported() {
        String document = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"},
                  {"Effect":"Deny","Action":"s3:GetObject","Resource":"*"}]}""";

        XmlPath answer = answer(custom("s3:GetObject", BUCKET).formParam(INPUT_1, document));

        assertEquals(List.of("explicitDeny"), strings(answer, CUSTOM + ".EvalDecision"));
        // One entry, and it is the deny: the allow that also matched is not reported beside it.
        assertEquals(List.of("3"), strings(answer, CUSTOM + ".MatchedStatements.member.StartPosition.Line"));
    }

    @Test
    void anImplicitDenyNamesNoStatement() {
        XmlPath answer = answer(custom("s3:DeleteObject", BUCKET).formParam(INPUT_1, PUBLISHED_EXAMPLE));

        assertEquals(List.of("implicitDeny"), strings(answer, CUSTOM + ".EvalDecision"));
        assertEquals(List.of(), strings(answer, CUSTOM + ".MatchedStatements.member.SourcePolicyId"));
    }

    @Test
    void everyDocumentThatAllowedIsNamedByItsOwnPosition() {
        XmlPath answer = answer(custom("s3:GetObject", BUCKET)
                .formParam(INPUT_1, allow("s3:GetObject"))
                .formParam("PolicyInputList.member.2", allow("s3:*")));

        assertEquals(List.of("allowed"), strings(answer, CUSTOM + ".EvalDecision"));
        assertEquals(List.of("PolicyInputList.1", "PolicyInputList.2"),
                strings(answer, CUSTOM + ".MatchedStatements.member.SourcePolicyId"));
    }

    /**
     * The exclusion AWS publishes for both members: the statements of a service control policy are
     * not reported, so a deny that came from one leaves the list empty while still deciding.
     */
    @Test
    void aDenyFromAnOrganizationLevelNamesNoStatement() {
        XmlPath answer = answer(custom("s3:GetObject", BUCKET)
                .formParam(INPUT_1, allow("s3:GetObject"))
                .formParam(level(1), deny("s3:GetObject")));

        assertEquals(List.of("explicitDeny"), strings(answer, CUSTOM + ".EvalDecision"));
        assertEquals(List.of(), strings(answer, CUSTOM + ".MatchedStatements.member.StartPosition.Line"));
    }

    @Test
    void aPrincipalsInlinePolicyIsNamedByItsNameAndTheEntityHoldingIt() {
        String userName = userWithInlinePolicy("detail-inline", allow("s3:GetObject"));

        XmlPath answer = answer(simulateUser(userName, "s3:GetObject", BUCKET));

        assertEquals(List.of("allowed"), strings(answer, PRINCIPAL + ".EvalDecision"));
        assertEquals(List.of("allow-it"),
                strings(answer, PRINCIPAL + ".MatchedStatements.member.SourcePolicyId"));
        assertEquals(List.of("user"),
                strings(answer, PRINCIPAL + ".MatchedStatements.member.SourcePolicyType"));
    }

    @Test
    void aPrincipalsManagedPolicyIsNamedByItsOwnName() {
        String policyName = "detail-managed-" + System.nanoTime();
        String userName = userWithManagedPolicy("detail-attached", policyName, allow("s3:GetObject"));

        XmlPath answer = answer(simulateUser(userName, "s3:GetObject", BUCKET));

        assertEquals(List.of(policyName),
                strings(answer, PRINCIPAL + ".MatchedStatements.member.SourcePolicyId"));
        assertEquals(List.of("user-managed"),
                strings(answer, PRINCIPAL + ".MatchedStatements.member.SourcePolicyType"));
    }

    @Test
    void theKeysAPolicyWantedAndTheCallDidNotSupplyAreNamed() {
        RequestSpecification simulation = custom("s3:GetObject", BUCKET)
                .formParam(INPUT_1, allowIf("s3:GetObject", "s3:prefix", "home/"));

        assertEquals(List.of("s3:prefix"), strings(answer(simulation),
                CUSTOM + ".MissingContextValues.member"));

        // Supplied, so the key is no longer missing, whatever the comparison then decides.
        XmlPath supplied = answer(custom("s3:GetObject", BUCKET)
                .formParam(INPUT_1, allowIf("s3:GetObject", "s3:prefix", "home/"))
                .formParam("ContextEntries.member.1.ContextKeyName", "s3:prefix")
                .formParam("ContextEntries.member.1.ContextKeyValues.member.1", "home/"));
        assertEquals(List.of(), strings(supplied, CUSTOM + ".MissingContextValues.member"));
        assertEquals(List.of("allowed"), strings(supplied, CUSTOM + ".EvalDecision"));
    }

    /**
     * An excluding resource pattern is resolved like an including one, so a value supplied for the
     * variable it carries can change the answer, and the key is named.
     */
    @Test
    void aVariableInAnExcludingResourcePatternIsNamed() {
        String document = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject",
                 "NotResource":"arn:aws:s3:::my-other-bucket/${aws:username}"}]}""";

        XmlPath answer = answer(custom("s3:GetObject", BUCKET).formParam(INPUT_1, document));

        assertEquals(List.of("aws:username"), strings(answer, CUSTOM + ".MissingContextValues.member"));
        // The pattern excludes nothing without a value, so the statement still allows the action.
        assertEquals(List.of("allowed"), strings(answer, CUSTOM + ".EvalDecision"));
    }

    /**
     * A key an operator answers for without a value is not one the policy required: an
     * {@code ...IfExists} suffix says "if the key is not present, evaluate the condition element
     * as true", and a {@code Null} asking for absence is satisfied by the absence. A {@code Null}
     * asking for presence does require it, and names it.
     */
    @Test
    void aKeyAnOperatorAnswersWithoutIsNotNamed() {
        String ifExists = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject",
                 "Resource":"*","Condition":{"StringEqualsIfExists":{"s3:prefix":"home/"}}}]}""";
        XmlPath answer = answer(custom("s3:GetObject", BUCKET).formParam(INPUT_1, ifExists));
        assertEquals(List.of(), strings(answer, CUSTOM + ".MissingContextValues.member"));
        assertEquals(List.of("allowed"), strings(answer, CUSTOM + ".EvalDecision"));

        String absent = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject",
                 "Resource":"*","Condition":{"Null":{"s3:prefix":"true"}}}]}""";
        assertEquals(List.of(), strings(answer(custom("s3:GetObject", BUCKET).formParam(INPUT_1, absent)),
                CUSTOM + ".MissingContextValues.member"));

        String present = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject",
                 "Resource":"*","Condition":{"Null":{"s3:prefix":"false"}}}]}""";
        assertEquals(List.of("s3:prefix"), strings(answer(custom("s3:GetObject", BUCKET)
                .formParam(INPUT_1, present)), CUSTOM + ".MissingContextValues.member"));
    }

    /**
     * A context key "is not case sensitive", so two resources whose statements spell one key
     * differently want the one key between them, however each of them writes it.
     */
    @Test
    void oneKeyTwoSpellingsIsNamedOnceAtTheTop() {
        String document = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"%s",
                   "Condition":{"StringEquals":{"s3:prefix":"home/"}}},
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"%s",
                   "Condition":{"StringEquals":{"S3:Prefix":"home/"}}}]}""".formatted(BUCKET, OTHER_BUCKET);

        XmlPath answer = answer(custom("s3:GetObject", BUCKET, OTHER_BUCKET).formParam(INPUT_1, document));

        assertEquals(List.of("s3:prefix"), strings(answer, CUSTOM + ".MissingContextValues.member"));
        assertEquals(List.of("S3:Prefix"),
                strings(answer, CUSTOM_RESOURCES + ".find { it.EvalResourceName == '" + OTHER_BUCKET
                        + "' }.MissingContextValues.member"));
    }

    /** The published exclusion: a key referenced only by an SCP is not returned. */
    @Test
    void aKeyOnlyAnOrganizationLevelReferencesIsNotNamed() {
        XmlPath answer = answer(custom("s3:GetObject", BUCKET)
                .formParam(INPUT_1, allow("s3:GetObject"))
                .formParam(level(1), allowIf("s3:GetObject", "s3:prefix", "home/")));

        assertEquals(List.of(), strings(answer, CUSTOM + ".MissingContextValues.member"));
    }

    /**
     * A statement scoped to one of the resources wants its keys for that resource alone, which is
     * what the per-resource lists are for, and the top-level list is "the deduplicated set of
     * missing context values across all requested resources".
     */
    @Test
    void theKeysAreNamedPerResourceAndDedupedAtTheTop() {
        String document = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"},
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"%s",
                   "Condition":{"StringEquals":{"s3:prefix":"home/"}}}]}""".formatted(BUCKET);

        XmlPath answer = answer(custom("s3:GetObject", BUCKET, OTHER_BUCKET).formParam(INPUT_1, document));

        assertEquals(List.of("s3:prefix"), strings(answer, CUSTOM + ".MissingContextValues.member"));
        assertEquals(List.of("s3:prefix"),
                strings(answer, CUSTOM_RESOURCES + ".find { it.EvalResourceName == '" + BUCKET
                        + "' }.MissingContextValues.member"));
        assertEquals(List.of(), strings(answer, CUSTOM_RESOURCES + ".find { it.EvalResourceName == '"
                + OTHER_BUCKET + "' }.MissingContextValues.member"));
    }

    /**
     * AWS's same-account example carries a boundary that allows the action and reports it under its
     * own detail, with the identity policy alone among the matched statements.
     */
    @Test
    void theBoundaryReportsItsOwnEffectAndIsNotNamedAmongTheStatements() {
        XmlPath allowed = answer(custom("s3:GetObject", BUCKET)
                .formParam(INPUT_1, allow("s3:GetObject"))
                .formParam("PermissionsBoundaryPolicyInputList.member.1", allow("s3:*")));

        assertEquals(List.of("allowed"), strings(allowed, CUSTOM + ".EvalDecision"));
        assertEquals(List.of("true"), strings(allowed,
                CUSTOM + ".PermissionsBoundaryDecisionDetail.AllowedByPermissionsBoundary"));
        assertEquals(List.of("true"), strings(allowed,
                CUSTOM_RESOURCES + ".PermissionsBoundaryDecisionDetail.AllowedByPermissionsBoundary"));
        assertEquals(List.of("PolicyInputList.1"),
                strings(allowed, CUSTOM + ".MatchedStatements.member.SourcePolicyId"));

        XmlPath capped = answer(custom("s3:GetObject", BUCKET)
                .formParam(INPUT_1, allow("s3:GetObject"))
                .formParam("PermissionsBoundaryPolicyInputList.member.1", allow("s3:PutObject")));

        assertEquals(List.of("implicitDeny"), strings(capped, CUSTOM + ".EvalDecision"));
        assertEquals(List.of("false"), strings(capped,
                CUSTOM + ".PermissionsBoundaryDecisionDetail.AllowedByPermissionsBoundary"));
    }

    @Test
    void theOrganizationLevelsReportWhetherTheyAllowed() {
        XmlPath allowed = answer(custom("s3:GetObject", BUCKET)
                .formParam(INPUT_1, allow("s3:GetObject"))
                .formParam(level(1), allow("s3:*")));
        assertEquals(List.of("true"), strings(allowed,
                CUSTOM + ".OrganizationsDecisionDetail.AllowedByOrganizations"));

        XmlPath denied = answer(custom("s3:GetObject", BUCKET)
                .formParam(INPUT_1, allow("s3:GetObject"))
                .formParam(level(1), deny("s3:GetObject")));
        assertEquals(List.of("false"), strings(denied,
                CUSTOM + ".OrganizationsDecisionDetail.AllowedByOrganizations"));

        // "Only applies if the simulated user's account is part of an organization": a simulation
        // handed no levels runs against no organization and reports nothing for one.
        XmlPath none = answer(custom("s3:GetObject", BUCKET).formParam(INPUT_1, allow("s3:GetObject")));
        assertEquals(List.of(), strings(none, CUSTOM + ".OrganizationsDecisionDetail"));
    }

    private static RequestSpecification iam(String action) {
        return IamSimulationRequests.iam(CREDENTIAL, action);
    }

    private static RequestSpecification custom(String actionName, String... resourceArns) {
        RequestSpecification simulation = iam("SimulateCustomPolicy")
                .formParam("ActionNames.member.1", actionName);
        for (int resource = 0; resource < resourceArns.length; resource++) {
            simulation.formParam("ResourceArns.member." + (resource + 1), resourceArns[resource]);
        }
        return simulation;
    }

    private static RequestSpecification simulateUser(String userName, String actionName, String resourceArn) {
        return iam("SimulatePrincipalPolicy")
                .formParam("PolicySourceArn", "arn:aws:iam::" + ACCOUNT + ":user/" + userName)
                .formParam("ActionNames.member.1", actionName)
                .formParam("ResourceArns.member.1", resourceArn);
    }

    /** A user holding one inline policy, named {@code allow-it}, which is what a result reports. */
    private String userWithInlinePolicy(String prefix, String document) {
        String userName = createUser(prefix);
        iam("PutUserPolicy").formParam("UserName", userName).formParam("PolicyName", "allow-it")
                .formParam("PolicyDocument", document)
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "allow-it").when().post("/").then().statusCode(200));
        return userName;
    }

    /** A user holding one customer-managed policy, which a result reports by its own name. */
    private String userWithManagedPolicy(String prefix, String policyName, String document) {
        String userName = createUser(prefix);
        String policyArn = iam("CreatePolicy").formParam("PolicyName", policyName)
                .formParam("PolicyDocument", document)
            .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> iam("DeletePolicy").formParam("PolicyArn", policyArn)
            .when().post("/").then().statusCode(200));
        iam("AttachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", policyArn)
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DetachUserPolicy").formParam("UserName", userName)
            .formParam("PolicyArn", policyArn).when().post("/").then().statusCode(200));
        return userName;
    }

    private String createUser(String prefix) {
        String userName = prefix + "-" + System.nanoTime();
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
            .when().post("/").then().statusCode(200));
        return userName;
    }

    private static String allow(String action) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"*\"}]}";
    }

    private static String deny(String action) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\""
                + action + "\",\"Resource\":\"*\"}]}";
    }

    private static String allowIf(String action, String key, String value) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"" + key
                + "\":\"" + value + "\"}}}]}";
    }
}
