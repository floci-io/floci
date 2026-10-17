package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The account password policy's {@code AllowUsersToChangePassword}, which the guide describes as
 * an account-level grant: the option "gives users access to the iam:ChangePassword action for only
 * their user and to the iam:GetAccountPasswordPolicy action", and "does not attach a permissions
 * policy to each user. Rather, IAM applies the permissions at the account-level for all users".
 *
 * <p>Runs in an account of its own, like the other classes that set account-wide state: the
 * emulator is shared, and a password policy left visible to another class changes what that class
 * is authorized to do.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class PasswordSelfServiceGrantEnforcementIntegrationTest {

    private static final String ACCOUNT = "000000000913";
    private static final String OTHER_ACCOUNT = "000000000914";
    private static final String PASSWORD = "Sup3r$ecret!";
    private static final String NEW_PASSWORD = "An0ther$ecret!";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void theAccountFlagLetsAUserChangeItsOwnPassword() {
        allowUsersToChangePassword(true);
        String accessKeyId = userWithAConsolePassword();

        changePassword(accessKeyId, PASSWORD, NEW_PASSWORD).statusCode(200);
        as(accessKeyId, "GetAccountPasswordPolicy", Map.of()).statusCode(200);
    }

    /** The grant is not a policy on the user, so nothing that lists their policies shows it. */
    @Test
    void theGrantIsNotAttachedToTheUser() {
        allowUsersToChangePassword(true);
        String userName = "pw-grant-" + suffix();
        String accessKeyId = userWithAConsolePassword(userName);

        changePassword(accessKeyId, PASSWORD, NEW_PASSWORD).statusCode(200);
        assertEquals(List.of(), inlinePolicyNames(userName));
        assertEquals(List.of(), attachedPolicyArns(userName));
        assertEquals(List.of(), inlinePoliciesInAccountDetails(userName));

        // The same readings do report a policy the user really carries, so the empty ones mean an
        // absent grant rather than a path that names nothing.
        admin("PutUserPolicy", Map.of("UserName", userName, "PolicyName", "own",
                "PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"iam:GetUser","Resource":"*"}]}"""))
                .statusCode(200);
        assertEquals(List.of("own"), inlinePolicyNames(userName));
        assertEquals(List.of("own"), inlinePoliciesInAccountDetails(userName));
    }

    private List<String> inlinePolicyNames(String userName) {
        return admin("ListUserPolicies", Map.of("UserName", userName)).statusCode(200)
                .extract().xmlPath()
                .getList("ListUserPoliciesResponse.ListUserPoliciesResult.PolicyNames.member",
                        String.class);
    }

    private List<String> attachedPolicyArns(String userName) {
        return admin("ListAttachedUserPolicies", Map.of("UserName", userName)).statusCode(200)
                .extract().xmlPath()
                .getList("ListAttachedUserPoliciesResponse.ListAttachedUserPoliciesResult"
                        + ".AttachedPolicies.member.PolicyArn", String.class);
    }

    /** The policy names {@code GetAccountAuthorizationDetails} reports for one user. */
    private List<String> inlinePoliciesInAccountDetails(String userName) {
        return admin("GetAccountAuthorizationDetails", Map.of("Filter.member.1", "User"))
                .statusCode(200).extract().xmlPath()
                .getList("GetAccountAuthorizationDetailsResponse"
                        + ".GetAccountAuthorizationDetailsResult.UserDetailList.member"
                        + ".findAll { it.UserName == '" + userName + "' }"
                        + ".UserPolicyList.member.PolicyName", String.class);
    }

    /**
     * A last-accessed report lists "every AWS service that the resource could access using
     * permissions policies", so this one counts: a user carrying no policy at all reaches IAM
     * while the flag is set, and reaches nothing once it is cleared. The report is fixed when the
     * job runs, so each reading generates its own.
     */
    @Test
    void theGrantIsLastAccessedInformation() {
        allowUsersToChangePassword(true);
        String userName = "pw-advisor-" + suffix();
        String accessKeyId = userWithAConsolePassword(userName);
        // The grant is live for this user, so the report is reporting a permission they have.
        changePassword(accessKeyId, PASSWORD, NEW_PASSWORD).statusCode(200);

        assertEquals(List.of("iam"), servicesLastAccessed(userName));

        // The flag is what puts it there: cleared, the same user with the same policies, none,
        // reaches nothing.
        allowUsersToChangePassword(false);
        assertEquals(List.of(), servicesLastAccessed(userName));
    }

    /** The service namespaces a user's last-accessed report lists. */
    private List<String> servicesLastAccessed(String userName) {
        String jobId = admin("GenerateServiceLastAccessedDetails",
                Map.of("Arn", "arn:aws:iam::" + ACCOUNT + ":user/" + userName))
                .statusCode(200)
                .extract().path("GenerateServiceLastAccessedDetailsResponse"
                        + ".GenerateServiceLastAccessedDetailsResult.JobId");
        return admin("GetServiceLastAccessedDetails", Map.of("JobId", jobId)).statusCode(200)
                .extract().xmlPath()
                .getList("GetServiceLastAccessedDetailsResponse"
                        + ".GetServiceLastAccessedDetailsResult.ServicesLastAccessed.member"
                        + ".ServiceNamespace", String.class);
    }

    /**
     * And it leaves the caller nothing to supply: {@code GetContextKeysForPrincipalPolicy} answers
     * for "the identity-based policies attached to the specified entity, and any additional
     * policies that you provide", so the {@code ${aws:username}} in the grant's own resource is
     * not a key that answer names. A simulation populates it regardless.
     */
    @Test
    void theGrantReferencesNoContextKeyToSupply() {
        allowUsersToChangePassword(true);
        String userName = "pw-keys-" + suffix();
        userWithAConsolePassword(userName);

        assertEquals(List.of(), contextKeys(userName));

        // A key an attached policy does reference comes back, so the empty reading is the grant
        // staying out rather than the whole answer being empty.
        admin("PutUserPolicy", Map.of("UserName", userName, "PolicyName", "named-user",
                "PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Action":"iam:GetUser","Resource":"*",
                          "Condition":{"StringEquals":{"aws:username":"someone"}}}]}"""))
                .statusCode(200);
        assertEquals(List.of("aws:username"), contextKeys(userName));
    }

    /** The context keys {@code GetContextKeysForPrincipalPolicy} names for one user. */
    private List<String> contextKeys(String userName) {
        return admin("GetContextKeysForPrincipalPolicy",
                Map.of("PolicySourceArn", "arn:aws:iam::" + ACCOUNT + ":user/" + userName))
                .statusCode(200).extract().xmlPath()
                .getList("GetContextKeysForPrincipalPolicyResponse"
                        + ".GetContextKeysForPrincipalPolicyResult.ContextKeyNames.member",
                        String.class);
    }

    /** An ordinary identity grant, so an explicit Deny of the same action still refuses it. */
    @Test
    void anExplicitDenyStillRefusesTheCall() {
        allowUsersToChangePassword(true);
        String userName = "pw-denied-" + suffix();
        String accessKeyId = userWithAConsolePassword(userName);
        admin("PutUserPolicy", Map.of("UserName", userName, "PolicyName", "deny-change",
                "PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Deny","Action":"iam:ChangePassword","Resource":"*"}]}"""))
                .statusCode(200);

        changePassword(accessKeyId, PASSWORD, NEW_PASSWORD)
                .statusCode(403).body(containsString("AccessDenied"));
        // The policy is what refuses it: the same user reads the password policy, which that Deny
        // does not name.
        as(accessKeyId, "GetAccountPasswordPolicy", Map.of()).statusCode(200);
    }

    /** And a permissions boundary caps it, as it caps any identity-based permission. */
    @Test
    void aPermissionsBoundaryStillCapsIt() {
        allowUsersToChangePassword(true);
        String userName = "pw-bounded-" + suffix();
        String accessKeyId = userWithAConsolePassword(userName);
        String boundaryArn = admin("CreatePolicy", Map.of(
                "PolicyName", "pw-boundary-" + suffix(),
                "PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"iam:GetAccountPasswordPolicy",
                           "Resource":"*"}]}"""))
                .statusCode(200)
                .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> EnforcementFixtures.removePolicy(port(),
                EnforcementFixtures.auth(ACCOUNT, "iam"), boundaryArn));
        admin("PutUserPermissionsBoundary",
                Map.of("UserName", userName, "PermissionsBoundary", boundaryArn)).statusCode(200);
        cleanup.register(() -> admin("DeleteUserPermissionsBoundary",
                Map.of("UserName", userName)).statusCode(200));

        changePassword(accessKeyId, PASSWORD, NEW_PASSWORD)
                .statusCode(403).body(containsString("AccessDenied"));
        // The boundary allows the other half of the grant, so it is the boundary narrowing the
        // grant rather than the grant having gone missing.
        as(accessKeyId, "GetAccountPasswordPolicy", Map.of()).statusCode(200);
    }

    /** Two actions, and the user still has none of the others it might want for a password. */
    @Test
    void theGrantReachesNothingElse() {
        allowUsersToChangePassword(true);
        String userName = "pw-narrow-" + suffix();
        String accessKeyId = userWithAConsolePassword(userName);

        as(accessKeyId, "GetUser", Map.of("UserName", userName))
                .statusCode(403).body(containsString("AccessDenied"));
        as(accessKeyId, "UpdateLoginProfile",
                Map.of("UserName", userName, "Password", NEW_PASSWORD))
                .statusCode(403).body(containsString("AccessDenied"));
        as(accessKeyId, "CreateUser", Map.of("UserName", "pw-other-" + suffix()))
                .statusCode(403).body(containsString("AccessDenied"));

        // The one call the grant does carry, so this test fails if the grant went missing rather
        // than merely staying narrow.
        changePassword(accessKeyId, PASSWORD, NEW_PASSWORD).statusCode(200);
    }

    /** The flag is what decides it, read both ways by one user whose policies never change. */
    @Test
    void theFlagIsWhatDecidesIt() {
        allowUsersToChangePassword(false);
        String accessKeyId = userWithAConsolePassword();

        changePassword(accessKeyId, PASSWORD, NEW_PASSWORD)
                .statusCode(403).body(containsString("AccessDenied"));
        as(accessKeyId, "GetAccountPasswordPolicy", Map.of())
                .statusCode(403).body(containsString("AccessDenied"));

        allowUsersToChangePassword(true);
        as(accessKeyId, "GetAccountPasswordPolicy", Map.of()).statusCode(200);
        changePassword(accessKeyId, PASSWORD, NEW_PASSWORD).statusCode(200);
    }

    /**
     * "For only their user": the grant's resource is {@code user/${aws:username}}, so it resolves
     * to the caller and names nobody else. Nothing is passed for that key: the simulator populates
     * the principal keys itself, as AWS's does, so the simulation reads the way the request path
     * reads.
     */
    @Test
    void theGrantNamesOnlyTheCallersOwnUser() {
        allowUsersToChangePassword(true);
        String mine = "pw-mine-" + suffix();
        String theirs = "pw-theirs-" + suffix();
        userWithAConsolePassword(mine);
        userWithAConsolePassword(theirs);

        simulate(mine, "arn:aws:iam::" + ACCOUNT + ":user/" + mine).statusCode(200)
                .body(decisionPath(), equalTo("allowed"));
        simulate(mine, "arn:aws:iam::" + ACCOUNT + ":user/" + theirs).statusCode(200)
                .body(decisionPath(), equalTo("implicitDeny"));
    }

    /**
     * The flag is the account's own, so it says nothing about a user of another account. Only a
     * {@code PolicySourceArn} can name one, and naming one together with a resource ARN minted in
     * this account would otherwise have the two halves meet: this account's grant resolving
     * {@code ${aws:username}} to the other account's user.
     */
    @Test
    void theFlagDoesNotReachAnotherAccountsUser() {
        allowUsersToChangePassword(true);
        String userName = "pw-elsewhere-" + suffix();
        otherAccountUser(userName);

        simulateSource("arn:aws:iam::" + OTHER_ACCOUNT + ":user/" + userName,
                "arn:aws:iam::" + ACCOUNT + ":user/" + userName)
                .statusCode(200)
                .body(decisionPath(), equalTo("implicitDeny"));
    }

    /**
     * The other half of that: the flag that answers for a user is their own account's. The ARN
     * resolves them where it says they are, so the grant is that account's, naming that account's
     * user ARNs, and a simulation run from this account reads it as that account would.
     */
    @Test
    void anotherAccountsUserCarriesItsOwnAccountsFlag() {
        String userName = "pw-neighbour-" + suffix();
        otherAccountUser(userName);
        allowUsersToChangePassword(OTHER_ACCOUNT, true);

        String source = "arn:aws:iam::" + OTHER_ACCOUNT + ":user/" + userName;
        simulateSource(source, "arn:aws:iam::" + OTHER_ACCOUNT + ":user/" + userName)
                .statusCode(200)
                .body(decisionPath(), equalTo("allowed"));
        // Their grant names their own account's ARNs and nothing in this one, which set no flag.
        simulateSource(source, "arn:aws:iam::" + ACCOUNT + ":user/" + userName)
                .statusCode(200)
                .body(decisionPath(), equalTo("implicitDeny"));
    }

    /** A user of the other account, with no policies and no console password. */
    private void otherAccountUser(String userName) {
        String otherAuth = EnforcementFixtures.auth(OTHER_ACCOUNT, "iam");
        cleanup.register(() -> EnforcementFixtures.removeUser(port(), otherAuth, userName));
        iam(OTHER_ACCOUNT, "CreateUser", Map.of("UserName", userName)).statusCode(200);
    }

    /**
     * The keys the simulation fills in do not displace the ones the call passed, whichever case it
     * spells them in: condition key names are compared without regard to case everywhere else, so
     * a lower-cased {@code aws:principaltype} is the same key as the one populated here.
     */
    @Test
    void aSuppliedContextValueWinsWhateverCaseItIsSpelledIn() {
        allowUsersToChangePassword(true);
        String userName = "pw-typed-" + suffix();
        userWithAConsolePassword(userName);
        admin("PutUserPolicy", Map.of("UserName", userName, "PolicyName", "as-a-role",
                "PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Action":"iam:GetUser","Resource":"*",
                          "Condition":{"StringEquals":{"aws:PrincipalType":"AssumedRole"}}}]}"""))
                .statusCode(200);

        for (String spelling : List.of("aws:PrincipalType", "aws:principaltype")) {
            admin("SimulatePrincipalPolicy", Map.of(
                    "PolicySourceArn", "arn:aws:iam::" + ACCOUNT + ":user/" + userName,
                    "ActionNames.member.1", "iam:GetUser",
                    "ResourceArns.member.1", "*",
                    "ContextEntries.member.1.ContextKeyName", spelling,
                    "ContextEntries.member.1.ContextKeyValues.member.1", "AssumedRole"))
                    .statusCode(200)
                    .body(decisionPath("iam:GetUser"), equalTo("allowed"));
        }
    }

    /**
     * A {@code PolicySourceArn} that names a user without being an ARN this emulator can parse is
     * resolved by its trailing name, as it was before the principal keys were populated, so the
     * account they carry comes from the identity that resolved rather than from the text of the
     * call.
     */
    @Test
    void aPolicySourceArnItCannotParseStillSimulates() {
        allowUsersToChangePassword(true);
        String userName = "pw-oddarn-" + suffix();
        userWithAConsolePassword(userName);

        admin("SimulatePrincipalPolicy", Map.of(
                "PolicySourceArn", "arn:aws:iam:user/" + userName,
                "ActionNames.member.1", "iam:ChangePassword",
                "ResourceArns.member.1", "arn:aws:iam::" + ACCOUNT + ":user/" + userName))
                .statusCode(200)
                .body(decisionPath(), equalTo("allowed"));
    }

    private static String decisionPath() {
        return decisionPath("iam:ChangePassword");
    }

    private static String decisionPath(String action) {
        return "SimulatePrincipalPolicyResponse.SimulatePrincipalPolicyResult.EvaluationResults"
                + ".member.find { it.EvalActionName == '" + action + "' }.EvalDecision";
    }

    private ValidatableResponse simulate(String userName, String resourceArn) {
        return simulateSource("arn:aws:iam::" + ACCOUNT + ":user/" + userName, resourceArn);
    }

    private ValidatableResponse simulateSource(String policySourceArn, String resourceArn) {
        return admin("SimulatePrincipalPolicy", Map.of(
                "PolicySourceArn", policySourceArn,
                "ActionNames.member.1", "iam:ChangePassword",
                "ResourceArns.member.1", resourceArn));
    }

    private void allowUsersToChangePassword(boolean allowed) {
        allowUsersToChangePassword(ACCOUNT, allowed);
    }

    private void allowUsersToChangePassword(String account, boolean allowed) {
        iam(account, "UpdateAccountPasswordPolicy",
                Map.of("AllowUsersToChangePassword", Boolean.toString(allowed))).statusCode(200);
        cleanup.register(() -> iam(account, "DeleteAccountPasswordPolicy", Map.of())
                .statusCode(200));
    }

    private String userWithAConsolePassword() {
        return userWithAConsolePassword("pw-self-" + suffix());
    }

    /** An IAM user holding a console password and an access key, and no policies at all. */
    private String userWithAConsolePassword(String userName) {
        String auth = EnforcementFixtures.auth(ACCOUNT, "iam");
        cleanup.register(() -> EnforcementFixtures.removeUser(port(), auth, userName));
        admin("CreateUser", Map.of("UserName", userName)).statusCode(200);
        admin("CreateLoginProfile", Map.of("UserName", userName, "Password", PASSWORD))
                .statusCode(200);
        cleanup.register(() -> admin("DeleteLoginProfile", Map.of("UserName", userName))
                .statusCode(200));
        return admin("CreateAccessKey", Map.of("UserName", userName)).statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey"
                        + ".AccessKeyId");
    }

    private ValidatableResponse changePassword(String accessKeyId, String oldPassword,
                                                String newPassword) {
        return as(accessKeyId, "ChangePassword",
                Map.of("OldPassword", oldPassword, "NewPassword", newPassword));
    }

    private ValidatableResponse admin(String action, Map<String, String> params) {
        return iam(ACCOUNT, action, params);
    }

    private ValidatableResponse as(String accessKeyId, String action, Map<String, String> params) {
        return iam(accessKeyId, action, params);
    }

    private ValidatableResponse iam(String accessKeyId, String action,
                                     Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", EnforcementFixtures.auth(accessKeyId, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private int port() {
        return baseUri.getPort();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
