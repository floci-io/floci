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
import static io.floci.aws.testing.IamSimulationRequests.strings;
import static java.util.Collections.nCopies;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shape a simulation answers in: one result per action, whatever the call named for resources.
 *
 * <p>The result's reference says so outright: "The simulator now returns a single
 * {@code EvaluationResult} per action, regardless of how many resource ARNs are provided.
 * Previously, simulating one action against N resources returned N evaluation results, each
 * containing the same aggregate decision. The top-level fields ({@code EvalDecision},
 * {@code MatchedStatements}, {@code MissingContextValues}, {@code EvalDecisionDetails}) now
 * represent the aggregate decision across all requested resources. The top-level
 * {@code EvalDecision} reflects the most restrictive decision across all resources (for example,
 * if any resource produces {@code explicitDeny}, the top-level decision is {@code explicitDeny})."
 * Each resource the call named answers for itself under {@code ResourceSpecificResults}, where the
 * reference sends a client that read them off the top-level result before: "If your application
 * parses evaluation results per resource ARN, update your code to read per-resource decisions from
 * {@code ResourceSpecificResults} rather than from the top-level result."
 *
 * <p>{@code EvalResourceName} names no resource at the top any more. It carries "the ARN template
 * for the simulated resource type (for example, {@code arn:${Partition}:s3:::${BucketName}/${KeyName}}),
 * or {@code *} if no ARN format is defined for the action. This is not a specific
 * customer-provided resource ARN", and the resources the call named carry their ARNs below.
 */
@QuarkusTest
class IamSimulateResultShapeIntegrationTest {

    private static final String CREDENTIAL = "test";

    private static final String ACCOUNT = "000000000000";

    private static final String CUSTOM = "SimulateCustomPolicyResponse.SimulateCustomPolicyResult"
            + ".EvaluationResults.member";
    private static final String CUSTOM_RESOURCES = CUSTOM + ".ResourceSpecificResults.member";
    private static final String PRINCIPAL = "SimulatePrincipalPolicyResponse"
            + ".SimulatePrincipalPolicyResult.EvaluationResults.member";
    private static final String PRINCIPAL_RESOURCES = PRINCIPAL + ".ResourceSpecificResults.member";

    private static final String OBJECT_A = "arn:aws:s3:::floci-shape-a/key";
    private static final String OBJECT_B = "arn:aws:s3:::floci-shape-b/key";
    private static final String OBJECT_C = "arn:aws:s3:::floci-shape-c/key";

    /** The templates the generated tables publish for the actions the naming cases simulate. */
    private static final String USER_TEMPLATE = "arn:${Partition}:iam::${Account}:user/${UserNameWithPath}";
    private static final String PARAMETER_TEMPLATE =
            "arn:${Partition}:ssm:${Region}:${Account}:parameter/${ParameterNameWithoutLeadingSlash}";

    /** The user the principal-side case simulates outlives the class otherwise: the emulator is shared. */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void oneActionAnswersOnceWithEachResourceTheCallNamedUnderIt() {
        XmlPath answer = answer(custom(allowAll("s3:GetObject"), List.of("s3:GetObject"),
                List.of(OBJECT_A, OBJECT_B, OBJECT_C)));

        assertEquals(List.of("s3:GetObject"), strings(answer, CUSTOM + ".EvalActionName"));
        assertEquals(List.of("allowed"), strings(answer, CUSTOM + ".EvalDecision"));
        assertEquals(List.of(OBJECT_A, OBJECT_B, OBJECT_C),
                strings(answer, CUSTOM_RESOURCES + ".EvalResourceName"));
        assertEquals(nCopies(3, "allowed"), strings(answer, CUSTOM_RESOURCES + ".EvalResourceDecision"));
    }

    @Test
    void theTopDecisionIsTheMostRestrictiveOfTheResources() {
        // Allowed everywhere, denied on the second resource alone.
        XmlPath answer = answer(custom(allowAllDenying("s3:GetObject", OBJECT_B),
                List.of("s3:GetObject"), List.of(OBJECT_A, OBJECT_B)));

        assertEquals(List.of("explicitDeny"), strings(answer, CUSTOM + ".EvalDecision"));
        assertEquals(List.of("allowed", "explicitDeny"),
                strings(answer, CUSTOM_RESOURCES + ".EvalResourceDecision"));
    }

    /**
     * An implicit deny on one resource is the aggregate as surely as an explicit one: the action is
     * refused for that resource, so only an action allowed on every resource the call named is
     * allowed at the top.
     */
    @Test
    void aResourceNoStatementAllowsMakesTheTopAnImplicitDeny() {
        XmlPath answer = answer(custom(allowOn("s3:GetObject", OBJECT_A),
                List.of("s3:GetObject"), List.of(OBJECT_A, OBJECT_B)));

        assertEquals(List.of("implicitDeny"), strings(answer, CUSTOM + ".EvalDecision"));
        assertEquals(List.of("allowed", "implicitDeny"),
                strings(answer, CUSTOM_RESOURCES + ".EvalResourceDecision"));
    }

    /**
     * The per-resource section answers "a list of resources". A simulation whose resource is the
     * wildcard is the case the reference answers at the top level alone, "either explicitly, or
     * when the {@code ResourceArns} parameter blank", so both spellings carry no section.
     */
    @Test
    void aSimulationWhoseResourceIsTheWildcardCarriesNoPerResourceSection() {
        XmlPath named = answer(custom(allowAll("s3:GetObject"), List.of("s3:GetObject"), List.of("*")));
        assertEquals(List.of("allowed"), strings(named, CUSTOM + ".EvalDecision"));
        assertEquals(List.of(), strings(named, CUSTOM_RESOURCES + ".EvalResourceName"));

        XmlPath unnamed = answer(custom(allowAll("s3:GetObject"), List.of("s3:GetObject"), List.of()));
        assertEquals(List.of("allowed"), strings(unnamed, CUSTOM + ".EvalDecision"));
        assertEquals(List.of(), strings(unnamed, CUSTOM_RESOURCES + ".EvalResourceName"));
    }

    /**
     * The reference distinguishes the two ways of carrying nothing: "if the simulation evaluates
     * policies within the same account and includes a resource ARN, then the parameter is present
     * but the response is empty. If the simulation evaluates policies within the same account and
     * specifies all resources (`*`), then the parameter is not returned." Both simulations here are
     * within one account, so the member is empty for a call that named resources and absent for one
     * that named none.
     */
    @Test
    void theDecisionDetailsAreEmptyForNamedResourcesAndAbsentForTheWildcard() {
        XmlPath named = answer(custom(allowAll("s3:GetObject"), List.of("s3:GetObject"),
                List.of(OBJECT_A)));
        assertEquals(List.of(""), strings(named, CUSTOM + ".EvalDecisionDetails"));

        XmlPath wildcard = answer(custom(allowAll("s3:GetObject"), List.of("s3:GetObject"), List.of()));
        assertEquals(List.of(), strings(wildcard, CUSTOM + ".EvalDecisionDetails"));
    }

    @Test
    void everyActionAnswersOnceWhateverTheResourceCount() {
        XmlPath answer = answer(custom(allowAll("s3:GetObject"),
                List.of("s3:GetObject", "s3:PutObject"), List.of(OBJECT_A, OBJECT_B)));

        // Two actions, two resources, two results: the four pairs are not four results.
        assertEquals(List.of("s3:GetObject", "s3:PutObject"), strings(answer, CUSTOM + ".EvalActionName"));
        assertEquals(List.of("allowed", "implicitDeny"), strings(answer, CUSTOM + ".EvalDecision"));
        assertEquals(List.of(OBJECT_A, OBJECT_B, OBJECT_A, OBJECT_B),
                strings(answer, CUSTOM_RESOURCES + ".EvalResourceName"));
        assertEquals(List.of("allowed", "allowed", "implicitDeny", "implicitDeny"),
                strings(answer, CUSTOM_RESOURCES + ".EvalResourceDecision"));
    }

    /**
     * The template comes from the resource types AWS publishes for the action, which the generated
     * tables carry for IAM's own actions and for every other namespace they read.
     */
    @Test
    void theResourceNameAtTheTopIsTheActionsOwnTemplate() {
        XmlPath answer = answer(custom(allowAll("*"),
                List.of("iam:GetUser", "ssm:GetParameter"), List.of(OBJECT_A)));

        assertEquals(List.of(USER_TEMPLATE, PARAMETER_TEMPLATE),
                strings(answer, CUSTOM + ".EvalResourceName"));
        // The resource the call named is still answered for, under its own ARN.
        assertEquals(List.of(OBJECT_A, OBJECT_A), strings(answer, CUSTOM_RESOURCES + ".EvalResourceName"));
    }

    /** A policy action name is case-insensitive, and the template it names is read the same way. */
    @Test
    void anActionNamedInAnotherCaseStillNamesItsTemplate() {
        XmlPath answer = answer(custom(allowAll("*"),
                List.of("IAM:GetUser", "ssm:getparameter"), List.of(OBJECT_A)));

        assertEquals(List.of(USER_TEMPLATE, PARAMETER_TEMPLATE),
                strings(answer, CUSTOM + ".EvalResourceName"));
    }

    /**
     * {@code *} is the published value for an action with no ARN format, and it is what an action
     * whose published types carry no single template answers here: {@code ec2:RunInstances} acts on
     * eighteen types, {@code iam:ListUsers} on none at all, and {@code s3:GetObject} belongs to a
     * namespace no generated table reads, which the IAM page records as a difference.
     */
    @Test
    void anActionWithNoOnePublishedTemplateNamesTheWildcard() {
        XmlPath answer = answer(custom(allowAll("*"),
                List.of("ec2:RunInstances", "iam:ListUsers", "s3:GetObject"), List.of(OBJECT_A)));

        assertEquals(nCopies(3, "*"), strings(answer, CUSTOM + ".EvalResourceName"));
    }

    @Test
    void aPrincipalSimulationAnswersInTheSameShape() {
        String userName = userAllowed("s3:GetObject");

        XmlPath answer = answer(iam("SimulatePrincipalPolicy")
                .formParam("PolicySourceArn", "arn:aws:iam::" + ACCOUNT + ":user/" + userName)
                .formParam("ActionNames.member.1", "s3:GetObject")
                .formParam("ResourceArns.member.1", OBJECT_A)
                .formParam("ResourceArns.member.2", OBJECT_B));

        assertEquals(List.of("allowed"), strings(answer, PRINCIPAL + ".EvalDecision"));
        assertEquals(List.of(OBJECT_A, OBJECT_B), strings(answer, PRINCIPAL_RESOURCES + ".EvalResourceName"));
        assertEquals(List.of("allowed", "allowed"),
                strings(answer, PRINCIPAL_RESOURCES + ".EvalResourceDecision"));
    }

    private static RequestSpecification iam(String action) {
        return IamSimulationRequests.iam(CREDENTIAL, action);
    }

    private static RequestSpecification custom(String document, List<String> actionNames,
                                               List<String> resourceArns) {
        RequestSpecification simulation = iam("SimulateCustomPolicy")
                .formParam("PolicyInputList.member.1", document);
        for (int action = 0; action < actionNames.size(); action++) {
            simulation.formParam("ActionNames.member." + (action + 1), actionNames.get(action));
        }
        for (int resource = 0; resource < resourceArns.size(); resource++) {
            simulation.formParam("ResourceArns.member." + (resource + 1), resourceArns.get(resource));
        }
        return simulation;
    }

    /** A user holding one inline policy that allows the action, so the simulation answers allowed. */
    private String userAllowed(String actionName) {
        String userName = "simulate-shape-" + System.nanoTime();
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
            .when().post("/").then().statusCode(200));
        iam("PutUserPolicy").formParam("UserName", userName).formParam("PolicyName", "allow-it")
                .formParam("PolicyDocument", allowAll(actionName))
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "allow-it").when().post("/").then().statusCode(200));
        return userName;
    }

    private static String allowAll(String action) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"*\"}]}";
    }

    private static String allowOn(String action, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"" + resource + "\"}]}";
    }

    private static String allowAllDenying(String action, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}]}";
    }
}
