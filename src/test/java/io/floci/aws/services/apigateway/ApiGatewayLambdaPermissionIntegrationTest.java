package io.floci.aws.services.apigateway;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.services.apigateway.ApiGatewayLambdaFixtures.ACCOUNT;
import static io.floci.aws.services.apigateway.ApiGatewayLambdaFixtures.REGION;
import static io.floci.aws.services.apigateway.ApiGatewayLambdaFixtures.apiGateway;
import static io.floci.aws.services.apigateway.ApiGatewayLambdaFixtures.createLambdaRestApi;
import static io.floci.aws.services.apigateway.ApiGatewayLambdaFixtures.signed;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * With IAM enforcement on, a REST API Lambda integration invokes the function only when the
 * function policy allows apigateway.amazonaws.com to, for the request's execute-api ARN when the
 * grant names one. That ARN carries the concrete request path and the function's Region. A refused call is answered with the gateway's 500 "Internal server error" and
 * never reaches the function. An integration that names {@code credentials} invokes as that role,
 * so the function policy does not decide it.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ApiGatewayLambdaPermissionIntegrationTest {

    private static final String OTHER_REGION = "us-west-2";
    private static final String PROXY_RESPONSE = "{\"statusCode\":200,\"body\":\"invoked\"}";

    @InjectSpy
    LambdaService lambdaService;

    private final Deque<Runnable> cleanup = new ArrayDeque<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubInvocation() {
        clearInvocations(lambdaService);
        doReturn(new InvokeResult(200, null, PROXY_RESPONSE.getBytes(StandardCharsets.UTF_8), null, "req"))
                .when(lambdaService).invokeTarget(any(), any(), any(), eq(InvocationType.RequestResponse));
    }

    @AfterEach
    void deleteWhatTheTestCreated() {
        while (!cleanup.isEmpty()) {
            cleanup.pop().run();
        }
    }

    @Test
    void proxyIntegrationToAFunctionWithoutAPolicyAnswersInternalServerError() {
        String functionArn = createFunction("no-policy-" + suffix());
        String apiId = createApi("AWS_PROXY", functionArn, null);

        execute(apiId)
                .statusCode(500)
                .body("message", equalTo("Internal server error"));

        verify(lambdaService, never()).invokeTarget(any(), any(), any(), any());
    }

    @Test
    void proxyIntegrationGrantedForTheMethodInvokesTheFunction() {
        String functionArn = createFunction("granted-" + suffix());
        String apiId = createApi("AWS_PROXY", functionArn, null);
        addPermission(functionArn, "arn:aws:execute-api:" + REGION + ":" + ACCOUNT + ":" + apiId + "/*/GET/items");

        execute(apiId).statusCode(200).body(equalTo("invoked"));

        verify(lambdaService).invokeTarget(any(), eq(functionArn), any(), eq(InvocationType.RequestResponse));
    }

    @Test
    void grantForAnotherApiDoesNotCoverThisOne() {
        String functionArn = createFunction("other-api-" + suffix());
        String apiId = createApi("AWS_PROXY", functionArn, null);
        addPermission(functionArn, "arn:aws:execute-api:" + REGION + ":" + ACCOUNT + ":otherapi/*/GET/items");

        execute(apiId).statusCode(500);

        verify(lambdaService, never()).invokeTarget(any(), any(), any(), any());
    }

    @Test
    void nonProxyIntegrationToAFunctionWithoutAPolicyAnswersInternalServerError() {
        String functionArn = createFunction("non-proxy-" + suffix());
        String apiId = createApi("AWS", functionArn, null);

        execute(apiId)
                .statusCode(500)
                .body("message", equalTo("Internal server error"));

        verify(lambdaService, never()).invokeTarget(any(), any(), any(), any());
    }

    @Test
    void anyMethodIsAuthorizedForTheRequestVerb() {
        String functionArn = createFunction("any-method-" + suffix());
        String apiId = createApi("/items", "ANY", "AWS_PROXY", functionArn, null);
        addPermission(functionArn, "arn:aws:execute-api:" + REGION + ":" + ACCOUNT + ":" + apiId + "/*/GET/items");

        execute(apiId).statusCode(200).body(equalTo("invoked"));
        given().when().delete("/execute-api/" + apiId + "/test/items").then().statusCode(500);

        verify(lambdaService).invokeTarget(any(), eq(functionArn), any(), eq(InvocationType.RequestResponse));
    }

    @Test
    void proxyIntegrationToAMissingFunctionAnswersInternalServerError() {
        String functionArn = "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:missing-" + suffix();
        String apiId = createApi("AWS_PROXY", functionArn, null);

        execute(apiId)
                .statusCode(500)
                .body("message", equalTo("Internal server error"));

        verify(lambdaService, never()).invokeTarget(any(), any(), any(), any());
    }

    @Test
    void integrationWithCredentialsIsNotDecidedByTheFunctionPolicy() {
        String functionArn = createFunction("role-invoked-" + suffix());
        String apiId = createApi("AWS_PROXY", functionArn, "arn:aws:iam::" + ACCOUNT + ":role/apigateway-invoke");

        execute(apiId).statusCode(200);

        verify(lambdaService).invokeTarget(any(), eq(functionArn), any(), eq(InvocationType.RequestResponse));
    }

    @Test
    void grantForTheConcreteRequestPathCoversAPathParameterResource() {
        String functionArn = createFunction("path-param-" + suffix());
        String apiId = createApi("/items/{id}", "GET", "AWS_PROXY", functionArn, null);
        addPermission(functionArn, "arn:aws:execute-api:" + REGION + ":" + ACCOUNT + ":" + apiId + "/*/GET/items/42");

        execute(apiId, "/items/42").statusCode(200).body(equalTo("invoked"));

        verify(lambdaService).invokeTarget(any(), eq(functionArn), any(), eq(InvocationType.RequestResponse));
    }

    @Test
    void grantForAnotherPathParameterValueDoesNotCoverThisOne() {
        String functionArn = createFunction("other-item-" + suffix());
        String apiId = createApi("/items/{id}", "GET", "AWS_PROXY", functionArn, null);
        addPermission(functionArn, "arn:aws:execute-api:" + REGION + ":" + ACCOUNT + ":" + apiId + "/*/GET/items/7");

        execute(apiId, "/items/42").statusCode(500);

        verify(lambdaService, never()).invokeTarget(any(), any(), any(), any());
    }

    @Test
    void grantForTheConcreteRequestPathCoversAGreedyResource() {
        String functionArn = createFunction("greedy-" + suffix());
        String apiId = createApi("/items/{proxy+}", "GET", "AWS_PROXY", functionArn, null);
        addPermission(functionArn, "arn:aws:execute-api:" + REGION + ":" + ACCOUNT + ":" + apiId + "/*/GET/items/a/b");

        execute(apiId, "/items/a/b").statusCode(200).body(equalTo("invoked"));

        verify(lambdaService).invokeTarget(any(), eq(functionArn), any(), eq(InvocationType.RequestResponse));
    }

    @Test
    void crossRegionFunctionIsAuthorizedInTheFunctionsRegion() {
        String functionArn = createFunction(OTHER_REGION, "cross-region-" + suffix());
        String apiId = createApi("AWS_PROXY", functionArn, null);
        addPermission(functionArn, "arn:aws:execute-api:" + OTHER_REGION + ":" + ACCOUNT + ":" + apiId + "/*/GET/items");

        execute(apiId).statusCode(200).body(equalTo("invoked"));

        verify(lambdaService).invokeTarget(any(), eq(functionArn), any(), eq(InvocationType.RequestResponse));
    }

    @Test
    void crossRegionGrantInTheApisRegionDoesNotCoverTheCall() {
        String functionArn = createFunction(OTHER_REGION, "api-region-" + suffix());
        String apiId = createApi("AWS_PROXY", functionArn, null);
        addPermission(functionArn, "arn:aws:execute-api:" + REGION + ":" + ACCOUNT + ":" + apiId + "/*/GET/items");

        execute(apiId).statusCode(500);

        verify(lambdaService, never()).invokeTarget(any(), any(), any(), any());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static ValidatableResponse execute(String apiId) {
        return execute(apiId, "/items");
    }

    private static ValidatableResponse execute(String apiId, String path) {
        return given().when().get("/execute-api/" + apiId + "/test" + path).then();
    }

    private String createApi(String type, String functionArn, String credentials) {
        return createApi("/items", "GET", type, functionArn, credentials);
    }

    private String createApi(String resourcePath, String httpMethod, String type, String functionArn,
                             String credentials) {
        String apiId = createLambdaRestApi(resourcePath, httpMethod, type, functionArn, credentials);
        cleanup.push(() -> apiGateway().delete("/restapis/" + apiId).then().statusCode(202));
        return apiId;
    }

    private String createFunction(String name) {
        return createFunction(REGION, name);
    }

    private String createFunction(String region, String name) {
        lambda(region)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Handler":"index.handler",
                         "Role":"arn:aws:iam::%s:role/lambda-role"}""".formatted(name, ACCOUNT))
        .when()
                .post("/2015-03-31/functions")
        .then()
                .statusCode(201);
        cleanup.push(() -> lambda(region).delete("/2015-03-31/functions/{name}", name).then().statusCode(204));
        return "arn:aws:lambda:" + region + ":" + ACCOUNT + ":function:" + name;
    }

    private static void addPermission(String functionArn, String sourceArn) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("StatementId", "s-" + suffix());
        request.put("Action", "lambda:InvokeFunction");
        request.put("Principal", "apigateway.amazonaws.com");
        request.put("SourceArn", sourceArn);
        lambda(AwsArnUtils.parse(functionArn).region()).body(request)
                .post("/2015-03-31/functions/{name}/policy", functionArn).then().statusCode(201);
    }

    private static RequestSpecification lambda(String region) {
        return signed("lambda", region).contentType("application/json");
    }
}
