package io.floci.aws.services.aps;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testing.SignedRestRequests;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.floci.aws.testing.IamTestPolicies.allow;
import static io.floci.aws.testing.SignedRestRequests.denied;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Amazon Managed Service for Prometheus requests are checked against the caller's policies through its generated
 * routes: a workspace call on the workspace its path names, a rule groups namespace call on the namespace its
 * workspace and name give, a tag call on the resource its ARN names however the ARN is sent, and a create that tags
 * what it makes also as {@code aps:TagResource}.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class ApsIamIntegrationTest {

    private static final String RULES = Base64.getEncoder()
            .encodeToString("groups: []\n".getBytes(StandardCharsets.UTF_8));

    private final AwsRequestSigner admin = AwsRequestSigner.signedAs("test", "test", "aps");

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void aWorkspaceReadIsRefusedWithoutThePermissionAllowedWithItAndRefusedOnceItIsRemoved() throws Exception {
        String workspaceId = createWorkspace();
        String arn = workspaceArn(workspaceId);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "aps-iam-")) {
            denied(send(user, "GET", "/workspaces/" + workspaceId, null), "aps:DescribeWorkspace");
            try {
                SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(), "PolicyName", "access",
                        "PolicyDocument", allow("\"aps:DescribeWorkspace\"", arn))).then().statusCode(200);
                send(user, "GET", "/workspaces/" + workspaceId, null).then().statusCode(200)
                        .body("workspace.arn", equalTo(arn));
            } finally {
                SignedIamUsers.iam(endpoint, "DeleteUserPolicy",
                        Map.of("UserName", user.name(), "PolicyName", "access")).then().statusCode(200);
            }
            denied(send(user, "GET", "/workspaces/" + workspaceId, null), "aps:DescribeWorkspace");
        } finally {
            deleteWorkspace(workspaceId);
        }
    }

    @Test
    void aCallerWithoutPermissionsIsRefusedBeforeAWorkspaceIsCreatedOrDeleted() throws Exception {
        String workspaceId = createWorkspace();
        String alias = "iam-none-" + workspaceId;
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "aps-iam-")) {
            denied(send(user, "POST", "/workspaces", "{\"alias\":\"" + alias + "\"}"), "aps:CreateWorkspace");
            denied(send(user, "GET", "/workspaces", null), "aps:ListWorkspaces");
            denied(send(user, "DELETE", "/workspaces/" + workspaceId, null), "aps:DeleteWorkspace");
            send(admin, "GET", "/workspaces?alias=" + alias, null).then().statusCode(200)
                    .body("workspaces.size()", equalTo(0));
            send(admin, "GET", "/workspaces/" + workspaceId, null).then().statusCode(200);
        } finally {
            deleteWorkspace(workspaceId);
        }
    }

    @Test
    void aWorkspaceScopedAllowReachesThatWorkspaceAndRefusesAnother() throws Exception {
        String allowed = createWorkspace();
        String other = createWorkspace();
        String actions = "[\"aps:DescribeWorkspace\",\"aps:UpdateWorkspaceAlias\",\"aps:ListRuleGroupsNamespaces\"]";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "aps-iam-", "access",
                allow(actions, workspaceArn(allowed)))) {
            send(user, "GET", "/workspaces/" + allowed, null).then().statusCode(200)
                    .body("workspace.workspaceId", equalTo(allowed));
            denied(send(user, "GET", "/workspaces/" + other, null), "aps:DescribeWorkspace");
            send(user, "POST", "/workspaces/" + allowed + "/alias", "{\"alias\":\"renamed\"}").then().statusCode(204);
            denied(send(user, "POST", "/workspaces/" + other + "/alias", "{\"alias\":\"renamed\"}"),
                    "aps:UpdateWorkspaceAlias");
            send(user, "GET", "/workspaces/" + allowed + "/rulegroupsnamespaces", null).then().statusCode(200);
            denied(send(user, "GET", "/workspaces/" + other + "/rulegroupsnamespaces", null),
                    "aps:ListRuleGroupsNamespaces");
            denied(send(user, "DELETE", "/workspaces/" + allowed, null), "aps:DeleteWorkspace");
            send(admin, "GET", "/workspaces/" + allowed, null).then().statusCode(200)
                    .body("workspace.alias", equalTo("renamed"));
            send(admin, "GET", "/workspaces/" + other, null).then().statusCode(200)
                    .body("workspace.alias", nullValue());
        } finally {
            deleteWorkspace(allowed);
            deleteWorkspace(other);
        }
    }

    @Test
    void aNamespaceScopedAllowReachesTheNamespacesOfOneWorkspaceOnly() throws Exception {
        String allowed = createWorkspace();
        String other = createWorkspace();
        try {
            String rules = createNamespace(allowed, "rules");
            createNamespace(other, "rules");
            String namespaces = workspaceArn(allowed).replace(":workspace/", ":rulegroupsnamespace/") + "/*";
            String actions = "[\"aps:CreateRuleGroupsNamespace\",\"aps:DescribeRuleGroupsNamespace\","
                    + "\"aps:PutRuleGroupsNamespace\"]";
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "aps-iam-", "access",
                    allow(actions, namespaces))) {
                send(user, "GET", "/workspaces/" + allowed + "/rulegroupsnamespaces/rules", null).then()
                        .statusCode(200).body("ruleGroupsNamespace.arn", equalTo(rules));
                denied(send(user, "GET", "/workspaces/" + other + "/rulegroupsnamespaces/rules", null),
                        "aps:DescribeRuleGroupsNamespace");
                send(user, "PUT", "/workspaces/" + allowed + "/rulegroupsnamespaces/rules", data()).then()
                        .statusCode(202);
                denied(send(user, "PUT", "/workspaces/" + other + "/rulegroupsnamespaces/rules", data()),
                        "aps:PutRuleGroupsNamespace");
                denied(send(user, "DELETE", "/workspaces/" + allowed + "/rulegroupsnamespaces/rules", null),
                        "aps:DeleteRuleGroupsNamespace");
                send(user, "POST", "/workspaces/" + allowed + "/rulegroupsnamespaces", namespace("more")).then()
                        .statusCode(202).body("arn", equalTo(rules.replace("/rules", "/more")));
                denied(send(user, "POST", "/workspaces/" + other + "/rulegroupsnamespaces", namespace("more")),
                        "aps:CreateRuleGroupsNamespace");
            }
            send(admin, "GET", "/workspaces/" + allowed + "/rulegroupsnamespaces/rules", null).then().statusCode(200);
            send(admin, "GET", "/workspaces/" + other + "/rulegroupsnamespaces/more", null).then().statusCode(404);
        } finally {
            deleteWorkspace(allowed);
            deleteWorkspace(other);
        }
    }

    @Test
    void aCreateThatTagsWhatItMakesAlsoNeedsTheTaggingPermission() throws Exception {
        String workspaceId = createWorkspace();
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                + "[\"aps:CreateWorkspace\",\"aps:CreateRuleGroupsNamespace\"],\"Resource\":\"*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"aps:TagResource\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"metrics\"}}}]}";
        String alias = "iam-tagged-" + workspaceId;
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "aps-iam-", "access", policy)) {
            denied(send(user, "POST", "/workspaces", "{\"alias\":\"" + alias + "\",\"tags\":{\"team\":\"logs\"}}"),
                    "aps:TagResource");
            denied(send(user, "POST", "/workspaces/" + workspaceId + "/rulegroupsnamespaces", tagged("logs")),
                    "aps:TagResource");
            send(admin, "GET", "/workspaces?alias=" + alias, null).then().statusCode(200)
                    .body("workspaces.size()", equalTo(0));
            send(admin, "GET", "/workspaces/" + workspaceId + "/rulegroupsnamespaces/tagged", null).then()
                    .statusCode(404);
            String created = send(user, "POST", "/workspaces", "{\"alias\":\"" + alias
                    + "\",\"tags\":{\"team\":\"metrics\"}}").then().statusCode(202)
                    .body("tags.team", equalTo("metrics")).extract().path("workspaceId");
            deleteWorkspace(created);
            send(user, "POST", "/workspaces/" + workspaceId + "/rulegroupsnamespaces", tagged("metrics")).then()
                    .statusCode(202).body("tags.team", equalTo("metrics"));
        } finally {
            deleteWorkspace(workspaceId);
        }
    }

    @Test
    void aTagCallIsCheckedOnTheResourceItsArnNamesHoweverTheArnIsSent() throws Exception {
        String allowed = createWorkspace();
        String other = createWorkspace();
        String allowedArn = workspaceArn(allowed);
        String otherArn = workspaceArn(other);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "aps-iam-", "access",
                allow("[\"aps:TagResource\",\"aps:ListTagsForResource\"]", allowedArn))) {
            send(user, "POST", "/tags/" + encoded(allowedArn), "{\"tags\":{\"encoded\":\"yes\"}}").then()
                    .statusCode(200);
            send(user, "POST", "/tags/" + allowedArn, "{\"tags\":{\"raw\":\"yes\"}}").then().statusCode(200);
            send(user, "POST", "/tags", "{\"resourceArn\":\"" + allowedArn + "\",\"tags\":{\"bare\":\"yes\"}}").then()
                    .statusCode(200);
            send(user, "GET", "/tags/" + encoded(allowedArn), null).then().statusCode(200)
                    .body("tags", equalTo(Map.of("encoded", "yes", "raw", "yes", "bare", "yes")));
            denied(send(user, "POST", "/tags/" + encoded(otherArn), "{\"tags\":{\"encoded\":\"yes\"}}"),
                    "aps:TagResource");
            denied(send(user, "POST", "/tags/" + otherArn, "{\"tags\":{\"raw\":\"yes\"}}"), "aps:TagResource");
            denied(send(user, "POST", "/tags", "{\"resourceArn\":\"" + otherArn + "\",\"tags\":{\"bare\":\"yes\"}}"),
                    "aps:TagResource");
            denied(send(user, "GET", "/tags/" + otherArn, null), "aps:ListTagsForResource");
            denied(send(user, "DELETE", "/tags/" + allowedArn + "?tagKeys=raw", null), "aps:UntagResource");
            send(admin, "GET", "/tags/" + encoded(otherArn), null).then().statusCode(200)
                    .body("tags", equalTo(Map.of()));
        } finally {
            deleteWorkspace(allowed);
            deleteWorkspace(other);
        }
    }

    private static String encoded(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    private static String data() {
        return "{\"data\":\"" + RULES + "\"}";
    }

    private static String namespace(String name) {
        return "{\"name\":\"" + name + "\",\"data\":\"" + RULES + "\"}";
    }

    private static String tagged(String team) {
        return "{\"name\":\"tagged\",\"data\":\"" + RULES + "\",\"tags\":{\"team\":\"" + team + "\"}}";
    }

    private String createWorkspace() throws Exception {
        return send(admin, "POST", "/workspaces", "{}").then().statusCode(202).extract().path("workspaceId");
    }

    private String workspaceArn(String workspaceId) throws Exception {
        return send(admin, "GET", "/workspaces/" + workspaceId, null).then().statusCode(200)
                .extract().path("workspace.arn");
    }

    private String createNamespace(String workspaceId, String name) throws Exception {
        return send(admin, "POST", "/workspaces/" + workspaceId + "/rulegroupsnamespaces", namespace(name)).then()
                .statusCode(202).extract().path("arn");
    }

    private void deleteWorkspace(String workspaceId) throws Exception {
        send(admin, "DELETE", "/workspaces/" + workspaceId, null).then().statusCode(202);
    }

    private Response send(SignedIamUsers.User user, String method, String path, String body) throws Exception {
        return send(user.signer("aps"), method, path, body);
    }

    private Response send(AwsRequestSigner signer, String method, String path, String body) throws Exception {
        return SignedRestRequests.send(endpoint, signer, method, path, body, "application/json");
    }
}
