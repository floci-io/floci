package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
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
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * A delegated administrator may create roles and users only with a given permissions boundary, as in
 * the IAM user guide's "Delegating responsibility to others using permissions boundaries" example:
 * an allow conditioned on {@code StringEquals} {@code iam:PermissionsBoundary}. The Service
 * Authorization Reference lists the key on {@code CreateRole}, {@code CreateUser},
 * {@code PutRolePermissionsBoundary} and {@code PutUserPermissionsBoundary}, which carry the boundary
 * in their {@code PermissionsBoundary} parameter. On actions on an existing role or user, such as
 * {@code AttachRolePolicy} and {@code PutUserPolicy}, the key is the boundary stored on it.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamPermissionsBoundaryConditionEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void createRoleIsAllowedOnlyWithTheBoundaryTheConditionNames() {
        String boundary = boundaryPolicy("boundary");
        String other = boundaryPolicy("other");
        UserCredentials delegated = delegatedAdmin(boundary, "iam:CreateRole");
        String role = "bounded-" + suffix();
        cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth(ADMIN, "iam"), role));

        denied(createRole(delegated, role).post("/").then(), "iam:CreateRole");
        denied(createRole(delegated, role).formParam("PermissionsBoundary", other).post("/").then(),
                "iam:CreateRole");
        createRole(delegated, role).formParam("PermissionsBoundary", boundary).post("/").then()
                .statusCode(200)
                .body(containsString("<PermissionsBoundaryArn>" + boundary + "</PermissionsBoundaryArn>"));
    }

    @Test
    void createUserAndPutUserPermissionsBoundaryAreAllowedOnlyWithThatBoundary() {
        String boundary = boundaryPolicy("boundary");
        String other = boundaryPolicy("other");
        UserCredentials delegated = delegatedAdmin(boundary, "iam:CreateUser",
                "iam:PutUserPermissionsBoundary");
        String user = "bounded-" + suffix();
        cleanup.register(() -> EnforcementFixtures.removeUser(baseUri.getPort(), auth(ADMIN, "iam"), user));

        denied(iam(delegated.accessKeyId(), "CreateUser").formParam("UserName", user).post("/").then(),
                "iam:CreateUser");
        iam(delegated.accessKeyId(), "CreateUser").formParam("UserName", user)
                .formParam("PermissionsBoundary", boundary).post("/").then().statusCode(200);

        denied(iam(delegated.accessKeyId(), "PutUserPermissionsBoundary").formParam("UserName", user)
                .formParam("PermissionsBoundary", other).post("/").then(), "iam:PutUserPermissionsBoundary");
        iam(delegated.accessKeyId(), "PutUserPermissionsBoundary").formParam("UserName", user)
                .formParam("PermissionsBoundary", boundary).post("/").then().statusCode(200);
    }

    @Test
    void putRolePermissionsBoundaryIsAllowedOnlyWithThatBoundary() {
        String boundary = boundaryPolicy("boundary");
        String other = boundaryPolicy("other");
        UserCredentials delegated = delegatedAdmin(boundary, "iam:PutRolePermissionsBoundary");
        String role = "rebounded-" + suffix();
        cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth(ADMIN, "iam"), role));
        createRole(ADMIN, role).post("/").then().statusCode(200);

        denied(iam(delegated.accessKeyId(), "PutRolePermissionsBoundary").formParam("RoleName", role)
                .formParam("PermissionsBoundary", other).post("/").then(), "iam:PutRolePermissionsBoundary");
        iam(delegated.accessKeyId(), "PutRolePermissionsBoundary").formParam("RoleName", role)
                .formParam("PermissionsBoundary", boundary).post("/").then().statusCode(200);
    }

    @Test
    void attachRolePolicyIsAllowedOnlyOnARoleThatHasTheBoundary() {
        String boundary = boundaryPolicy("boundary");
        String policy = boundaryPolicy("attached");
        UserCredentials delegated = delegatedAdmin(boundary, "iam:AttachRolePolicy");
        String bounded = "bounded-" + suffix();
        String open = "open-" + suffix();
        cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth(ADMIN, "iam"), bounded));
        cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth(ADMIN, "iam"), open));
        createRole(ADMIN, bounded).formParam("PermissionsBoundary", boundary).post("/").then().statusCode(200);
        createRole(ADMIN, open).post("/").then().statusCode(200);

        denied(iam(delegated.accessKeyId(), "AttachRolePolicy").formParam("RoleName", open)
                .formParam("PolicyArn", policy).post("/").then(), "iam:AttachRolePolicy");
        iam(delegated.accessKeyId(), "AttachRolePolicy").formParam("RoleName", bounded)
                .formParam("PolicyArn", policy).post("/").then().statusCode(200);
    }

    @Test
    void putUserPolicyIsAllowedOnlyOnAUserThatHasTheBoundary() {
        String boundary = boundaryPolicy("boundary");
        UserCredentials delegated = delegatedAdmin(boundary, "iam:PutUserPolicy");
        String bounded = "bounded-" + suffix();
        String open = "open-" + suffix();
        cleanup.register(() -> EnforcementFixtures.removeUser(baseUri.getPort(), auth(ADMIN, "iam"), bounded));
        cleanup.register(() -> EnforcementFixtures.removeUser(baseUri.getPort(), auth(ADMIN, "iam"), open));
        iam(ADMIN, "CreateUser").formParam("UserName", bounded).formParam("PermissionsBoundary", boundary)
                .post("/").then().statusCode(200);
        iam(ADMIN, "CreateUser").formParam("UserName", open).post("/").then().statusCode(200);
        String document = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""";

        denied(iam(delegated.accessKeyId(), "PutUserPolicy").formParam("UserName", open)
                .formParam("PolicyName", "inline").formParam("PolicyDocument", document).post("/").then(),
                "iam:PutUserPolicy");
        iam(delegated.accessKeyId(), "PutUserPolicy").formParam("UserName", bounded)
                .formParam("PolicyName", "inline").formParam("PolicyDocument", document).post("/").then()
                .statusCode(200);
    }

    private String boundaryPolicy(String name) {
        String arn = iam(ADMIN, "CreatePolicy")
                .formParam("PolicyName", name + "-" + suffix())
                .formParam("PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:*","Resource":"*"}]}""")
                .post("/").then().statusCode(200)
                .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> EnforcementFixtures.removePolicy(baseUri.getPort(), auth(ADMIN, "iam"), arn));
        return arn;
    }

    private UserCredentials delegatedAdmin(String boundary, String... actions) {
        String userName = "delegated-admin-" + suffix();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ADMIN, "iam"), userName);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), userName, "OnlyWithBoundary", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["%s"],"Resource":"*",
                  "Condition":{"StringEquals":{"iam:PermissionsBoundary":"%s"}}}]}"""
                .formatted(String.join("\",\"", actions), boundary));
        return credentials;
    }

    private RequestSpecification createRole(UserCredentials caller, String roleName) {
        return createRole(caller.accessKeyId(), roleName);
    }

    private RequestSpecification createRole(String accessKeyId, String roleName) {
        return iam(accessKeyId, "CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY);
    }

    private static RequestSpecification iam(String accessKeyId, String action) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(accessKeyId, "iam"))
                .formParam("Action", action);
    }

    private static void denied(ValidatableResponse response, String action) {
        response.statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString(action));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
