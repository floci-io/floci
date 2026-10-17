package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.LogsAuthorizationFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LogsGroupAuthorizationIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @Inject
    CloudWatchLogsService logsService;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogGroup", "DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy"})
    void exactGroupPermissionAllowsOnlyTheSelectedGroup(String action) {
        String selected = group(action);
        String other = group(action);
        String caller = user(policy("Allow", action, arn(selected)));
        Response denied = operation(caller, action, other).extract().response();
        assertAll("unrelated group denied without mutation",
                () -> denied.then().statusCode(400).body(containsString("AccessDeniedException")),
                () -> assertUnchanged(action, other));
        operation(caller, action, selected).statusCode(200);
        assertChanged(action, selected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogGroup", "DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy"})
    void bareGroupPermissionAllowsOnlyTheSelectedGroup(String action) {
        String selected = group(action);
        String other = group(action);
        String caller = user(policy("Allow", action, bareArn(selected)));
        Response denied = operation(caller, action, other).extract().response();
        assertAll("bare group grant denies unrelated state changes",
                () -> denied.then().statusCode(400).body(containsString("AccessDeniedException")),
                () -> assertUnchanged(action, other));
        operation(caller, action, selected).statusCode(200);
        assertChanged(action, selected);
    }

    @ParameterizedTest
    @CsvSource({"CreateLogGroup,false", "CreateLogGroup,true", "DeleteLogGroup,false", "DeleteLogGroup,true",
            "PutRetentionPolicy,false", "PutRetentionPolicy,true", "DeleteRetentionPolicy,false",
            "DeleteRetentionPolicy,true"})
    void oppositeGroupArnFormDenyOverridesExactAllow(String action, boolean bareAllow) {
        String selected = group(action);
        String other = group(action);
        String selectedAllow = bareAllow ? bareArn(selected) : arn(selected);
        String otherAllow = bareAllow ? bareArn(other) : arn(other);
        String selectedDeny = bareAllow ? arn(selected) : bareArn(selected);
        String caller = user(policy("Allow", action, selectedAllow) + ","
                + policy("Allow", action, otherAllow) + "," + policy("Deny", action, selectedDeny));
        Response denied = operation(caller, action, selected).extract().response();
        assertAll("equivalent group ARN deny preserves the selected group",
                () -> denied.then().statusCode(400).body(containsString("AccessDeniedException")),
                () -> assertUnchanged(action, selected));
        operation(caller, action, other).statusCode(200);
        assertChanged(action, other);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogGroup", "DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy"})
    void exactGroupDenyOverridesWildcardAllow(String action) {
        String selected = group(action);
        String other = group(action);
        String caller = user(policy("Allow", action, "*") + "," + policy("Deny", action, arn(selected)));
        Response denied = operation(caller, action, selected).extract().response();
        assertAll("selected group denied without mutation",
                () -> denied.then().statusCode(400).body(containsString("AccessDeniedException")),
                () -> assertUnchanged(action, selected));
        operation(caller, action, other).statusCode(200);
        assertChanged(action, other);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy"})
    void resourceTagDenyProtectsExistingGroup(String action) {
        String selected = group(action);
        String other = group(action);
        call(endpoint.getPort(), ACCOUNT, "TagLogGroup",
                Map.of("logGroupName", selected, "tags", Map.of("protected", "true"))).statusCode(200);
        String condition = ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/protected\":\"true\"}}";
        String caller = user(policy("Allow", action, "*") + ","
                + policy("Deny", action, "*", condition));
        Response denied = operation(caller, action, selected).extract().response();
        assertAll("group tag deny preserves state",
                () -> denied.then().statusCode(400).body(containsString("AccessDeniedException")),
                () -> assertUnchanged(action, selected));
        operation(caller, action, other).statusCode(200);
        assertChanged(action, other);
    }

    @Test
    void createRequestTagsUseHandlerValues() {
        String selected = group("CreateLogGroup");
        String other = group("CreateLogGroup");
        String condition = ",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"42\"}}";
        String caller = user(policy("Allow", "CreateLogGroup", "*", condition)
                + "," + policy("Allow", "TagResource", "*"));
        call(endpoint.getPort(), caller, "CreateLogGroup",
                Map.of("logGroupName", other, "tags", Map.of("team", "red")))
                .statusCode(400).body(containsString("AccessDeniedException"));
        assertNull(find(other));
        call(endpoint.getPort(), caller, "CreateLogGroup",
                Map.of("logGroupName", selected, "tags", Map.of("team", 42))).statusCode(200);
        call(endpoint.getPort(), ACCOUNT, "ListTagsLogGroup", Map.of("logGroupName", selected))
                .statusCode(200).body("tags.team", equalTo("42"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "TagResource", "TagLogGroup"})
    void taggedCreateRequiresEitherTagPermission(String taggingAction) {
        String selected = group("CreateLogGroup");
        String untagged = group("CreateLogGroup");
        String statements = policy("Allow", "CreateLogGroup", "*");
        if (!taggingAction.equals("none")) {
            statements += "," + policy("Allow", taggingAction, "*");
        }
        String caller = user(statements);
        call(endpoint.getPort(), caller, "CreateLogGroup", Map.of("logGroupName", untagged)).statusCode(200);
        assertNotNull(find(untagged));
        Response response = call(endpoint.getPort(), caller, "CreateLogGroup",
                Map.of("logGroupName", selected, "tags", Map.of("team", "blue"))).extract().response();
        if (taggingAction.equals("none")) {
            assertAll("tagged creation requires an alternative permission",
                    () -> response.then().statusCode(400).body(containsString("AccessDeniedException")),
                    () -> assertNull(find(selected)));
        } else {
            response.then().statusCode(200);
            call(endpoint.getPort(), ACCOUNT, "ListTagsLogGroup", Map.of("logGroupName", selected))
                    .statusCode(200).body("tags.team", equalTo("blue"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogGroup", "DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy"})
    void ambiguousGroupNamesCannotBecomeAuthorizedAliases(String action) {
        String name = "logs-group-invalid-" + UUID.randomUUID() + ":child";
        if (!action.equals("CreateLogGroup")) {
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, REGION, () -> logsService.deleteLogGroup(name, REGION)));
            logsService.createLogGroupForAccount(ACCOUNT, name, 7, Map.of(), REGION);
        }
        String caller = user(policy("Allow", action, "*"));
        operation(caller, action, name).statusCode(400).body(containsString("InvalidParameterException"));
        assertUnchanged(action, name);
    }

    @ParameterizedTest
    @CsvSource({"TagResource,false,false", "TagResource,true,false", "TagLogGroup,false,false",
            "TagLogGroup,true,false", "TagLogGroup,false,true", "TagLogGroup,true,true"})
    void createTagAlternativesUseTheirOwnArnAndConditions(String taggingAction, boolean primaryBare,
                                                          boolean taggingBare) {
        String selected = group("CreateLogGroup");
        String otherAction = taggingAction.equals("TagResource") ? "TagLogGroup" : "TagResource";
        String resource = taggingAction.equals("TagResource") || taggingBare ? bareArn(selected) : arn(selected);
        String condition = ",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"blue\"},"
                + "\"ForAllValues:StringEquals\":{\"aws:TagKeys\":[\"team\"]}}";
        String caller = user(policy("Allow", "CreateLogGroup", primaryBare ? bareArn(selected) : arn(selected)) + ","
                + policy("Allow", taggingAction, resource, condition) + "," + policy("Deny", otherAction, "*"));
        for (Map<String, String> rejected : List.of(Map.of("team", "red"), Map.of("team", "blue", "extra", "value"))) {
            Response denied = call(endpoint.getPort(), caller, "CreateLogGroup",
                    Map.of("logGroupName", selected, "tags", rejected)).extract().response();
            assertAll("secondary permission conditions must reject before creating",
                    () -> denied.then().statusCode(400).body(containsString("AccessDeniedException")),
                    () -> assertNull(find(selected)));
        }
        call(endpoint.getPort(), caller, "CreateLogGroup",
                Map.of("logGroupName", selected, "tags", Map.of("team", "blue"))).statusCode(200);
        call(endpoint.getPort(), ACCOUNT, "ListTagsLogGroup", Map.of("logGroupName", selected))
                .statusCode(200).body("tags.team", equalTo("blue"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"array", "text", "number", "empty-object", "null"})
    void ignoredOrEmptyTagsDoNotRequireTaggingPermission(String kind) {
        String selected = group("CreateLogGroup");
        String caller = user(policy("Allow", "CreateLogGroup", arn(selected)));
        Object tags = switch (kind) {
            case "array" -> List.of("team");
            case "text" -> "team";
            case "number" -> 42;
            case "empty-object" -> Map.of();
            case "null" -> null;
            default -> throw new AssertionError(kind);
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("logGroupName", selected);
        body.put("tags", tags);
        call(endpoint.getPort(), caller, "CreateLogGroup", body).statusCode(200);
        assertEquals(Map.of(), call(endpoint.getPort(), ACCOUNT, "ListTagsLogGroup",
                Map.of("logGroupName", selected)).statusCode(200).extract().path("tags"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void taggedCreationRetainsAssumedRoleSessionCeilings(boolean taggingAllowed) {
        String selected = group("CreateLogGroup");
        String untagged = group("CreateLogGroup");
        String role = "logs-group-session-" + UUID.randomUUID();
        int port = endpoint.getPort();
        String root = EnforcementFixtures.auth(ACCOUNT, "iam");
        cleanup.register(() -> EnforcementFixtures.removeRole(port, root, role));
        String roleArn = given().port(port).header("Authorization", root)
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                         "Principal":{"AWS":"*"},"Action":"sts:AssumeRole"}]}
                        """).post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        String create = policy("Allow", "CreateLogGroup", "*");
        String tagging = policy("Allow", "TagResource", "*");
        given().port(port).header("Authorization", root).formParam("Action", "PutRolePolicy")
                .formParam("RoleName", role).formParam("PolicyName", "groups")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + create + "," + tagging + "]}")
                .post("/").then().statusCode(200);
        String sessionPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + create + (taggingAllowed ? "," + tagging : "") + "]}";
        Response assumed = given().port(port).header("Authorization", EnforcementFixtures.auth(ACCOUNT, "sts"))
                .formParam("Action", "AssumeRole").formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", "groups").formParam("Policy", sessionPolicy)
                .post("/").then().statusCode(200).extract().response();
        String key = assumed.xmlPath().getString("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
        String token = assumed.xmlPath().getString("AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken");
        sessionCreate(port, key, token, Map.of("logGroupName", untagged)).then().statusCode(200);
        assertNotNull(find(untagged));
        Response response = sessionCreate(port, key, token,
                Map.of("logGroupName", selected, "tags", Map.of("team", "blue")));
        assertAll("secondary permission must retain the issued session policy",
                () -> response.then().statusCode(taggingAllowed ? 200 : 400),
                () -> assertEquals(taggingAllowed, find(selected) != null),
                () -> {
                    if (!taggingAllowed) {
                        response.then().body(containsString("AccessDeniedException"));
                    }
                });
    }

    private static Response sessionCreate(int port, String key, String token, Map<String, ?> body) {
        return given().port(port).contentType("application/x-amz-json-1.1")
                .header("Authorization", EnforcementFixtures.auth(key, "logs"))
                .header("X-Amz-Security-Token", token).header("X-Amz-Target", "Logs_20140328.CreateLogGroup")
                .body(body).when().post("/");
    }

    private String group(String action) {
        String name = "logs-group-auth-" + UUID.randomUUID();
        int port = endpoint.getPort();
        cleanup.register(() -> {
            if (find(name) != null) {
                call(port, ACCOUNT, "DeleteLogGroup", Map.of("logGroupName", name)).statusCode(200);
            }
        });
        if (!action.equals("CreateLogGroup")) {
            call(port, ACCOUNT, "CreateLogGroup", Map.of("logGroupName", name)).statusCode(200);
            call(port, ACCOUNT, "PutRetentionPolicy", Map.of("logGroupName", name, "retentionInDays", 7))
                    .statusCode(200);
        }
        return name;
    }

    private String user(String statements) {
        return LogsAuthorizationFixtures.user(cleanup, endpoint.getPort(), ACCOUNT, "logs-group-auth-",
                "groups", statements);
    }

    private static String policy(String effect, String action, String resource) {
        return policy(effect, action, resource, "");
    }

    private static String policy(String effect, String action, String resource, String condition) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"logs:" + action
                + "\",\"Resource\":\"" + resource + "\"" + condition + "}";
    }

    private static String bareArn(String group) {
        return "arn:aws:logs:" + REGION + ":" + ACCOUNT + ":log-group:" + group;
    }

    private static String arn(String group) {
        return bareArn(group) + ":*";
    }

    private ValidatableResponse operation(String caller, String action, String group) {
        Map<String, ?> body = action.equals("PutRetentionPolicy")
                ? Map.of("logGroupName", group, "retentionInDays", 14) : Map.of("logGroupName", group);
        return call(endpoint.getPort(), caller, action, body);
    }

    private Map<String, Object> find(String group) {
        List<Map<String, Object>> groups = call(endpoint.getPort(), ACCOUNT, "DescribeLogGroups",
                Map.of("logGroupNamePrefix", group)).statusCode(200).extract().path("logGroups");
        return groups.stream().filter(item -> group.equals(item.get("logGroupName"))).findFirst().orElse(null);
    }

    private void assertUnchanged(String action, String group) {
        if (action.equals("CreateLogGroup")) {
            assertNull(find(group));
        } else {
            Map<String, Object> stored = find(group);
            assertNotNull(stored);
            assertEquals(7, stored.get("retentionInDays"));
        }
    }

    private void assertChanged(String action, String group) {
        if (action.equals("DeleteLogGroup")) {
            assertNull(find(group));
        } else {
            Map<String, Object> stored = find(group);
            assertNotNull(stored);
            assertEquals(action.equals("PutRetentionPolicy") ? 14 : null, stored.get("retentionInDays"));
        }
    }

    private static ValidatableResponse call(int port, String caller, String action, Map<String, ?> body) {
        return LogsAuthorizationFixtures.call(port, caller, "20261016", REGION, action, body).then();
    }
}
