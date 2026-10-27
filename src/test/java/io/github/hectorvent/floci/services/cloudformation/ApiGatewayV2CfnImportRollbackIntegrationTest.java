package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.REGION;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.stack;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.stackAction;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * A stack whose AWS::ApiGatewayV2::Api declares a Body the import refuses fails to create. The
 * provisioner created the HTTP API before the Body was materialized and deletes it when that fails,
 * so neither ROLLBACK_COMPLETE nor DeleteStack leaves it behind, and an HTTP API of the same name
 * that the stack did not create is left alone. When that delete fails, the stack's rollback deletes
 * the API instead. An update that adds such an Api rolls back cleanly, and one that refuses a kept
 * API's new Body keeps the API with its routes.
 */
@QuarkusTest
class ApiGatewayV2CfnImportRollbackIntegrationTest {

    /**
     * Two JWT authorizers and the route of the first operation are materialized before the second
     * operation, an AND of both schemes, is refused: a V2 route attaches only one authorizer.
     */
    private static final String API = """
            "Api":{"Type":"AWS::ApiGatewayV2::Api","Properties":{
              "Name":"%1$s","ProtocolType":"HTTP",
              "Body":{"openapi":"3.0.1","info":{"title":"%1$s","version":"1"},
                "components":{"securitySchemes":{
                  "a":{"type":"oauth2","x-amazon-apigateway-authorizer":{"type":"jwt",
                    "identitySource":"$request.header.Authorization",
                    "jwtConfiguration":{"issuer":"https://issuer.example.com","audience":["a"]}}},
                  "b":{"type":"oauth2","x-amazon-apigateway-authorizer":{"type":"jwt",
                    "identitySource":"$request.header.Authorization",
                    "jwtConfiguration":{"issuer":"https://issuer.example.com","audience":["b"]}}}}},
                "paths":{"/open":{"get":{"responses":{"200":{"description":"ok"}}}}%2$s}}}}
            """;
    private static final String REFUSED_OPERATION =
            ",\"/both\":{\"get\":{\"security\":[{\"a\":[],\"b\":[]}],\"responses\":{\"200\":{\"description\":\"ok\"}}}}";

    @InjectSpy
    ApiGatewayV2Service apiGatewayV2Service;

    @Inject
    CloudFormationService cloudFormationService;

    /** The test's stack name, which also names its APIs. */
    private String name;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Removes what a test left, so a failed test leaves nothing for another to find. */
    @AfterEach
    void cleanUp() {
        if (name == null) {
            return;
        }
        doCallRealMethod().when(apiGatewayV2Service).deleteApi(anyString(), anyString());
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", name)
                .when().post("/");
        CfnStackWaits.awaitStackDeleted(name);
        for (String apiName : List.of(name, name + "-keep")) {
            apisNamed(apiName).forEach(apiId -> apiGatewayV2Service.deleteApi(REGION, apiId));
        }
    }

    @Test
    void aCreateWhoseBodyIsRefusedLeavesNoApiBehind() {
        name = uniqueName("create");
        String outsideId = apiGatewayV2Service.createApi(REGION, Map.of(
                "name", name, "protocolType", "HTTP", "description", "outside the stack")).getApiId();

        stackAction("CreateStack", name, template(refusedApi(name)));
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(name);
        assertEquals("ROLLBACK_COMPLETE", state.status(), state.reason());
        assertThat(CfnStackWaits.failedResourceReason(name),
                containsString("do not support AND security requirements"));
        String createdId = stack(cloudFormationService, name).getResources().get("Api").getPhysicalId();
        assertNotNull(createdId, "the failed Api resource carries the id of the API it created");
        assertNotEquals(outsideId, createdId);

        assertFalse(apiGatewayV2Service.hasApi(REGION, createdId), "the API the stack created is gone");
        assertEquals(List.of(outsideId), apisNamed(name));

        deleteStack(name);
        assertEquals(List.of(outsideId), apisNamed(name));
        assertEquals("outside the stack", apiGatewayV2Service.getApi(REGION, outsideId).getDescription());
    }

    @Test
    void aCreateWhoseApiCannotBeDeletedLeavesItToTheStackRollback() {
        name = uniqueName("undo");
        AtomicInteger deletes = new AtomicInteger();
        doAnswer(invocation -> {
            if (deletes.getAndIncrement() == 0) {
                throw new AwsException("TooManyRequestsException", "Too Many Requests", 429);
            }
            return invocation.callRealMethod();
        }).when(apiGatewayV2Service).deleteApi(eq(REGION), anyString());

        stackAction("CreateStack", name, template(refusedApi(name)));
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(name);

        assertEquals("ROLLBACK_COMPLETE", state.status(), state.reason());
        assertEquals(2, deletes.get(), "the provision's delete was refused, and the stack's rollback deleted it");
        assertEquals(List.of(), apisNamed(name));
    }

    @Test
    void anUpdateThatAddsAnApiWhoseBodyIsRefusedRollsItBack() {
        name = uniqueName("add");
        String keep = """
                "Keep":{"Type":"AWS::ApiGatewayV2::Api","Properties":{"Name":"%s-keep","ProtocolType":"HTTP"}}
                """.formatted(name);
        stackAction("CreateStack", name, template(keep));
        CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");
        String keepId = stack(cloudFormationService, name).getResources().get("Keep").getPhysicalId();

        stackAction("UpdateStack", name, template(keep + "," + refusedApi(name)));
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(name);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
        assertFalse(stack(cloudFormationService, name).getResources().containsKey("Api"));
        assertEquals(List.of(), apisNamed(name), "the API the update created is gone");
        assertTrue(apiGatewayV2Service.hasApi(REGION, keepId));
    }

    @Test
    void anUpdateWhoseBodyIsRefusedKeepsTheApiAndItsRoutes() {
        name = uniqueName("keep");
        stackAction("CreateStack", name, template(API.formatted(name, "")));
        CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");
        String keptId = stack(cloudFormationService, name).getResources().get("Api").getPhysicalId();
        assertEquals(List.of("GET /open"), routeKeys(keptId));

        stackAction("UpdateStack", name, template(refusedApi(name)));
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(name);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
        assertEquals(keptId, stack(cloudFormationService, name).getResources().get("Api").getPhysicalId());
        assertTrue(apiGatewayV2Service.hasApi(REGION, keptId), "the stack still holds the API it kept");
        assertEquals(List.of("GET /open"), routeKeys(keptId));

        deleteStack(name);
        assertFalse(apiGatewayV2Service.hasApi(REGION, keptId), "DeleteStack removes the kept API");
    }

    private static String uniqueName(String test) {
        return "cfn-v2-import-rollback-" + test + "-" + Long.toString(System.nanoTime(), 36);
    }

    private static String refusedApi(String name) {
        return API.formatted(name, REFUSED_OPERATION);
    }

    private List<String> apisNamed(String apiName) {
        return apiGatewayV2Service.getApis(REGION).stream()
                .filter(api -> apiName.equals(api.getName()))
                .map(Api::getApiId)
                .toList();
    }

    private List<String> routeKeys(String apiId) {
        return apiGatewayV2Service.getRoutes(REGION, apiId).stream().map(Route::getRouteKey).sorted().toList();
    }

    private static String template(String resources) {
        return "{\"Resources\":{" + resources + "}}";
    }

    private static void deleteStack(String stackName) {
        stackAction("DeleteStack", stackName, null);
        CfnStackWaits.awaitStackDeleted(stackName);
    }
}
