package io.floci.aws.services.iam;

import io.floci.aws.testing.IamSimulationRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.floci.aws.testing.IamSimulationRequests.LEVELS;
import static java.util.Collections.nCopies;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two simulation members both simulators used to answer as though they had not been sent.
 *
 * <p>{@code OrderedOrganizationPolicyInputList} is "an ordered list of service control policies
 * (SCPs) to include in the simulation. Each element represents one level of an Organizations
 * hierarchy, from the organization root to the account", evaluated "in the order that you provide,
 * consistent with how Organizations enforces SCPs", which is what the levels do here: an action no
 * level allows is denied, and one every level allows survives the walk. A simulation gates on the
 * levels it was handed and on nothing else, the way the console includes SCPs only when "AWS
 * Organizations SCPs" is ticked.
 *
 * <p>{@code ResourceHandlingOption} names the type of simulation, and by naming it "you enable the
 * policy simulator to enforce the presence of the required resources to ensure reliable simulation
 * results". So a scenario whose resources are not all named is refused rather than simulated, and
 * the request that names no scenario is left alone.
 *
 * <p>Both are inputs, so both are answered where this package answers an input: ahead of the
 * principal lookup, which cannot answer for a request the operation refuses.
 */
@QuarkusTest
class IamSimulateOrganizationAndScenarioIntegrationTest {

    private static final String CREDENTIAL = "test";

    private static final String ACCOUNT = "000000000000";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    private static final String CUSTOM_DECISION = "SimulateCustomPolicyResponse"
            + ".SimulateCustomPolicyResult.EvaluationResults.member.EvalDecision";

    private static final String PRINCIPAL_DECISION = "SimulatePrincipalPolicyResponse"
            + ".SimulatePrincipalPolicyResult.EvaluationResults.member.EvalDecision";

    /** Each resource's own answer, which is where a result carries the resources a call named. */
    private static final String CUSTOM_RESOURCE_DECISIONS = "SimulateCustomPolicyResponse"
            + ".SimulateCustomPolicyResult.EvaluationResults.member.ResourceSpecificResults"
            + ".member.EvalResourceDecision";

    private static final String INPUT = "PolicyInputList.member.1";

    /** The EC2 resources the published scenarios require, as ARNs a simulation may name. */
    private static final String INSTANCE = "arn:aws:ec2:us-east-1:000000000000:instance/i-1";
    private static final String IMAGE = "arn:aws:ec2:us-east-1:000000000000:image/ami-1";
    private static final String SECURITY_GROUP = "arn:aws:ec2:us-east-1:000000000000:security-group/sg-1";
    private static final String NETWORK_INTERFACE = "arn:aws:ec2:us-east-1:000000000000:network-interface/eni-1";
    private static final String VOLUME = "arn:aws:ec2:us-east-1:000000000000:volume/vol-1";

    /** The user the principal-side cases simulate outlives the class otherwise: the emulator is shared. */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void anActionNoOrganizationLevelAllowsIsDeniedByTheLevelsTheCallHandedIn() {
        // The input policy allows it, so what refuses it is the hierarchy above it.
        RequestSpecification simulation = custom("s3:GetObject")
                .formParam(LEVELS + "1.ServiceControlPolicyInputList.member.1", allow("s3:PutObject"));

        assertEquals("implicitDeny", decision(simulation, CUSTOM_DECISION));
    }

    @Test
    void anActionEveryLevelAllowsSurvivesTheWalk() {
        RequestSpecification simulation = custom("s3:GetObject")
                .formParam(LEVELS + "1.ServiceControlPolicyInputList.member.1", allow("s3:*"))
                .formParam(LEVELS + "2.ServiceControlPolicyInputList.member.1", allow("s3:GetObject"));

        assertEquals("allowed", decision(simulation, CUSTOM_DECISION));
    }

    @Test
    void aLevelThatDeniesTakesTheActionWhateverTheLevelsBelowItSay() {
        RequestSpecification simulation = custom("s3:GetObject")
                .formParam(LEVELS + "1.ServiceControlPolicyInputList.member.1", deny("s3:GetObject"))
                .formParam(LEVELS + "2.ServiceControlPolicyInputList.member.1", allow("s3:*"))
                .formParam(LEVELS + "3.ServiceControlPolicyInputList.member.1", allow("s3:GetObject"));

        assertEquals("explicitDeny", decision(simulation, CUSTOM_DECISION));
    }

    @Test
    void aLevelCarryingNoPolicyDocumentIsRefusedLikeAnyInputDocument() {
        custom("s3:GetObject")
                .formParam(LEVELS + "1.ServiceControlPolicyInputList.member.1", "{not-a-policy")
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"));
    }

    @Test
    void theLevelsGateAPrincipalSimulationTheSameWay() {
        String userName = createUserAllowed("s3:GetObject");

        assertEquals("explicitDeny", decision(simulatePrincipal(userName, "s3:GetObject")
                .formParam(LEVELS + "1.ServiceControlPolicyInputList.member.1", deny("s3:GetObject")),
                PRINCIPAL_DECISION));
        // The same principal, asked without the levels: the deny came from them and nowhere else.
        assertEquals("allowed", decision(simulatePrincipal(userName, "s3:GetObject"), PRINCIPAL_DECISION));
    }

    @Test
    void aScenarioIsHeldToTheResourcesItRequires() {
        // Instance, image and security group named, network interface left out.
        custom("ec2:StartInstances")
                .formParam("ResourceArns.member.1", INSTANCE)
                .formParam("ResourceArns.member.2", IMAGE)
                .formParam("ResourceArns.member.3", SECURITY_GROUP)
                .formParam("ResourceHandlingOption", "EC2-VPC-InstanceStore")
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"))
                .body(MESSAGE, containsString("network-interface"));
    }

    @Test
    void aScenarioIsHeldToItsResourcesOnAPrincipalSimulationToo() {
        String userName = createUserAllowed("ec2:StartInstances");

        simulatePrincipal(userName, "ec2:StartInstances")
                .formParam("ResourceHandlingOption", "EC2-VPC-EBS")
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"));
    }

    @Test
    void aScenarioWhoseResourcesAreAllNamedSimulates() {
        RequestSpecification simulation = custom("ec2:StartInstances")
                .formParam("ResourceArns.member.1", INSTANCE)
                .formParam("ResourceArns.member.2", IMAGE)
                .formParam("ResourceArns.member.3", SECURITY_GROUP)
                .formParam("ResourceArns.member.4", NETWORK_INTERFACE)
                .formParam("ResourceArns.member.5", VOLUME)
                .formParam("ResourceHandlingOption", "EC2-VPC-EBS");

        // The one result the action answers with, as every simulation answers now, carrying each
        // named resource's own decision under it, and the scenario is complete.
        assertEquals(List.of("allowed"), decisions(simulation, CUSTOM_DECISION));
        assertEquals(nCopies(5, "allowed"), decisions(simulation, CUSTOM_RESOURCE_DECISIONS));
    }

    @Test
    void aValueNamingNoScenarioIsRefused() {
        custom("s3:GetObject")
                .formParam("ResourceHandlingOption", "EC2-Classic")
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"));
    }

    @Test
    void aSimulationNamingNoScenarioIsLeftAlone() {
        assertEquals("allowed", decision(custom("s3:GetObject"), CUSTOM_DECISION));
    }

    /**
     * Omitting the member is what is left alone, not emptying it. A member the request carries is
     * a value rather than a member it left out, so a value of nothing but space names no published
     * scenario and is refused like any other value that names none. The value carried empty never
     * reaches the scenario at all: the member's declared minimum of one answers it, in the layer
     * that runs ahead of the operation.
     */
    @Test
    void aScenarioValueOfNothingButSpaceNamesNoScenario() {
        custom("s3:GetObject")
                .formParam("ResourceHandlingOption", " ")
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"));

        custom("s3:GetObject")
                .formParam("ResourceHandlingOption", "")
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("ValidationError"))
                .body(MESSAGE, containsString("length greater than or equal to 1"));
    }

    /**
     * Both members are read where the handed-in documents are read, ahead of the principal lookup,
     * so a request that carries one of them broken and names nobody is answered for the input it
     * supplied rather than for the entity it did not find.
     */
    @Test
    void anUnknownPrincipalDoesNotAnswerForAnInputTheOperationRefuses() {
        String absent = "simulate-levels-absent-" + System.nanoTime();

        // Nothing else wrong with the request: the lookup is what answers it.
        simulatePrincipal(absent, "s3:GetObject")
            .when().post("/").then().statusCode(404)
                .body(CODE, equalTo("NoSuchEntity"));

        simulatePrincipal(absent, "s3:GetObject")
                .formParam(LEVELS + "1.ServiceControlPolicyInputList.member.1", "{not-a-policy")
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"));

        simulatePrincipal(absent, "s3:GetObject")
                .formParam("ResourceHandlingOption", "EC2-Classic")
            .when().post("/").then().statusCode(400)
                .body(CODE, equalTo("InvalidInput"));
    }

    private static RequestSpecification iam(String action) {
        return IamSimulationRequests.iam(CREDENTIAL, action);
    }

    private static RequestSpecification custom(String actionName) {
        return iam("SimulateCustomPolicy")
                .formParam(INPUT, allow(actionName))
                .formParam("ActionNames.member.1", actionName);
    }

    private static RequestSpecification simulatePrincipal(String userName, String actionName) {
        return iam("SimulatePrincipalPolicy")
                .formParam("PolicySourceArn", "arn:aws:iam::" + ACCOUNT + ":user/" + userName)
                .formParam("ActionNames.member.1", actionName)
                .formParam("ResourceArns.member.1", "*");
    }

    /** A user holding one inline policy that allows the action, so only the levels can refuse it. */
    private String createUserAllowed(String actionName) {
        String userName = "simulate-levels-" + System.nanoTime();
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
            .when().post("/").then().statusCode(200));
        iam("PutUserPolicy").formParam("UserName", userName).formParam("PolicyName", "allow-it")
                .formParam("PolicyDocument", allow(actionName))
            .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "allow-it").when().post("/").then().statusCode(200));
        return userName;
    }

    private static String decision(RequestSpecification simulation, String path) {
        List<String> decisions = decisions(simulation, path);
        assertEquals(1, decisions.size(), "one action answers once");
        return decisions.getFirst();
    }

    private static List<String> decisions(RequestSpecification simulation, String path) {
        return simulation.when().post("/").then().statusCode(200)
                .extract().xmlPath().getList(path);
    }

    private static String allow(String action) {
        return document("Allow", action);
    }

    private static String deny(String action) {
        return document("Deny", action);
    }

    private static String document(String effect, String action) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"" + effect + "\","
                + "\"Action\":\"" + action + "\",\"Resource\":\"*\"}]}";
    }
}
