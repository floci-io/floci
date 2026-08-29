package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

@QuarkusTest
class CloudFormationApiGatewayV2CleanupIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ROUTE_IDS_ATTR = "__FlociApiGatewayV2BodyRouteIds";

    @InjectSpy
    ApiGatewayV2Service apiGatewayV2Service;

    @Inject
    CloudFormationService cloudFormationService;

    private String stackName;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteStack() {
        if (stackName != null) {
            doCallRealMethod().when(apiGatewayV2Service)
                    .deleteRoute(anyString(), anyString(), anyString());
            given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteStack")
                    .formParam("StackName", stackName)
            .when()
                    .post("/");
        }
    }

    @Test
    void failedUpdatePersistsOwnershipOfAReplacementThatCleanupCouldNotDelete() {
        String suffix = Long.toString(System.nanoTime(), 36);
        stackName = "http-api-cleanup-" + suffix;
        String apiName = "http-api-cleanup-" + suffix;

        createStack(httpApiTemplate(apiName, List.of("/before")));
        waitForStackStatus("CREATE_COMPLETE");
        String apiId = apiIdForName(apiName);
        String oldRouteId = apiGatewayV2Service.findRouteByKey(REGION, apiId, "GET /before").getRouteId();

        AtomicReference<String> survivingRouteId = new AtomicReference<>();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = invocation.getArgument(2);
            if ("GET /fail".equals(request.get("routeKey"))) {
                throw new AwsException("InternalFailure", "simulated route failure", 500);
            }
            Route route = (Route) invocation.callRealMethod();
            if ("GET /survivor".equals(route.getRouteKey())) {
                survivingRouteId.set(route.getRouteId());
            }
            return route;
        }).when(apiGatewayV2Service).createRoute(eq(REGION), eq(apiId), anyMap());
        doAnswer(invocation -> {
            String routeId = invocation.getArgument(2);
            if (routeId.equals(survivingRouteId.get())) {
                throw new AwsException("InternalFailure", "simulated cleanup failure", 500);
            }
            return invocation.callRealMethod();
        }).when(apiGatewayV2Service).deleteRoute(eq(REGION), eq(apiId), anyString());

        updateStack(httpApiTemplate(apiName, List.of("/survivor", "/fail")));
        waitForStackStatus("UPDATE_ROLLBACK_COMPLETE");

        StackResource persisted = cloudFormationService.describeStacks(stackName, REGION)
                .getFirst().getResources().get("HttpApi");
        assertEquals("CREATE_COMPLETE", persisted.getStatus());
        assertTrue(Arrays.asList(persisted.getAttributes().get(ROUTE_IDS_ATTR).split(","))
                .containsAll(List.of(oldRouteId, survivingRouteId.get())));
    }

    @Test
    void failedSnapshotRestorationMarksUpdateRollbackFailed() {
        String suffix = Long.toString(System.nanoTime(), 36);
        stackName = "http-api-restore-failure-" + suffix;
        String apiName = "http-api-restore-failure-" + suffix;

        createStack(httpApiTemplate(apiName, List.of("/before")));
        waitForStackStatus("CREATE_COMPLETE");
        String apiId = apiIdForName(apiName);
        String oldRouteId = apiGatewayV2Service.findRouteByKey(REGION, apiId, "GET /before").getRouteId();

        AtomicReference<String> independentRouteId = new AtomicReference<>();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = invocation.getArgument(2);
            if ("GET /fail".equals(request.get("routeKey"))) {
                Route independent = apiGatewayV2Service.createRoute(
                        REGION, apiId, Map.of("routeKey", "GET /before"));
                independentRouteId.set(independent.getRouteId());
                throw new AwsException("InternalFailure", "simulated route failure", 500);
            }
            return invocation.callRealMethod();
        }).when(apiGatewayV2Service).createRoute(eq(REGION), eq(apiId), anyMap());

        updateStack(httpApiTemplate(apiName, List.of("/fail")));
        waitForStackStatus("UPDATE_ROLLBACK_FAILED");

        Route independent = apiGatewayV2Service.findRouteByKey(REGION, apiId, "GET /before");
        assertEquals(independentRouteId.get(), independent.getRouteId());
        assertThrows(AwsException.class,
                () -> apiGatewayV2Service.getRoute(REGION, apiId, oldRouteId));
    }

    private void createStack(String template) {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private void updateStack(String template) {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "UpdateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private void waitForStackStatus(String expected) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String status = cloudFormationService.describeStacks(stackName, REGION).getFirst().getStatus();
            if (expected.equals(status)) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for stack status", e);
            }
        }
        throw new AssertionError("Stack did not reach " + expected);
    }

    private static String apiIdForName(String apiName) {
        return given()
        .when()
                .get("/v2/apis")
        .then()
                .statusCode(200)
                .extract()
                .path("items.find { it.name == '" + apiName + "' }.apiId");
    }

    private static String httpApiTemplate(String apiName, List<String> paths) {
        String pathDefinitions = paths.stream()
                .map(path -> "\"" + path + "\":{\"get\":{}}")
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"AWSTemplateFormatVersion":"2010-09-09",
                 "Transform":"AWS::Serverless-2016-10-31",
                 "Resources":{"HttpApi":{"Type":"AWS::Serverless::HttpApi","Properties":{
                   "Name":"%s","DefinitionBody":{"openapi":"3.0.1","paths":{%s}}
                 }}}}
                """.formatted(apiName, pathDefinitions);
    }
}
