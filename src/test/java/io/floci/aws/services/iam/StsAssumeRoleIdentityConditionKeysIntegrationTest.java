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
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;

/**
 * With {@code iam.enforcement-enabled=true}, the caller's identity policy for {@code sts:AssumeRole}
 * is evaluated with the {@code sts:RoleSessionName} and {@code sts:ExternalId} the request carries,
 * which the Service Authorization Reference lists among the condition keys of {@code AssumeRole}.
 * Every role here trusts its account, so the identity policy decides.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StsAssumeRoleIdentityConditionKeysIntegrationTest {

    private static final String ACCOUNT = "111111111111";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void anIdentityPolicyRequiringAnExternalIdAllowsOnlyThatExternalId() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleArn = createRole("external-id-" + suffix);
        UserCredentials caller = callerWithPolicy("external-id-caller-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"%s",
                   "Condition":{"StringEquals":{"sts:ExternalId":"partner-42"}}}]}""".formatted(roleArn));

        assumeRole(caller, roleArn, "s1", "partner-42").then().statusCode(200);
        assumeRole(caller, roleArn, "s2", "partner-43").then().statusCode(403);
        assumeRole(caller, roleArn, "s3", null).then().statusCode(403);
    }

    @Test
    void anIdentityPolicyRequiringTheUserNameAsSessionNameAllowsOnlyThatName() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleArn = createRole("session-name-" + suffix);
        String user = "session-caller-" + suffix;
        UserCredentials caller = callerWithPolicy(user, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"%s",
                   "Condition":{"StringEquals":{"sts:RoleSessionName":"${aws:username}"}}}]}""".formatted(roleArn));

        assumeRole(caller, roleArn, user, null).then().statusCode(200);
        assumeRole(caller, roleArn, "someone-else", null).then().statusCode(403);
    }

    @Test
    void aDenyOnASessionNamePatternRefusesOnlyThoseNames() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleArn = createRole("session-deny-" + suffix);
        UserCredentials caller = callerWithPolicy("session-deny-caller-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"%s"},
                  {"Effect":"Deny","Action":"sts:AssumeRole","Resource":"*",
                   "Condition":{"StringLike":{"sts:RoleSessionName":"admin-*"}}}]}""".formatted(roleArn));

        assumeRole(caller, roleArn, "admin-1", null).then().statusCode(403);
        assumeRole(caller, roleArn, "batch-1", null).then().statusCode(200);
    }

    @Test
    void anIdentityPolicyWithoutConditionAllowsWithOrWithoutAnExternalId() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleArn = createRole("no-condition-" + suffix);
        UserCredentials caller = callerWithPolicy("no-condition-caller-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"%s"}]}""".formatted(roleArn));

        assumeRole(caller, roleArn, "s1", null).then().statusCode(200);
        assumeRole(caller, roleArn, "s2", "partner-42").then().statusCode(200);
    }

    private UserCredentials callerWithPolicy(String user, String policy) {
        String iamAuth = auth(ACCOUNT, "iam");
        UserCredentials caller = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), iamAuth, user, "assume", policy);
        return caller;
    }

    private String createRole(String role) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*"}]}""");
    }

    private Response assumeRole(UserCredentials caller, String roleArn, String sessionName, String externalId) {
        RequestSpecification request = given().port(baseUri.getPort())
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(caller.accessKeyId(), "sts"))
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", sessionName);
        if (externalId != null) {
            request = request.formParam("ExternalId", externalId);
        }
        return request.when().post("/");
    }
}
