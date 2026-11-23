package io.floci.aws.services.aps;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testing.SignedRestRequests;
import io.floci.aws.testing.TestAccounts;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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

    private static final String TEAM_METRICS = "{\"tags\":{\"team\":\"metrics\"}}";
    private static final String RULES = Base64.getEncoder()
            .encodeToString("groups: []\n".getBytes(StandardCharsets.UTF_8));

    private final AwsRequestSigner admin = AwsRequestSigner.signedAs("test", "test", "aps");

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    IamService iam;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

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
    void aTagCallIsCheckedOnTheWorkspaceItsArnNamesOnAmpsOwnRouteAlone() throws Exception {
        String allowed = createWorkspace();
        String other = createWorkspace();
        String allowedArn = workspaceArn(allowed);
        String otherArn = workspaceArn(other);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "aps-iam-", "access",
                allow("[\"aps:TagResource\",\"aps:ListTagsForResource\"]", allowedArn))) {
            send(user, "POST", "/tags/" + encoded(allowedArn), "{\"tags\":{\"encoded\":\"yes\"}}").then()
                    .statusCode(200);
            // AWS serves AMP's tagging on /tags/{resourceArn} alone: an ARN whose slash is not encoded, and the bare
            // /tags the shared controller serves other services on, are no route of AMP's, whatever the caller may do.
            for (String arn : List.of(allowedArn, otherArn)) {
                invalidArn(send(user, "POST", "/tags/" + arn, "{\"tags\":{\"raw\":\"yes\"}}"), arn);
                invalidArn(send(user, "POST", "/tags", "{\"resourceArn\":\"" + arn + "\",\"tags\":{\"bare\":\"yes\"}}"),
                        arn);
            }
            send(user, "GET", "/tags/" + encoded(allowedArn), null).then().statusCode(200)
                    .body("tags", equalTo(Map.of("encoded", "yes")));
            denied(send(user, "POST", "/tags/" + encoded(otherArn), "{\"tags\":{\"encoded\":\"yes\"}}"),
                    "aps:TagResource");
            denied(send(user, "GET", "/tags/" + encoded(otherArn), null), "aps:ListTagsForResource");
            denied(send(user, "DELETE", "/tags/" + encoded(allowedArn) + "?tagKeys=encoded", null),
                    "aps:UntagResource");
            send(admin, "GET", "/tags/" + encoded(otherArn), null).then().statusCode(200)
                    .body("tags", equalTo(Map.of()));
        } finally {
            deleteWorkspace(allowed);
            deleteWorkspace(other);
        }
    }

    private static void invalidArn(Response response, String arn) {
        response.then().statusCode(400).body("message", equalTo("Invalid resource ARN: " + arn));
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags AMP holds on the workspace a request names, as tag calls
     * leave them: an allow on one team's tag reads that team's workspace, not an untagged one, no longer reads it once
     * {@code UntagResource} has removed the tag, and reads the other once {@code TagResource} has added it.
     */
    @Test
    void aResourceTagConditionReadsTheTagsOfTheWorkspaceARequestNames() throws Exception {
        String account = TestAccounts.randomId();
        AwsRequestSigner owner = user(account, "aps-owner", allow("\"aps:*\"", "*"));
        AwsRequestSigner caller = user(account, "aps-tags", onTeamMetrics("aps:DescribeWorkspace"));
        Workspace tagged = workspace(owner, TEAM_METRICS);
        Workspace untagged = workspace(owner, "{}");

        send(caller, "GET", "/workspaces/" + tagged.id(), null).then().statusCode(200)
                .body("workspace.arn", equalTo(tagged.arn()));
        denied(send(caller, "GET", "/workspaces/" + untagged.id(), null), "aps:DescribeWorkspace");
        send(owner, "DELETE", "/tags/" + encoded(tagged.arn()) + "?tagKeys=team", null).then().statusCode(200);
        denied(send(caller, "GET", "/workspaces/" + tagged.id(), null), "aps:DescribeWorkspace");
        send(owner, "POST", "/tags/" + encoded(untagged.arn()), TEAM_METRICS).then().statusCode(200);
        send(caller, "GET", "/workspaces/" + untagged.id(), null).then().statusCode(200)
                .body("workspace.arn", equalTo(untagged.arn()));
    }

    /** As for a workspace, for the rule groups namespace a request names by its workspace and name. */
    @Test
    void aResourceTagConditionReadsTheTagsOfTheRuleGroupsNamespaceARequestNames() throws Exception {
        String account = TestAccounts.randomId();
        AwsRequestSigner owner = user(account, "aps-owner", allow("\"aps:*\"", "*"));
        AwsRequestSigner caller = user(account, "aps-tags", onTeamMetrics("aps:DescribeRuleGroupsNamespace"));
        String namespaces = "/workspaces/" + workspace(owner, "{}").id() + "/rulegroupsnamespaces";
        String arn = send(owner, "POST", namespaces, tagged("metrics")).then().statusCode(202).extract().path("arn");
        send(owner, "POST", namespaces, namespace("untagged")).then().statusCode(202);

        send(caller, "GET", namespaces + "/tagged", null).then().statusCode(200)
                .body("ruleGroupsNamespace.arn", equalTo(arn));
        denied(send(caller, "GET", namespaces + "/untagged", null), "aps:DescribeRuleGroupsNamespace");
        send(owner, "DELETE", "/tags/" + encoded(arn) + "?tagKeys=team", null).then().statusCode(200);
        denied(send(caller, "GET", namespaces + "/tagged", null), "aps:DescribeRuleGroupsNamespace");
        String untagged = arn.replace("/tagged", "/untagged");
        send(owner, "POST", "/tags/" + encoded(untagged), TEAM_METRICS).then().statusCode(200);
        send(caller, "GET", namespaces + "/untagged", null).then().statusCode(200)
                .body("ruleGroupsNamespace.arn", equalTo(untagged));
    }

    /** A user of {@code account} with the inline policy {@code policy}, removed after the test, signing for AMP. */
    private AwsRequestSigner user(String account, String name, String policy) {
        AccessKey key = SignedIamUsers.createUser(iam, cleanup, account, name, "access", policy);
        return AwsRequestSigner.signedAs(key.getAccessKeyId(), key.getSecretAccessKey(), "aps");
    }

    private record Workspace(String id, String arn) {
    }

    /** A workspace {@code owner} creates with {@code body}, deleted after the test. */
    private Workspace workspace(AwsRequestSigner owner, String body) throws Exception {
        Response created = send(owner, "POST", "/workspaces", body);
        created.then().statusCode(202);
        Workspace workspace = new Workspace(created.path("workspaceId"), created.path("arn"));
        cleanup.register(() -> {
            try {
                send(owner, "DELETE", "/workspaces/" + workspace.id(), null).then().statusCode(202);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return workspace;
    }

    /** An allow of {@code action} on any resource tagged {@code team} {@code metrics}. */
    private static String onTeamMetrics(String action) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"" + action
                + "\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"metrics\"}}}]}";
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
