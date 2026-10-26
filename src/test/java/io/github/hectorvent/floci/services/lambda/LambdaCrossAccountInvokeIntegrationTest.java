package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Under IAM enforcement, a call to a function in another account needs the function policy to allow
 * the caller as well as the caller's own policy (Lambda Developer Guide, function URLs: "If the
 * principal making the request is in a different account, then the principal must have both an
 * identity-based policy ... and permissions granted to them in a resource-based policy"). Lambda
 * authorizes a call against the function, version or alias ARN the call names, so a function addressed
 * by ARN is evaluated as that ARN, in its own account and with its qualifier.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaCrossAccountInvokeIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String OWNER = "444455556666";
    private static final String CALLER = "777788889999";
    private static final String SM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String INVOKE_ANYTHING = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"*"}]}""";
    private static final String ROTATE_AND_INVOKE = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"secretsmanager:*","Resource":"*"},
              {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"*"}]}""";

    @InjectMock
    LambdaExecutorService executor;

    /** How to delete each thing the test created, pushed as it is created and run newest first. */
    private final Deque<Runnable> cleanup = new ArrayDeque<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubExecutor() {
        when(executor.invoke(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new InvokeResult(200, null, "{}".getBytes(StandardCharsets.UTF_8), null, "req"));
    }

    @AfterEach
    void deleteWhatTheTestCreated() {
        while (!cleanup.isEmpty()) {
            cleanup.pop().run();
        }
    }

    @Test
    void anotherAccountsFunctionIsRefusedWhenItsPolicyGrantsNothing() {
        String functionArn = createFunction(OWNER, "victim-" + suffix());
        String caller = createUser(CALLER, "invoker-" + suffix(), INVOKE_ANYTHING);

        invoke(caller, functionArn)
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));

        verify(executor, never()).invoke(argThat(isFunction(functionArn)), any(), any(), anyInt(), any(), any());
    }

    @Test
    void aGrantToTheCallersAccountLetsItInvoke() {
        String functionArn = createFunction(OWNER, "shared-" + suffix());
        addPermission(OWNER, functionArn, null, Map.of("Principal", CALLER));
        String caller = createUser(CALLER, "invoker-" + suffix(), INVOKE_ANYTHING);

        invoke(caller, functionArn).statusCode(200);

        verify(executor).invoke(argThat(isFunction(functionArn)), any(), any(), anyInt(), any(), any());
    }

    @Test
    void aGrantDoesNotStandInForTheCallersOwnPolicy() {
        String functionArn = createFunction(OWNER, "shared-" + suffix());
        addPermission(OWNER, functionArn, null, Map.of("Principal", CALLER));
        String caller = createUser(CALLER, "no-lambda-" + suffix(), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListAllMyBuckets","Resource":"*"}]}""");

        invoke(caller, functionArn).statusCode(403);
    }

    @Test
    void aPartialArnReachesTheAccountItNames() {
        String name = "partial-" + suffix();
        String functionArn = createFunction(OWNER, name);
        String caller = createUser(CALLER, "invoker-" + suffix(), INVOKE_ANYTHING);

        invoke(caller, OWNER + ":function:" + name).statusCode(403);

        addPermission(OWNER, functionArn, null, Map.of("Principal", CALLER));
        invoke(caller, OWNER + ":function:" + name).statusCode(200);

        verify(executor).invoke(argThat(isFunction(functionArn)), any(), any(), anyInt(), any(), any());
    }

    @Test
    void aGrantNamingARoleCoversThatRolesSessionsOnly() {
        String functionArn = createFunction(OWNER, "role-scoped-" + suffix());
        String grantedRole = "deploy.bot-" + suffix();
        String otherRole = "other-" + suffix();
        createRole(CALLER, grantedRole, INVOKE_ANYTHING);
        createRole(CALLER, otherRole, INVOKE_ANYTHING);
        addPermission(OWNER, functionArn, null,
                Map.of("Principal", "arn:aws:iam::" + CALLER + ":role/" + grantedRole));

        invoke(assumeRole(CALLER, grantedRole), functionArn).statusCode(200);
        invoke(assumeRole(CALLER, otherRole), functionArn).statusCode(403);
    }

    @Test
    void anAliasGrantCoversThatAliasOnly() {
        String name = "aliased-" + suffix();
        String functionArn = createFunction(OWNER, name);
        publishVersionAndAlias(OWNER, name, "live");
        addPermission(OWNER, functionArn, "live", Map.of("Principal", CALLER));
        String caller = createUser(CALLER, "invoker-" + suffix(), INVOKE_ANYTHING);

        invoke(caller, functionArn + ":live").statusCode(200);
        invoke(caller, functionArn, "live").statusCode(200);
        invoke(caller, functionArn).statusCode(403);
        invoke(caller, functionArn + ":1").statusCode(403);
    }

    @Test
    void aPublicFunctionUrlGrantDoesNotAdmitADirectInvoke() {
        String functionArn = createFunction(OWNER, "url-only-" + suffix());
        addPermission(OWNER, functionArn, null, Map.of("Principal", "*", "InvokedViaFunctionUrl", true));
        String caller = createUser(CALLER, "invoker-" + suffix(), INVOKE_ANYTHING);

        invoke(caller, functionArn).statusCode(403);
    }

    @Test
    void aDenyOnTheFunctionArnAppliesToAnInvokeByArn() {
        String name = "guarded-" + suffix();
        String functionArn = createFunction(OWNER, name);
        String user = createUser(OWNER, "denied-" + suffix(), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"*"},
                  {"Effect":"Deny","Action":"lambda:InvokeFunction","Resource":"%s"}]}""".formatted(functionArn));

        invoke(user, name).statusCode(403);
        invoke(user, functionArn).statusCode(403);
        invoke(user, OWNER + ":function:" + name).statusCode(403);

        verify(executor, never()).invoke(argThat(isFunction(functionArn)), any(), any(), anyInt(), any(), any());
    }

    @Test
    void aSameAccountFunctionPolicyGrantsOnItsOwn() {
        String functionArn = createFunction(OWNER, "own-grant-" + suffix());
        String userName = "granted-" + suffix();
        String user = createUser(OWNER, userName, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListAllMyBuckets","Resource":"*"}]}""");
        addPermission(OWNER, functionArn, null, Map.of("Principal", "arn:aws:iam::" + OWNER + ":user/" + userName));

        invoke(user, functionArn).statusCode(200);
    }

    /**
     * Operations other than invoke act on the caller's own function of the name an ARN gives, whatever
     * account it names, so they are authorized against that function: a grant from the account the
     * ARN names does not get past a Deny on the function the call actually changes.
     */
    @Test
    void aManagementCallNamingAnotherAccountsArnIsCheckedAgainstTheFunctionItChanges() {
        String name = "managed-" + suffix();
        String foreignArn = createFunction(OWNER, name);
        String ownArn = createFunction(CALLER, name);
        addPermission(OWNER, foreignArn, null, Map.of("Principal", CALLER, "Action", "lambda:DeleteFunction"));
        String user = createUser(CALLER, "deleter-" + suffix(), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:*","Resource":"*"},
                  {"Effect":"Deny","Action":"lambda:DeleteFunction","Resource":"%s"}]}""".formatted(ownArn));

        lambda(user).delete("/2015-03-31/functions/{name}", foreignArn).then().statusCode(403);

        lambda(CALLER).get("/2015-03-31/functions/{name}", name).then().statusCode(200);
        lambda(OWNER).get("/2015-03-31/functions/{name}", name).then().statusCode(200);
    }

    /**
     * {@code GetFunction} reads a function named by a qualified ARN in the account the ARN names, so
     * that account's function policy has to allow the caller, as for an invoke.
     */
    @Test
    void aQualifiedReadOfAnotherAccountsFunctionNeedsItsGrant() {
        String foreignArn = createFunction(OWNER, "read-" + suffix());
        String user = createUser(CALLER, "reader-" + suffix(), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:GetFunction","Resource":"*"}]}""");

        lambda(user).get("/2015-03-31/functions/{name}", foreignArn + ":$LATEST").then().statusCode(403);
        lambda(user).queryParam("Qualifier", "$LATEST").get("/2015-03-31/functions/{name}", foreignArn)
                .then().statusCode(403);

        addPermission(OWNER, foreignArn, null, Map.of("Principal", CALLER, "Action", "lambda:GetFunction"));
        lambda(user).get("/2015-03-31/functions/{name}", foreignArn + ":$LATEST")
                .then().statusCode(200).body("Configuration.FunctionArn", startsWith(foreignArn));
    }

    @Test
    void unpublishedCapacityValidationPrecedesMissingFunctionLookupAfterIamAuthorization() {
        String name = "absent-capacity-" + suffix();
        String user = createUser(OWNER, "capacity-admin-" + suffix(), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:*","Resource":"*"}]}""");
        String path = "/2019-09-30/functions/{name}/provisioned-concurrency";
        lambda(user).queryParam("Qualifier", "$LATEST").body("{\"ProvisionedConcurrentExecutions\":1}")
                .put(path, name).then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo(
                        "Provisioned Concurrency Configs cannot be applied to unpublished function versions."));
        String message = "The function resource provided must be an alias or a published version.";
        lambda(user).queryParam("Qualifier", "$LATEST").get(path, name).then().statusCode(400)
                .body("message", equalTo(message));
        lambda(user).queryParam("Qualifier", "$LATEST").delete(path, name).then().statusCode(400)
                .body("message", equalTo(message));
        lambda(user).queryParam("Qualifier", "1").get(path, name).then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        String denied = createUser(OWNER, "capacity-denied-" + suffix(), INVOKE_ANYTHING);
        lambda(denied).queryParam("Qualifier", "$LATEST").get(path, name).then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void provisionedConcurrencyRequiresTheOwnersQualifiedGrant() {
        String name = "capacity-" + suffix();
        String arn = createFunction(OWNER, name);
        publishVersionAndAlias(OWNER, name, "live");
        String user = createUser(CALLER, "capacity-reader-" + suffix(), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:*","Resource":"*"}]}""");
        String path = "/2019-09-30/functions/{name}/provisioned-concurrency";
        lambda(user).queryParam("Qualifier", "1").get(path, arn).then().statusCode(403);
        addPermission(OWNER, arn, "1", Map.of("Principal", CALLER,
                "Action", "lambda:GetProvisionedConcurrencyConfig"));
        lambda(user).queryParam("Qualifier", "1").get(path, arn).then().statusCode(404)
                .body("__type", equalTo("ProvisionedConcurrencyConfigNotFoundException"));
        lambda(user).queryParam("Qualifier", "live").get(path, arn).then().statusCode(403);
        lambda(user).queryParam("Qualifier", "1").delete(path, arn).then().statusCode(403);
        lambda(user).queryParam("Qualifier", "1").body("{\"ProvisionedConcurrentExecutions\":1}")
                .put(path, arn).then().statusCode(403);
        lambda(OWNER).queryParam("Qualifier", "1").get(path, name).then().statusCode(404);
    }

    @Test
    void provisionedConcurrencyGrantDoesNotReplaceTheCallersIdentityPolicy() {
        String name = "capacity-deny-" + suffix();
        String arn = createFunction(OWNER, name);
        publishVersionAndAlias(OWNER, name, "live");
        addPermission(OWNER, arn, "1", Map.of("Principal", CALLER,
                "Action", "lambda:GetProvisionedConcurrencyConfig"));
        String user = createUser(CALLER, "capacity-denied-" + suffix(), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"*"}]}""");
        lambda(user).queryParam("Qualifier", "1")
                .get("/2019-09-30/functions/{name}/provisioned-concurrency", arn).then().statusCode(403);
    }

    @Test
    void rotateSecretIsRefusedForAnotherAccountsFunctionThatGrantsNothing() {
        String name = "rotator-" + suffix();
        String foreignArn = createFunction(OWNER, name);
        createFunction(CALLER, name);
        String caller = createUser(CALLER, "rotator-user-" + suffix(), ROTATE_AND_INVOKE);
        String secretName = createSecret(caller, "foreign-" + suffix());

        secretsManager(caller, "RotateSecret",
                "{\"SecretId\":\"" + secretName + "\",\"RotationLambdaARN\":\"" + foreignArn + "\"}")
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));

        verify(executor, never()).invoke(argThat(isFunction(foreignArn)), any(), any(), anyInt(), any(), any());
    }

    @Test
    void rotateSecretRunsAnotherAccountsFunctionThatGrantsTheCaller() {
        String foreignArn = createFunction(OWNER, "rotator-" + suffix());
        addPermission(OWNER, foreignArn, null, Map.of("Principal", CALLER));
        String caller = createUser(CALLER, "rotator-user-" + suffix(), ROTATE_AND_INVOKE);
        String secretName = createSecret(caller, "granted-" + suffix());

        secretsManager(caller, "RotateSecret",
                "{\"SecretId\":\"" + secretName + "\",\"RotationLambdaARN\":\"" + foreignArn + "\"}")
                .statusCode(200);

        verify(executor, timeout(5000).atLeastOnce())
                .invoke(argThat(isFunction(foreignArn)), any(), any(), anyInt(), any(), any());
        awaitRotationFinished(caller, secretName);
    }

    @Test
    void rotateSecretRefusesAnAliasTheFunctionDoesNotHave() {
        String functionArn = createFunction(OWNER, "rotator-" + suffix());
        String user = createUser(OWNER, "rotator-user-" + suffix(), ROTATE_AND_INVOKE);
        String secretName = createSecret(user, "no-alias-" + suffix());

        secretsManager(user, "RotateSecret",
                "{\"SecretId\":\"" + secretName + "\",\"RotationLambdaARN\":\"" + functionArn + ":nosuchalias\"}")
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));

        verify(executor, never()).invoke(any(), any(), any(), anyInt(), any(), any());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static ArgumentMatcher<LambdaFunction> isFunction(String functionArn) {
        return fn -> fn != null && functionArn.equals(fn.getFunctionArn());
    }

    private String createFunction(String account, String name) {
        lambda(account)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Handler":"index.handler",
                         "Role":"arn:aws:iam::%s:role/lambda-role"}""".formatted(name, account))
        .when()
                .post("/2015-03-31/functions")
        .then()
                .statusCode(201);
        cleanup.push(() -> lambda(account).delete("/2015-03-31/functions/{name}", name).then().statusCode(204));
        return "arn:aws:lambda:" + REGION + ":" + account + ":function:" + name;
    }

    private static void publishVersionAndAlias(String account, String name, String alias) {
        lambda(account).body("{}").post("/2015-03-31/functions/{name}/versions", name).then().statusCode(201);
        lambda(account)
                .body("{\"Name\":\"" + alias + "\",\"FunctionVersion\":\"1\"}")
                .post("/2015-03-31/functions/{name}/aliases", name)
        .then()
                .statusCode(201);
    }

    /** Adds a statement granting {@code lambda:InvokeFunction} unless {@code grant} names another action. */
    private static void addPermission(String account, String functionArn, String qualifier, Map<String, Object> grant) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("StatementId", "s-" + suffix());
        request.put("Action", "lambda:InvokeFunction");
        request.putAll(grant);
        RequestSpecification spec = lambda(account).body(request);
        if (qualifier != null) {
            spec.queryParam("Qualifier", qualifier);
        }
        spec.post("/2015-03-31/functions/{name}/policy", functionArn).then().statusCode(201);
    }

    private static ValidatableResponse invoke(String accessKeyId, String functionName) {
        return invoke(accessKeyId, functionName, null);
    }

    private static ValidatableResponse invoke(String accessKeyId, String functionName, String qualifier) {
        RequestSpecification spec = given()
                .header("Authorization", authorization(accessKeyId, "lambda"))
                .contentType("application/json")
                .body("{}");
        if (qualifier != null) {
            spec.queryParam("Qualifier", qualifier);
        }
        return spec.when().post("/2015-03-31/functions/{name}/invocations", functionName).then();
    }

    private static RequestSpecification lambda(String accessKeyId) {
        return given()
                .header("Authorization", authorization(accessKeyId, "lambda"))
                .contentType("application/json");
    }

    private String createSecret(String accessKeyId, String secretName) {
        secretsManager(accessKeyId, "CreateSecret",
                "{\"Name\":\"" + secretName + "\",\"SecretString\":\"v1\"}").statusCode(200);
        cleanup.push(() -> secretsManager(accessKeyId, "DeleteSecret",
                "{\"SecretId\":\"" + secretName + "\",\"ForceDeleteWithoutRecovery\":true}").statusCode(200));
        return secretName;
    }

    private static ValidatableResponse secretsManager(String accessKeyId, String operation, String body) {
        return given()
                .header("Authorization", authorization(accessKeyId, "secretsmanager"))
                .header("X-Amz-Target", "secretsmanager." + operation)
                .contentType(SM_CONTENT_TYPE)
                .body(body)
        .when()
                .post("/")
        .then();
    }

    /**
     * Lets the rotation finish before cleanup deletes the secret, which it would otherwise go on
     * trying to update.
     */
    private static void awaitRotationFinished(String accessKeyId, String secretName) {
        await().atMost(Duration.ofSeconds(5)).until(() -> secretsManager(accessKeyId, "DescribeSecret",
                "{\"SecretId\":\"" + secretName + "\"}").statusCode(200).extract().path("LastRotatedDate") != null);
    }

    private String createUser(String account, String userName, String policyDocument) {
        iam(account, "CreateUser", Map.of("UserName", userName)).statusCode(200);
        cleanup.push(() -> iam(account, "DeleteUser", Map.of("UserName", userName)).statusCode(200));
        iam(account, "PutUserPolicy", Map.of(
                "UserName", userName,
                "PolicyName", "inline",
                "PolicyDocument", policyDocument)).statusCode(200);
        cleanup.push(() -> iam(account, "DeleteUserPolicy",
                Map.of("UserName", userName, "PolicyName", "inline")).statusCode(200));
        String accessKeyId = iam(account, "CreateAccessKey", Map.of("UserName", userName))
                .statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        cleanup.push(() -> iam(account, "DeleteAccessKey",
                Map.of("UserName", userName, "AccessKeyId", accessKeyId)).statusCode(200));
        return accessKeyId;
    }

    private void createRole(String account, String roleName, String policyDocument) {
        iam(account, "CreateRole", Map.of(
                "RoleName", roleName,
                "AssumeRolePolicyDocument", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole"}]}"""
                        .formatted(account))).statusCode(200);
        cleanup.push(() -> iam(account, "DeleteRole", Map.of("RoleName", roleName)).statusCode(200));
        iam(account, "PutRolePolicy", Map.of(
                "RoleName", roleName,
                "PolicyName", "inline",
                "PolicyDocument", policyDocument)).statusCode(200);
        cleanup.push(() -> iam(account, "DeleteRolePolicy",
                Map.of("RoleName", roleName, "PolicyName", "inline")).statusCode(200));
    }

    private static String assumeRole(String account, String roleName) {
        return given()
                .header("Authorization", authorization(account, "sts"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AssumeRole")
                .formParam("Version", "2011-06-15")
                .formParam("RoleArn", "arn:aws:iam::" + account + ":role/" + roleName)
                .formParam("RoleSessionName", "s1b")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
    }

    private static ValidatableResponse iam(String account, String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", authorization(account, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static String authorization(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261011/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
