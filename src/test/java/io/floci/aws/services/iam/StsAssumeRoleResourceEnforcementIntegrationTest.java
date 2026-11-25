package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * With {@code iam.enforcement-enabled=true}, {@code sts:AssumeRole} is authorized against the role it
 * names, in the account its ARN names, so an identity policy can allow assuming one role and not another
 * (IAM User Guide, "Granting a user permissions to switch roles": the policy's Resource is the role's ARN),
 * and {@code sts:GetFederationToken} against the federated user its name names. The role's tags are its
 * {@code aws:ResourceTag} and {@code iam:ResourceTag} keys, in the caller's policy and in the role's own trust
 * policy.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StsAssumeRoleResourceEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "222222222222";
    private static final String LIST_USERS = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*"}]}""";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aPolicyNamingOneRoleAllowsAssumingThatRoleOnly() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String allowedArn = createRole("assume-allowed-" + suffix);
        String otherArn = createRole("assume-other-" + suffix);
        String user = "assume-caller-" + suffix;
        String iamAuth = auth(ACCOUNT, "iam");
        UserCredentials caller = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), iamAuth, user, "assume", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"%s"}]}""".formatted(allowedArn));

        assumeRole(caller, allowedArn).then().statusCode(200);
        assumeRole(caller, otherArn).then().statusCode(403);
    }

    @Test
    void aPolicyNamingAnotherAccountsRoleAllowsAssumingThatRole() {
        // STS assumes the role in the account its ARN names, so the policy naming that role is all
        // the caller needs, not also one naming a role of the same name in its own account.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleArn = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), OTHER_ACCOUNT, "/",
                "cross-account-" + suffix, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole"}]}""".formatted(ACCOUNT), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*"}]}""");
        String user = "cross-account-caller-" + suffix;
        String iamAuth = auth(ACCOUNT, "iam");
        UserCredentials caller = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), iamAuth, user, "assume", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"%s"}]}""".formatted(roleArn));

        assumeRole(caller, roleArn).then().statusCode(200);
    }

    @Test
    void aPolicyNamingOneFederatedUserAllowsGetFederationTokenForThatNameOnly() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "federation-named-" + suffix;
        String iamAuth = auth(ACCOUNT, "iam");
        UserCredentials caller = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), iamAuth, user, "federation", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:GetFederationToken",
                   "Resource":"arn:aws:sts::%s:federated-user/fed-%s"}]}""".formatted(ACCOUNT, suffix));

        getFederationToken(caller, "fed-" + suffix).then().statusCode(200);
        getFederationToken(caller, "other-" + suffix).then().statusCode(403);
    }

    @Test
    void aPolicyNamingOneRoleDoesNotAllowOtherStsActionsThatCarryItsArn() {
        // GetFederationToken never acts on a role, so adding the allowed role's ARN to it must not
        // make a policy scoped to that role allow it.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String allowedArn = createRole("federation-allowed-" + suffix);
        String user = "federation-caller-" + suffix;
        String iamAuth = auth(ACCOUNT, "iam");
        UserCredentials caller = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), iamAuth, user, "sts-all", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:*","Resource":"%s"}]}""".formatted(allowedArn));

        given().port(baseUri.getPort())
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(caller.accessKeyId(), "sts"))
                .formParam("Action", "GetFederationToken")
                .formParam("Name", "federated")
                .formParam("RoleArn", allowedArn)
                .when().post("/")
                .then().statusCode(403);
        assumeRole(caller, allowedArn).then().statusCode(200);
    }

    @Test
    void aMalformedRoleArnCannotDodgeADenyOnTheRoleItSpells() {
        // A RoleArn with an invalid percent escape cannot be decoded, so it is authorized as * like
        // a request naming no role, and the Deny on the role it spells does not match. The Allow on *
        // lets it through, but the form reader behind the filter cannot decode the body either, so no
        // role is assumed: the request fails exactly as it does without the resource check. This pins
        // the outcome only; ResourceArnBuilderTest pins that the filter reads the value as absent.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String deniedArn = createRole("malformed-denied-" + suffix);
        String user = "malformed-caller-" + suffix;
        String iamAuth = auth(ACCOUNT, "iam");
        UserCredentials caller = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), iamAuth, user, "assume", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"},
                  {"Effect":"Deny","Action":"sts:AssumeRole","Resource":"%s"}]}""".formatted(deniedArn));

        given().port(baseUri.getPort()).urlEncodingEnabled(false)
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(caller.accessKeyId(), "sts"))
                .body("Action=AssumeRole&RoleSessionName=" + EnforcementFixtures.SESSION_NAME + "&RoleArn="
                        + URLEncoder.encode(deniedArn, StandardCharsets.UTF_8) + "%ZZ")
                .when().post("/")
                .then().statusCode(not(200)).body(not(containsString("AccessKeyId")));
    }

    @Test
    void aPolicyKeyedOnARolesTagAllowsAssumingOnlyARoleCarryingIt() {
        // IAM User Guide, "IAM: Assume roles that have a specific tag": a user may assume any role whose
        // iam:ResourceTag/Project is ExampleCorpABC.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String taggedArn = createRole("tagged-" + suffix);
        tagRole("tagged-" + suffix, "Project", "ExampleCorpABC");
        String untaggedArn = createRole("untagged-" + suffix);
        String user = "tag-caller-" + suffix;
        String iamAuth = auth(ACCOUNT, "iam");
        UserCredentials caller = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), iamAuth, user, "assume", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*",
                  "Condition":{"StringEquals":{"iam:ResourceTag/Project":"ExampleCorpABC"}}}]}""");

        assumeRole(caller, taggedArn).then().statusCode(200);
        assumeRole(caller, untaggedArn).then().statusCode(403);
    }

    @Test
    void aTrustPolicyKeyedOnItsRolesTagAllowsAssumingOnlyARoleCarryingIt() {
        // A trust policy reads the tags of its own role as aws:ResourceTag.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trust = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole",
                  "Condition":{"StringEquals":{"aws:ResourceTag/Team":"Blue"}}}]}""".formatted(ACCOUNT);
        String blueArn = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", "blue-" + suffix,
                trust, LIST_USERS);
        tagRole("blue-" + suffix, "Team", "Blue");
        String redArn = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", "red-" + suffix,
                trust, LIST_USERS);
        tagRole("red-" + suffix, "Team", "Red");
        String user = "trust-tag-caller-" + suffix;
        String iamAuth = auth(ACCOUNT, "iam");
        UserCredentials caller = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), iamAuth, user, "assume", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"}]}""");

        assumeRole(caller, blueArn).then().statusCode(200);
        assumeRole(caller, redArn).then().statusCode(403);
    }

    private void tagRole(String role, String key, String value) {
        iamCall(auth(ACCOUNT, "iam"), "TagRole", "RoleName", role, "Tags.member.1.Key", key,
                "Tags.member.1.Value", value).then().statusCode(200);
    }

    private Response iamCall(String authorization, String action, String... parameters) {
        RequestSpecification request = given().port(baseUri.getPort())
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .formParam("Action", action);
        for (int index = 0; index < parameters.length; index += 2) {
            request = request.formParam(parameters[index], parameters[index + 1]);
        }
        return request.when().post("/");
    }

    private String createRole(String role) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*"}]}""");
    }

    private Response getFederationToken(UserCredentials caller, String name) {
        return given().port(baseUri.getPort())
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(caller.accessKeyId(), "sts"))
                .formParam("Action", "GetFederationToken")
                .formParam("Name", name)
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
