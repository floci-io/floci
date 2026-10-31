package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * With {@code iam.enforcement-enabled=true}, {@code sts:AssumeRole} is authorized against the role it
 * names, so an identity policy can allow assuming one role and not another (IAM User Guide, "Granting
 * a user permissions to switch roles": the policy's Resource is the role's ARN).
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StsAssumeRoleResourceEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";

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

    private String createRole(String role) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*"}]}""");
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
