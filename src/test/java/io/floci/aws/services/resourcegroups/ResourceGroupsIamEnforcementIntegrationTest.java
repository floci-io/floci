package io.floci.aws.services.resourcegroups;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ResourceGroupsIamEnforcementIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final Map<String, String> OPERATIONS = Map.of(
            "/groups", "CreateGroup",
            "/get-group", "GetGroup",
            "/delete-group", "DeleteGroup",
            "/group-resources", "GroupResources",
            "/list-group-resources", "ListGroupResources");

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void explicitDenyAppliesToEveryManagementOperation() {
        String user = "rg-deny-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), user)
                .accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "deny", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"*","Resource":"*"},
                  {"Effect":"Deny","Action":"resource-groups:*","Resource":"*"}]}
                """);
        for (String path : OPERATIONS.keySet()) {
            call(key, path, Map.of()).statusCode(403).body("__type", equalTo("AccessDeniedException"));
        }
    }

    @Test
    void createAuthorizesNameInsteadOfIgnoredGroupSelectors() {
        String user = "rg-create-scope-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), user)
                .accessKeyId();
        String name = "rg-secret-" + UUID.randomUUID();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "allow", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"resource-groups:CreateGroup",
                    "Resource":"arn:aws:resource-groups:us-east-1:%s:group/allowed"}]}
                """.formatted(ACCOUNT));
        call(key, "/groups", "{\"Name\":\"" + name + "\",\"Group\":\"allowed\",\"GroupName\":\"allowed\","
                + "\"Configuration\":" + ResourceGroupsServiceTest.CONFIGURATION + "}").statusCode(403);
        call(ACCOUNT, "/get-group", Map.of("Group", name)).statusCode(404);
    }

    @Test
    void groupScopedDenyAppliesToEveryManagementOperation() {
        String user = "rg-scoped-deny-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), user)
                .accessKeyId();
        String name = "rg-scoped-" + UUID.randomUUID();
        String arn = "arn:aws:resource-groups:us-east-1:" + ACCOUNT + ":group/" + name;
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "deny", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"*","Resource":"*"},
                  {"Effect":"Deny","Action":"resource-groups:*","Resource":"%s"}]}
                """.formatted(arn));
        for (String path : OPERATIONS.keySet()) {
            Object body = path.equals("/groups")
                    ? "{\"Name\":\"" + name + "\",\"Configuration\":" + ResourceGroupsServiceTest.CONFIGURATION + "}"
                    : Map.of("Group", name);
            call(key, path, body).statusCode(403).body("__type", equalTo("AccessDeniedException"));
            if (!path.equals("/groups")) {
                call(key, path, Map.of("Group", arn)).statusCode(403)
                        .body("__type", equalTo("AccessDeniedException"));
            }
        }
        call(ACCOUNT, "/get-group", Map.of("Group", name)).statusCode(404);
    }

    @ParameterizedTest
    @CsvSource({"Group,false", "Group,true", "GroupName,false"})
    void aGroupScopedGrantAllowsOnlyThatGroup(String selector, boolean useArn) {
        String name = "rg-scoped-allow-" + UUID.randomUUID();
        String arn = "arn:aws:resource-groups:us-east-1:" + ACCOUNT + ":group/" + name;
        String user = "rg-scoped-allow-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), user)
                .accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "allow", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"resource-groups:GetGroup","Resource":"%s"}]}
                """.formatted(arn));
        call(ACCOUNT, "/groups", "{\"Name\":\"" + name + "\",\"Configuration\":"
                + ResourceGroupsServiceTest.CONFIGURATION + "}").statusCode(200);
        try {
            call(key, "/get-group", Map.of(selector, useArn ? arn : name)).statusCode(200);
            call(key, "/get-group", Map.of("Group", name + "-other")).statusCode(403);
            call(key, "/delete-group", Map.of("Group", name)).statusCode(403);
        } finally {
            call(ACCOUNT, "/delete-group", Map.of("Group", name)).statusCode(200);
        }
    }

    @Test
    void allowedManagementOperationsStillReachTheirService() {
        String user = "rg-allow-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), user)
                .accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "allow", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"resource-groups:*","Resource":"*"}]}
                """);
        String name = "rg-allow-" + UUID.randomUUID();
        call(key, "/groups", "{\"Name\":\"" + name + "\",\"Configuration\":"
                + ResourceGroupsServiceTest.CONFIGURATION + "}").statusCode(200);
        try {
            call(key, "/get-group", Map.of("Group", name)).statusCode(200);
            call(key, "/list-group-resources", Map.of("Group", name)).statusCode(200);
            call(key, "/group-resources", Map.of("Group", name, "ResourceArns",
                    new String[]{"arn:aws:ec2:us-east-1:" + ACCOUNT + ":capacity-reservation/cr-00000000000000001"}))
                    .statusCode(200).body("Failed[0].ErrorCode", equalTo("ResourceGroupsValidationException"));
        } finally {
            call(key, "/delete-group", Map.of("Group", name)).statusCode(200);
        }
    }

    private static ValidatableResponse call(String key, String path, Object body) {
        return given().header("Authorization", auth(key, "resource-groups"))
                .contentType("application/json").body(body).post(path).then();
    }
}
