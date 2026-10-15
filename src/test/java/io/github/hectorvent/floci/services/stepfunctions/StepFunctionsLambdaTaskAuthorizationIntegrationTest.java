package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Under IAM enforcement, a Lambda Task calls Invoke as the state machine's execution role. On a
 * function in another account that needs the role's own permission and a grant in the function's
 * policy (Step Functions Developer Guide, "I can't invoke a Lambda function from another account";
 * IAM User Guide, cross-account policy evaluation logic); in the role's own account either is enough.
 * A task whose {@code Credentials} name a role calls Invoke as that role, once the execution role
 * has assumed it (Developer Guide, "Accessing cross-account AWS resources in Step Functions").
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsLambdaTaskAuthorizationIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String SFN_ACCOUNT = "565656565656";
    private static final String FUNCTION_ACCOUNT = "787878787878";
    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String INVOKE_ANYTHING = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"*"}]}""";
    private static final String ASSUME_ANY_ROLE = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"}]}""";
    private static final String STATES_TRUST = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"Service":"states.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

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
        when(executor.invoke(any(), any(), any()))
                .thenReturn(new InvokeResult(200, null, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null, "req"));
    }

    @AfterEach
    void deleteWhatTheTestCreated() {
        while (!cleanup.isEmpty()) {
            cleanup.pop().run();
        }
    }

    @Test
    void aGrantToTheExecutionRoleLetsTheTaskInvokeTheFunction() {
        String roleArn = createRole("sfn-exec-" + suffix(), INVOKE_ANYTHING);
        String functionArn = createFunction(FUNCTION_ACCOUNT, "worker-" + suffix());
        addPermission(functionArn, null, Map.of("Principal", roleArn));

        runTask(roleArn, functionArn)
                .body("status", equalTo("SUCCEEDED"))
                .body("output", equalTo("{\"ok\":true}"));

        verify(executor).invoke(argThat(isFunction(functionArn)), any(), any());
    }

    @Test
    void aFunctionWhosePolicyGrantsNothingFailsTheTask() {
        String roleName = "sfn-exec-" + suffix();
        String roleArn = createRole(roleName, INVOKE_ANYTHING);
        String functionArn = createFunction(FUNCTION_ACCOUNT, "worker-" + suffix());

        runTask(roleArn, functionArn)
                .body("status", equalTo("FAILED"))
                .body("error", equalTo("Lambda.AccessDeniedException"))
                .body("cause", equalTo("User: arn:aws:sts::" + SFN_ACCOUNT + ":assumed-role/" + roleName
                        + " is not authorized to perform: lambda:InvokeFunction on resource: " + functionArn
                        + " because no resource-based policy allows the lambda:InvokeFunction action"));

        verify(executor, never()).invoke(argThat(isFunction(functionArn)), any(), any());
    }

    @Test
    void aRoleWithoutPermissionToInvokeFailsTheTaskDespiteTheGrant() {
        String roleArn = createRole("sfn-exec-" + suffix(), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""");
        String functionArn = createFunction(FUNCTION_ACCOUNT, "worker-" + suffix());
        addPermission(functionArn, null, Map.of("Principal", roleArn));

        runTask(roleArn, functionArn)
                .body("status", equalTo("FAILED"))
                .body("error", equalTo("Lambda.AccessDeniedException"))
                .body("cause", containsString("because no identity-based policy allows the lambda:InvokeFunction action"));

        verify(executor, never()).invoke(argThat(isFunction(functionArn)), any(), any());
    }

    @Test
    void aGrantOnAnAliasCoversTasksThatNameTheAliasOnly() {
        String roleArn = createRole("sfn-exec-" + suffix(), INVOKE_ANYTHING);
        String name = "worker-" + suffix();
        String functionArn = createFunction(FUNCTION_ACCOUNT, name);
        publishVersionAndAlias(name, "live");
        addPermission(functionArn, "live", Map.of("Principal", SFN_ACCOUNT));

        runTask(roleArn, functionArn + ":live").body("status", equalTo("SUCCEEDED"));
        runTask(roleArn, functionArn)
                .body("status", equalTo("FAILED"))
                .body("error", equalTo("Lambda.AccessDeniedException"));
    }

    @Test
    void inItsOwnAccountTheRolesPermissionIsEnough() {
        String roleArn = createRole("sfn-exec-" + suffix(), INVOKE_ANYTHING);
        String functionArn = createFunction(SFN_ACCOUNT, "own-" + suffix());

        runTask(roleArn, functionArn).body("status", equalTo("SUCCEEDED"));
    }

    @Test
    void inItsOwnAccountARoleWithoutPermissionFailsTheTask() {
        String roleArn = createRole("sfn-exec-" + suffix(), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""");
        String functionArn = createFunction(SFN_ACCOUNT, "own-" + suffix());

        runTask(roleArn, functionArn)
                .body("status", equalTo("FAILED"))
                .body("error", equalTo("Lambda.AccessDeniedException"))
                .body("cause", containsString("because no identity-based policy allows the lambda:InvokeFunction action"));

        verify(executor, never()).invoke(argThat(isFunction(functionArn)), any(), any());
    }

    @Test
    void aTaskWithCredentialsInvokesAsTheRoleTheyNameInTheFunctionsAccount() {
        String executionRoleArn = createRole("sfn-exec-" + suffix(), ASSUME_ANY_ROLE);
        String stateMachineName = "cross-account-" + suffix();
        String stateMachineArn = "arn:aws:states:" + REGION + ":" + SFN_ACCOUNT + ":stateMachine:" + stateMachineName;
        String taskRoleArn = createRole(FUNCTION_ACCOUNT, "LambdaRole-" + suffix(), "/", """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"AWS":"%s"},"Action":"sts:AssumeRole",
                  "Condition":{"StringEquals":{"sts:ExternalId":"%s"}}}]}"""
                .formatted(executionRoleArn, stateMachineArn), INVOKE_ANYTHING);
        String functionArn = createFunction(FUNCTION_ACCOUNT, "worker-" + suffix());

        run(executionRoleArn, stateMachineName, """
                {"StartAt":"Call","States":{"Call":{"Type":"Task","Resource":"%s",
                  "Credentials":{"RoleArn":"%s"},"End":true}}}""".formatted(functionArn, taskRoleArn), "{}")
                .body("status", equalTo("SUCCEEDED"));

        verify(executor).invoke(argThat(isFunction(functionArn)), any(), any());
    }

    @Test
    void inItsOwnAccountATaskWithCredentialsNeedsOnlyThatRoleToInvoke() {
        String executionRoleArn = createRole("sfn-exec-" + suffix(), ASSUME_ANY_ROLE);
        String taskRoleArn = createRole(SFN_ACCOUNT, "invoker-" + suffix(), "/", trusting(executionRoleArn),
                INVOKE_ANYTHING);
        String functionArn = createFunction(SFN_ACCOUNT, "own-" + suffix());

        run(executionRoleArn, "own-account-" + suffix(), """
                {"StartAt":"Call","States":{"Call":{"Type":"Task","Resource":"arn:aws:states:::lambda:invoke",
                  "Parameters":{"FunctionName":"%s"},"Credentials":{"RoleArn.$":"$.roleArn"},"End":true}}}"""
                .formatted(functionArn), "{\"roleArn\":\"" + taskRoleArn + "\"}")
                .body("status", equalTo("SUCCEEDED"));

        verify(executor).invoke(argThat(isFunction(functionArn)), any(), any());
    }

    @Test
    void aRoleThatDoesNotTrustTheExecutionRoleFailsTheTaskWithStatesPermissions() {
        String executionRoleName = "sfn-exec-" + suffix();
        String executionRoleArn = createRole(executionRoleName, ASSUME_ANY_ROLE);
        String taskRoleArn = createRole(FUNCTION_ACCOUNT, "LambdaRole-" + suffix(), "/", STATES_TRUST, INVOKE_ANYTHING);
        String functionArn = createFunction(FUNCTION_ACCOUNT, "worker-" + suffix());

        run(executionRoleArn, "cross-account-" + suffix(), """
                {"StartAt":"Call","States":{"Call":{"Type":"Task","Resource":"%s",
                  "Credentials":{"RoleArn":"%s"},"End":true}}}""".formatted(functionArn, taskRoleArn), "{}")
                .body("status", equalTo("FAILED"))
                .body("error", equalTo("States.Permissions"))
                .body("cause", equalTo("User: arn:aws:sts::" + SFN_ACCOUNT + ":assumed-role/" + executionRoleName
                        + " is not authorized to perform: sts:AssumeRole on resource: " + taskRoleArn
                        + " because no role trust policy allows the sts:AssumeRole action"));

        verify(executor, never()).invoke(argThat(isFunction(functionArn)), any(), any());
    }

    private ValidatableResponse runTask(String roleArn, String resource) {
        return run(roleArn, "cross-account-" + suffix(), """
                {"StartAt":"Call","States":{"Call":{"Type":"Task","Resource":"%s","End":true}}}"""
                .formatted(resource), "{}");
    }

    private ValidatableResponse run(String roleArn, String stateMachineName, String definition, String input) {
        String stateMachineArn = createStateMachine(roleArn, stateMachineName, definition);
        return sfn("StartSyncExecution", Map.of("stateMachineArn", stateMachineArn, "input", input))
                .statusCode(200);
    }

    private String createStateMachine(String roleArn, String name, String definition) {
        String stateMachineArn = sfn("CreateStateMachine", Map.of(
                "name", name,
                "type", "EXPRESS",
                "roleArn", roleArn,
                "definition", definition))
                .statusCode(200)
                .extract().path("stateMachineArn");
        cleanup.push(() -> sfn("DeleteStateMachine", Map.of("stateMachineArn", stateMachineArn)).statusCode(200));
        return stateMachineArn;
    }

    private static ValidatableResponse sfn(String operation, Map<String, String> request) {
        return given()
                .header("Authorization", authorization(SFN_ACCOUNT, "states"))
                .header("X-Amz-Target", "AWSStepFunctions." + operation)
                .contentType(SFN_CONTENT_TYPE)
                .body(request)
        .when()
                .post("/")
        .then();
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

    private static void publishVersionAndAlias(String name, String alias) {
        lambda(FUNCTION_ACCOUNT).body("{}").post("/2015-03-31/functions/{name}/versions", name).then().statusCode(201);
        lambda(FUNCTION_ACCOUNT)
                .body("{\"Name\":\"" + alias + "\",\"FunctionVersion\":\"1\"}")
                .post("/2015-03-31/functions/{name}/aliases", name)
        .then()
                .statusCode(201);
    }

    private static void addPermission(String functionArn, String qualifier, Map<String, Object> grant) {
        String account = functionArn.split(":")[4];
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

    private static RequestSpecification lambda(String account) {
        return given()
                .header("Authorization", authorization(account, "lambda"))
                .contentType("application/json");
    }

    /** An execution role, which Step Functions assumes. */
    private String createRole(String roleName, String policyDocument) {
        return createRole(SFN_ACCOUNT, roleName, "/service-role/", STATES_TRUST, policyDocument);
    }

    private String createRole(String account, String roleName, String path, String trustPolicy,
                              String policyDocument) {
        String roleArn = iam(account, "CreateRole", Map.of(
                "RoleName", roleName,
                "Path", path,
                "AssumeRolePolicyDocument", trustPolicy))
                .statusCode(200)
                .extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.push(() -> iam(account, "DeleteRole", Map.of("RoleName", roleName)).statusCode(200));
        iam(account, "PutRolePolicy", Map.of(
                "RoleName", roleName,
                "PolicyName", "inline",
                "PolicyDocument", policyDocument)).statusCode(200);
        cleanup.push(() -> iam(account, "DeleteRolePolicy",
                Map.of("RoleName", roleName, "PolicyName", "inline")).statusCode(200));
        return roleArn;
    }

    private static String trusting(String roleArn) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"%s"},"Action":"sts:AssumeRole"}]}""".formatted(roleArn);
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
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261012/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
