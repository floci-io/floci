package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.Session;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * With {@code iam.enforcement-enabled=true}, a role's trust policy acts as its resource-based policy.
 * The STS API Reference (AssumeRole) lets a user assume a role in its own account when the user is
 * added "as a principal directly in the role's trust policy": "When a resource-based policy grants
 * access to a principal in the same account, no additional identity-based policy is required." A
 * trust policy naming the account instead needs the user's identity-based policy too, and across
 * accounts "a principal needs an Allow in the identity policy and the resource-based policy".
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StsAssumeRoleTrustPolicyUserIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "222222222222";

    private static final String ROLE_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*"}]}""";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aTrustPolicyNamingTheUserLetsItAssumeTheRoleWithoutAnIdentityPolicy() {
        String suffix = suffix();
        String user = "trusted-user-" + suffix;
        UserCredentials caller = createUser(ACCOUNT, user);
        String roleArn = createRole(ACCOUNT, "trusts-user-" + suffix, trustNaming(userArn(ACCOUNT, user)));

        assumeRole(caller, roleArn).then().statusCode(200);
    }

    @Test
    void aTrustPolicyNamingTheAccountStillNeedsTheUsersIdentityPolicy() {
        String suffix = suffix();
        String user = "account-trusted-" + suffix;
        UserCredentials caller = createUser(ACCOUNT, user);
        String roleArn = createRole(ACCOUNT, "trusts-root-" + suffix,
                trustNaming("arn:aws:iam::" + ACCOUNT + ":root"));
        String bareAccountRoleArn = createRole(ACCOUNT, "trusts-account-" + suffix, trustNaming(ACCOUNT));

        assumeRole(caller, roleArn).then().statusCode(403).body(containsString("AccessDenied"));
        assumeRole(caller, bareAccountRoleArn).then().statusCode(403).body(containsString("AccessDenied"));

        allowAssumeRole(user, "*");
        assumeRole(caller, roleArn).then().statusCode(200);
        assumeRole(caller, bareAccountRoleArn).then().statusCode(200);
    }

    @Test
    void anExplicitDenyInTheUsersIdentityPolicyRefusesARoleThatTrustsTheUser() {
        String suffix = suffix();
        String user = "denied-user-" + suffix;
        UserCredentials caller = createUser(ACCOUNT, user);
        String roleArn = createRole(ACCOUNT, "trusts-denied-" + suffix, trustNaming(userArn(ACCOUNT, user)));
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "deny", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Action":"sts:AssumeRole","Resource":"%s"}]}""".formatted(roleArn));

        assumeRole(caller, roleArn).then().statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void aPermissionsBoundaryDoesNotCapATrustPolicyNamingTheUser() {
        // The IAM User Guide: within the same account, resource-based policies that grant
        // permissions to an IAM user ARN "are not limited by an implicit deny in an identity-based
        // policy or permissions boundary".
        String suffix = suffix();
        String user = "bounded-user-" + suffix;
        // Created first so that the user, which holds it as its boundary, is removed before it.
        String boundaryArn = createPolicy("list-only-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*"}]}""");
        UserCredentials caller = createUser(ACCOUNT, user);
        iam("PutUserPermissionsBoundary", "UserName", user, "PermissionsBoundary", boundaryArn);
        String roleArn = createRole(ACCOUNT, "trusts-bounded-" + suffix, trustNaming(userArn(ACCOUNT, user)));

        assumeRole(caller, roleArn).then().statusCode(200);
    }

    @Test
    void aTrustPolicyNamingAUserOfAnotherAccountStillNeedsThatUsersIdentityPolicy() {
        String suffix = suffix();
        String user = "cross-account-user-" + suffix;
        UserCredentials caller = createUser(OTHER_ACCOUNT, user);
        String roleArn = createRole(ACCOUNT, "trusts-other-account-user-" + suffix,
                trustNaming(userArn(OTHER_ACCOUNT, user)));

        assumeRole(caller, roleArn).then().statusCode(403).body(containsString("AccessDenied"));

        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(OTHER_ACCOUNT, "iam"), user, "assume", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"%s"}]}""".formatted(roleArn));
        assumeRole(caller, roleArn).then().statusCode(200);
    }

    @Test
    void aTrustPolicyNamingAnotherUserDoesNotLetTheCallerIn() {
        String suffix = suffix();
        UserCredentials caller = createUser(ACCOUNT, "untrusted-user-" + suffix);
        createUser(ACCOUNT, "other-user-" + suffix);
        String roleArn = createRole(ACCOUNT, "trusts-other-user-" + suffix,
                trustNaming(userArn(ACCOUNT, "other-user-" + suffix)));

        assumeRole(caller, roleArn).then().statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void aTrustPolicyNamingARoleLetsItsSessionsAssumeTheRoleWithoutAnIdentityPolicy() {
        // The IAM User Guide: "If an action is allowed by an identity-based policy, a
        // resource-based policy, or both, then AWS allows the action", and a role ARN is a
        // principal a resource-based policy grants to. The calling role's own policy allows
        // nothing on STS.
        String suffix = suffix();
        String callerRoleArn = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/team/",
                "caller-" + suffix, ROLE_POLICY);
        Session session = EnforcementFixtures.assumeRole(baseUri.getPort(), ACCOUNT, callerRoleArn);
        String trustsRole = createRole(ACCOUNT, "trusts-role-" + suffix, trustNaming(callerRoleArn));
        String trustsAccount = createRole(ACCOUNT, "trusts-root-of-role-" + suffix,
                trustNaming("arn:aws:iam::" + ACCOUNT + ":root"));

        assumeRole(session, trustsRole).then().statusCode(200);
        assumeRole(session, trustsAccount).then().statusCode(403).body(containsString("AccessDenied"));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String userArn(String account, String user) {
        return "arn:aws:iam::" + account + ":user/" + user;
    }

    private static String trustNaming(String principal) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"%s"},"Action":"sts:AssumeRole"}]}""".formatted(principal);
    }

    private UserCredentials createUser(String account, String user) {
        return EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(account, "iam"), user);
    }

    private String createRole(String account, String role, String trustPolicy) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), account, "/", role, trustPolicy,
                ROLE_POLICY);
    }

    private void allowAssumeRole(String user, String resource) {
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "assume", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"%s"}]}""".formatted(resource));
    }

    private String createPolicy(String name, String document) {
        String arn = iam("CreatePolicy", "PolicyName", name, "PolicyDocument", document)
                .then().extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> EnforcementFixtures.removePolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), arn));
        return arn;
    }

    private Response iam(String action, String... params) {
        RequestSpecification request = given().port(baseUri.getPort())
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(ACCOUNT, "iam"))
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        Response response = request.when().post("/");
        response.then().statusCode(200);
        return response;
    }

    private Response assumeRole(Session session, String roleArn) {
        return given().port(baseUri.getPort())
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(session.accessKeyId(), "sts"))
                .header("X-Amz-Security-Token", session.sessionToken())
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", EnforcementFixtures.SESSION_NAME)
                .when().post("/");
    }

    private Response assumeRole(UserCredentials caller, String roleArn) {
        return given().port(baseUri.getPort())
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(caller.accessKeyId(), "sts"))
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", EnforcementFixtures.SESSION_NAME)
                .when().post("/");
    }
}
