package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamPolicyArnConditionIntegrationTest {
    private static final String DOCUMENT = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private String account;
    private String region;
    private boolean operationAlias;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();
    @TestHTTPResource("/")
    URI baseUri;

    static Stream<Arguments> requests() {
        return Stream.of("Role", "User", "Group").flatMap(kind ->
                Stream.of("Attach", "Detach").flatMap(verb ->
                        Stream.of("allow", "deny", "control", "mismatchAllow", "mismatchDeny", "duplicate")
                                .flatMap(mode -> Stream.of(false, true)
                                        .map(scoped -> Arguments.of(kind, verb, mode, scoped)))));
    }

    @ParameterizedTest
    @MethodSource("requests")
    void managedPolicyConditionMatchesTheRequestedPolicy(String kind, String verb, String mode, boolean scoped) {
        account = scoped ? "123456789012" : "000000000000";
        region = scoped ? "cn-north-1" : "us-east-1";
        operationAlias = scoped;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String target = "context-target-" + suffix;
        String targetKey = kind + "Name";
        String policyName = "context-policy-" + suffix;
        Response createdPolicy = call(account, "CreatePolicy", Map.of("PolicyName", policyName,
                "PolicyDocument", DOCUMENT));
        assertEquals(200, createdPolicy.statusCode(), createdPolicy.asString());
        String policyArn = createdPolicy.xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> EnforcementFixtures.removePolicy(baseUri.getPort(), auth(account), policyArn));
        if (kind.equals("Role")) {
            cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth(account), target));
        } else if (kind.equals("Group")) {
            cleanup.register(() -> EnforcementFixtures.removeGroup(baseUri.getPort(), auth(account), target));
        } else {
            cleanup.register(() -> EnforcementFixtures.removeUser(baseUri.getPort(), auth(account), target));
        }
        Map<String, String> create = kind.equals("Role")
                ? Map.of(targetKey, target, "Path", "/context/", "AssumeRolePolicyDocument",
                        TRUST)
                : Map.of(targetKey, target, "Path", "/context/");
        Response created = call(account, "Create" + kind, create);
        assertEquals(200, created.statusCode(), created.asString());
        String targetArn = created.xmlPath().getString(
                "Create" + kind + "Response.Create" + kind + "Result." + kind + ".Arn");
        Map<String, String> parameters = Map.of(targetKey, target, "PolicyArn", policyArn);
        if (verb.equals("Detach")) {
            Response attached = call(account, "Attach" + kind + "Policy", parameters);
            assertEquals(200, attached.statusCode(), attached.asString());
        }
        String caller = "context-caller-" + suffix;
        String accessKey = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(account), caller).accessKeyId();
        String action = "iam:" + verb + kind + "Policy";
        boolean denyStatement = mode.equals("deny") || mode.equals("mismatchDeny");
        String conditional = """
                {"Effect":"%s","Action":"%s","Resource":"%s",
                 "Condition":{"ArnEquals":{"iam:PolicyARN":"%s"}}}"""
                .formatted(denyStatement ? "Deny" : "Allow", action, targetArn,
                        mode.startsWith("mismatch") ? policyArn + "-other" : policyArn);
        String broad = """
                {"Effect":"Allow","Action":"%s","Resource":"%s"}""".formatted(action, targetArn);
        String statements = mode.equals("control") ? broad
                : denyStatement ? broad + "," + conditional : conditional;
        Response authorized = call(account, "PutUserPolicy", Map.of("UserName", caller, "PolicyName", "condition",
                "PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}"));
        assertEquals(200, authorized.statusCode(), authorized.asString());
        RequestSpecification request = given().header("Authorization", auth(accessKey))
                .contentType("application/x-www-form-urlencoded")
                .formParam(operationAlias ? "Operation" : "Action", verb + kind + "Policy")
                .formParam("Version", "2010-05-08").formParam(targetKey, target)
                .formParam("PolicyArn", policyArn)
                .queryParam("PolicyArn", policyArn + "-url-shadow");
        if (mode.equals("duplicate")) {
            request.formParam("PolicyArn", policyArn + "-second-value");
        }
        Response result = request.when().post("/");
        Response listed = call(account, "ListAttached" + kind + "Policies", Map.of(targetKey, target));
        assertEquals(200, listed.statusCode(), listed.asString());
        boolean denied = mode.equals("deny") || mode.equals("mismatchAllow");
        boolean shouldBeAttached = verb.equals("Attach") != denied;
        if (denied) {
            assertEquals("AccessDenied", result.xmlPath().getString("ErrorResponse.Error.Code"));
        }
        assertAll(
                () -> assertEquals(denied ? 403 : 200, result.statusCode(), result.asString()),
                () -> assertEquals(shouldBeAttached, listed.asString().contains(policyArn), listed.asString()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sharedGroupCleanupReleasesManagedPolicies(boolean scoped) {
        account = scoped ? "123456789012" : "000000000000";
        region = scoped ? "cn-north-1" : "us-east-1";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String group = "cleanup-group-" + suffix;
        Response createdPolicy = call(account, "CreatePolicy", Map.of("PolicyName", "cleanup-policy-" + suffix,
                "PolicyDocument", DOCUMENT));
        assertEquals(200, createdPolicy.statusCode(), createdPolicy.asString());
        String arn = createdPolicy.xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> EnforcementFixtures.removePolicy(baseUri.getPort(), auth(account), arn));
        cleanup.register(() -> EnforcementFixtures.removeGroup(baseUri.getPort(), auth(account), group));
        assertEquals(200, call(account, "CreateGroup", Map.of("GroupName", group)).statusCode());
        assertEquals(200, call(account, "AttachGroupPolicy", Map.of("GroupName", group, "PolicyArn", arn))
                .statusCode());
        EnforcementFixtures.removeGroup(baseUri.getPort(), auth(account), group);
        assertEquals(404, call(account, "GetGroup", Map.of("GroupName", group)).statusCode());
        // The policy's attachment count must be released too, otherwise its deletion is refused.
        assertEquals(200, call(account, "DeletePolicy", Map.of("PolicyArn", arn)).statusCode());
        assertEquals(404, call(account, "GetPolicy", Map.of("PolicyArn", arn)).statusCode());
    }

    private String auth(String key) {
        return EnforcementFixtures.auth(key, "iam").replace("/us-east-1/", "/" + region + "/");
    }

    private Response call(String key, String action, Map<String, String> parameters) {
        return given().header("Authorization", auth(key)).contentType("application/x-www-form-urlencoded")
                .formParam("Action", action).formParam("Version", "2010-05-08").formParams(parameters)
                .when().post("/");
    }
}
