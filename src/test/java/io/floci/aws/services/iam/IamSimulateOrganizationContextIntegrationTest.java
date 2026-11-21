package io.floci.aws.services.iam;

import io.floci.aws.testing.IamTestPolicies;
import io.floci.aws.testing.OrganizationsFixture;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.floci.aws.testing.IamSimulationRequests.iam;
import static io.floci.aws.testing.IamSimulationRequests.level;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The organization context a simulation populates for the principal it was given.
 *
 * <p>The simulator fills these keys in rather than asking for them: the guide lists
 * {@code aws:PrincipalOrgID}, {@code aws:PrincipalOrgMasterAccountId} and
 * {@code aws:PrincipalOrgPaths} as the organization context it populates, and says that "because
 * these keys are populated for you, SCPs and other policies that use principal or organization
 * conditions are evaluated correctly". So an SCP handed to a simulation and keyed on the
 * organization it guards decides the simulation, where a key no value reaches decides nothing.
 *
 * <p>The path is the one place the two published shapes differ. An account's entity path is built
 * "using the IDs of the organization, root, and all OUs in the path down to and including the OU"
 * and ends with the account's own id, while the key's own condition compares
 * {@code o-a1b2c3d4e5/r-ab12/ou-ab12-11111111/ou-ab12-22222222/} with {@code StringEquals} and
 * "returns true for principals in accounts that are attached directly to the ou-ab12-22222222
 * OU". A value carrying the account id could never equal that, so what a principal carries stops
 * at its parent, which is what these cases hold it to.
 */
@QuarkusTest
class IamSimulateOrganizationContextIntegrationTest {

    /** Its own management account, so this suite never contends with another for an organization. */
    private static final String MANAGEMENT = "494949494949";
    /** An account in no organization at all, for the keys that are then absent. */
    private static final String OUTSIDE = "262626262626";

    private static final String ACTION = "s3:GetObject";
    private static final String ENTRIES = "ContextEntries.member.1.";

    private static final String PRINCIPAL_DECISION = "SimulatePrincipalPolicyResponse"
            + ".SimulatePrincipalPolicyResult.EvaluationResults.member.EvalDecision";
    private static final String CUSTOM_DECISION = "SimulateCustomPolicyResponse"
            + ".SimulateCustomPolicyResult.EvaluationResults.member.EvalDecision";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final OrganizationsFixture organizations = new OrganizationsFixture();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void removeTheOrganization() {
        organizations.cleanup();
    }

    /** One organization, one OU under its root, one member account inside the OU. */
    private record Hierarchy(String organizationId, String rootId, String unitId, String accountId) {

        /** What the account's principals carry: the path down to the OU holding the account. */
        String path() {
            return organizationId + "/" + rootId + "/" + unitId + "/";
        }

        /** The account's own entity path, which goes one segment further. */
        String entityPath() {
            return path() + accountId + "/";
        }
    }

    private Hierarchy hierarchy() {
        organizations.request(MANAGEMENT, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        String organizationId = organizations.request(MANAGEMENT, "DescribeOrganization", "{}")
                .post("/").then().statusCode(200).extract().jsonPath().getString("Organization.Id");
        String rootId = organizations.request(MANAGEMENT, "ListRoots", "{}")
                .post("/").then().statusCode(200).extract().jsonPath().getString("Roots[0].Id");
        String unitId = organizations.createOu(MANAGEMENT, rootId, "workloads");
        String accountId = organizations.createAccount(MANAGEMENT, "member@example.com", "member",
                unitId, rootId);
        return new Hierarchy(organizationId, rootId, unitId, accountId);
    }

    @Test
    void anScpKeyedOnTheOrganizationIdSeesTheOrganizationTheAccountIsIn() {
        Hierarchy organization = hierarchy();
        String userName = userAllowedIn(organization.accountId());

        assertEquals("allowed", decision(simulateUser(organization.accountId(), userName)
                .formParam(level(1), allowIf("StringEquals", "aws:PrincipalOrgID",
                        organization.organizationId()))));

        // The same level naming another organization: the key carries a value, and it is not this.
        assertEquals("implicitDeny", decision(simulateUser(organization.accountId(), userName)
                .formParam(level(1), allowIf("StringEquals", "aws:PrincipalOrgID", "o-notthisone"))));
    }

    @Test
    void theManagementAccountIsNamedForEveryAccountInTheOrganization() {
        Hierarchy organization = hierarchy();
        String userName = userAllowedIn(organization.accountId());

        assertEquals("allowed", decision(simulateUser(organization.accountId(), userName)
                .formParam(level(1), allowIf("StringEquals", "aws:PrincipalOrgMasterAccountId",
                        MANAGEMENT))));
    }

    @Test
    void thePathAPrincipalCarriesStopsAtTheOrganizationalUnitHoldingItsAccount() {
        Hierarchy organization = hierarchy();
        String userName = userAllowedIn(organization.accountId());

        assertEquals("allowed", decision(simulateUser(organization.accountId(), userName)
                .formParam(level(1), allowIf("ForAnyValue:StringEquals", "aws:PrincipalOrgPaths",
                        organization.path()))));

        // The account's entity path goes one segment further, and is not what a condition matches.
        assertEquals("implicitDeny", decision(simulateUser(organization.accountId(), userName)
                .formParam(level(1), allowIf("ForAnyValue:StringEquals", "aws:PrincipalOrgPaths",
                        organization.entityPath()))));

        // The wildcard form AWS documents for "every principal in the organization".
        assertEquals("allowed", decision(simulateUser(organization.accountId(), userName)
                .formParam(level(1), allowIf("ForAnyValue:StringLike", "aws:PrincipalOrgPaths",
                        organization.organizationId() + "/*"))));
    }

    @Test
    void theManagementAccountSitsAtTheRootOfItsOwnOrganization() {
        Hierarchy organization = hierarchy();
        String userName = userAllowedIn(MANAGEMENT);

        assertEquals("allowed", decision(simulateUser(MANAGEMENT, userName)
                .formParam(level(1), allowIf("ForAnyValue:StringEquals", "aws:PrincipalOrgPaths",
                        organization.organizationId() + "/" + organization.rootId() + "/"))));
    }

    @Test
    void anAccountInNoOrganizationCarriesNoneOfTheKeys() {
        String userName = userAllowedIn(OUTSIDE);

        assertEquals("implicitDeny", decision(simulateUser(OUTSIDE, userName)
                .formParam(level(1), allowIf("StringEquals", "aws:PrincipalOrgID", "o-anything"))));

        // Nothing else is refusing it: the same level without the condition allows the action.
        assertEquals("allowed", decision(simulateUser(OUTSIDE, userName)
                .formParam(level(1), allow())));
    }

    @Test
    void aValueTheCallSuppliedWinsOverThePopulatedOne() {
        Hierarchy organization = hierarchy();
        String userName = userAllowedIn(organization.accountId());

        // The level names the organization the account is really in, and the call says otherwise.
        assertEquals("implicitDeny", decision(simulateUser(organization.accountId(), userName)
                .formParam(level(1), allowIf("StringEquals", "aws:PrincipalOrgID",
                        organization.organizationId()))
                .formParam(ENTRIES + "ContextKeyName", "aws:PrincipalOrgID")
                .formParam(ENTRIES + "ContextKeyValues.member.1", "o-suppliedinstead")));

        // The other way round: the supplied value is the one the condition is held against.
        assertEquals("allowed", decision(simulateUser(organization.accountId(), userName)
                .formParam(level(1), allowIf("StringEquals", "aws:PrincipalOrgID",
                        "o-suppliedinstead"))
                .formParam(ENTRIES + "ContextKeyName", "aws:PrincipalOrgID")
                .formParam(ENTRIES + "ContextKeyValues.member.1", "o-suppliedinstead")));
    }

    @Test
    void theKeysReachTheIdentityPolicyAndNotOnlyTheLevels() {
        Hierarchy organization = hierarchy();
        // The user's own policy allows the action only inside this organization, and no level is
        // handed in at all, so what the condition reads is the populated request context.
        String userName = user(organization.accountId(),
                allowIf("StringEquals", "aws:PrincipalOrgID", organization.organizationId()));

        assertEquals("allowed", decision(simulateUser(organization.accountId(), userName)));
    }

    @Test
    void aRoleSourceCarriesTheAccountAndOrganizationItsArnNames() {
        Hierarchy organization = hierarchy();
        // Both keys describe the account rather than the credential, so a role source answers for
        // them even though it resolves no session of its own.
        String roleName = roleAllowedIf(organization.accountId(),
                allowIf("StringEquals", "aws:PrincipalAccount", organization.accountId()));

        assertEquals("allowed", decision(simulateRole(organization.accountId(), roleName)));

        assertEquals("allowed", decision(simulateRole(organization.accountId(), roleName)
                .formParam(level(1), allowIf("StringEquals", "aws:PrincipalOrgID",
                        organization.organizationId()))));
    }

    @Test
    void aCustomSimulationNamesNoPrincipalSoNothingIsPopulatedForIt() {
        Hierarchy organization = hierarchy();

        RequestSpecification simulation = iam(organization.accountId(), "SimulateCustomPolicy")
                .formParam("PolicyInputList.member.1", allow())
                .formParam("ActionNames.member.1", ACTION)
                .formParam("ResourceArns.member.1", "*")
                .formParam(level(1), allowIf("StringEquals", "aws:PrincipalOrgID",
                        organization.organizationId()));

        assertEquals("implicitDeny", decisionAt(simulation, CUSTOM_DECISION));
    }

    private static RequestSpecification simulateUser(String accountId, String userName) {
        return simulate(accountId, "arn:aws:iam::" + accountId + ":user/" + userName);
    }

    private static RequestSpecification simulateRole(String accountId, String roleName) {
        return simulate(accountId, "arn:aws:iam::" + accountId + ":role/" + roleName);
    }

    private static RequestSpecification simulate(String accountId, String policySourceArn) {
        return iam(accountId, "SimulatePrincipalPolicy")
                .formParam("PolicySourceArn", policySourceArn)
                .formParam("ActionNames.member.1", ACTION)
                .formParam("ResourceArns.member.1", "*");
    }

    /** A user whose one inline policy allows the action outright. */
    private String userAllowedIn(String accountId) {
        return user(accountId, allow());
    }

    private String user(String accountId, String document) {
        String userName = "simulate-org-" + System.nanoTime();
        iam(accountId, "CreateUser").formParam("UserName", userName)
                .post("/").then().statusCode(200);
        cleanup.register(() -> iam(accountId, "DeleteUser").formParam("UserName", userName)
                .post("/").then().statusCode(200));
        iam(accountId, "PutUserPolicy").formParam("UserName", userName)
                .formParam("PolicyName", "allow-it").formParam("PolicyDocument", document)
                .post("/").then().statusCode(200);
        cleanup.register(() -> iam(accountId, "DeleteUserPolicy").formParam("UserName", userName)
                .formParam("PolicyName", "allow-it").post("/").then().statusCode(200));
        return userName;
    }

    private String roleAllowedIf(String accountId, String document) {
        String roleName = "simulate-org-role-" + System.nanoTime();
        iam(accountId, "CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", IamTestPolicies.TRUST_POLICY)
                .post("/").then().statusCode(200);
        cleanup.register(() -> iam(accountId, "DeleteRole").formParam("RoleName", roleName)
                .post("/").then().statusCode(200));
        iam(accountId, "PutRolePolicy").formParam("RoleName", roleName)
                .formParam("PolicyName", "allow-it").formParam("PolicyDocument", document)
                .post("/").then().statusCode(200);
        cleanup.register(() -> iam(accountId, "DeleteRolePolicy").formParam("RoleName", roleName)
                .formParam("PolicyName", "allow-it").post("/").then().statusCode(200));
        return roleName;
    }

    private static String decision(RequestSpecification simulation) {
        return decisionAt(simulation, PRINCIPAL_DECISION);
    }

    private static String decisionAt(RequestSpecification simulation, String path) {
        List<String> decisions = simulation.post("/").then().statusCode(200)
                .extract().xmlPath().getList(path);
        assertEquals(1, decisions.size(), "one action answers once");
        return decisions.getFirst();
    }

    private static String allow() {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + ACTION + "\",\"Resource\":\"*\"}]}";
    }

    private static String allowIf(String operator, String key, String value) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + ACTION + "\",\"Resource\":\"*\",\"Condition\":{\"" + operator + "\":{\"" + key
                + "\":\"" + value + "\"}}}]}";
    }
}
