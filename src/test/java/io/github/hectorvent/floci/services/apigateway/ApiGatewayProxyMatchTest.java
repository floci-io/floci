package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.websocket.WebSocketConnectionManager;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.core.common.RegionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ApiGatewayProxyMatchTest {

    @Mock ApiGatewayService apiGatewayService;
    @Mock ApiGatewayV2Service apiGatewayV2Service;
    @Mock LambdaService lambdaService;
    @Mock VtlTemplateEngine vtlEngine;
    @Mock AwsServiceRouter serviceRouter;
    @Mock WebSocketConnectionManager webSocketConnectionManager;

    private ApiGatewayExecuteController ctrl;

    @BeforeEach
    void setUp() {
        ctrl = new ApiGatewayExecuteController(apiGatewayService, apiGatewayV2Service, lambdaService,
                new RegionResolver("us-east-1", "000000000000"),
                new ObjectMapper(), vtlEngine, serviceRouter, webSocketConnectionManager);
    }

    private ApiGatewayResource resource(String id, String parentId, String pathPart, String path) {
        ApiGatewayResource r = new ApiGatewayResource();
        r.setId(id);
        r.setParentId(parentId);
        r.setPathPart(pathPart);
        r.setPath(path);
        return r;
    }

    @Test
    void rootProxyMatchesEverything() {
        ApiGatewayResource rootProxy = resource("r1", "root", "{proxy+}", "/{proxy+}");

        assertSame(rootProxy, ctrl.matchResource(List.of(rootProxy), "/anything"));
        assertSame(rootProxy, ctrl.matchResource(List.of(rootProxy), "/"));
        assertSame(rootProxy, ctrl.matchResource(List.of(rootProxy), "/a/b/c"));
    }

    @Test
    void siblingProxyRoutesToCorrectParent() {
        ApiGatewayResource authProxy = resource("r1", "root", "{proxy+}", "/auth/{proxy+}");
        ApiGatewayResource productsProxy = resource("r2", "root", "{proxy+}", "/products/{proxy+}");
        List<ApiGatewayResource> resources = List.of(authProxy, productsProxy);

        assertSame(authProxy, ctrl.matchResource(resources, "/auth/login"));
        assertSame(productsProxy, ctrl.matchResource(resources, "/products/123"));
        assertSame(productsProxy, ctrl.matchResource(resources, "/products/abc/def"));
        assertNull(ctrl.matchResource(resources, "/other"));
    }

    @Test
    void emptyProxySegmentDoesNotMatchNonRootProxy() {
        ApiGatewayResource authProxy = resource("r1", "root", "{proxy+}", "/auth/{proxy+}");
        ApiGatewayResource productsProxy = resource("r2", "root", "{proxy+}", "/products/{proxy+}");

        assertNull(ctrl.matchResource(List.of(authProxy), "/auth/"));
        assertNull(ctrl.matchResource(List.of(authProxy, productsProxy), "/auth/"));
        assertNull(ctrl.matchResource(List.of(authProxy, productsProxy), "/products/"));
    }

    @Test
    void rootProxyFallbackWhenNoSpecificMatch() {
        ApiGatewayResource rootProxy = resource("r0", "root", "{proxy+}", "/{proxy+}");
        ApiGatewayResource authProxy = resource("r1", "root", "{proxy+}", "/auth/{proxy+}");
        List<ApiGatewayResource> resources = List.of(rootProxy, authProxy);

        assertSame(authProxy, ctrl.matchResource(resources, "/auth/login"));
        assertSame(rootProxy, ctrl.matchResource(resources, "/other"));
        assertSame(rootProxy, ctrl.matchResource(resources, "/"));
    }

    @Test
    void exactMatchTakesPriorityOverProxy() {
        ApiGatewayResource exact = resource("r1", "root", "login", "/auth/login");
        ApiGatewayResource authProxy = resource("r2", "root", "{proxy+}", "/auth/{proxy+}");
        List<ApiGatewayResource> resources = List.of(authProxy, exact);

        assertSame(exact, ctrl.matchResource(resources, "/auth/login"));
        assertSame(authProxy, ctrl.matchResource(resources, "/auth/register"));
    }

    @Test
    void templatePathTakesPriorityOverProxy() {
        ApiGatewayResource itemDetail = resource("r1", "root", "{id}", "/items/{id}");
        ApiGatewayResource itemsProxy = resource("r2", "root", "{proxy+}", "/items/{proxy+}");
        List<ApiGatewayResource> resources = List.of(itemsProxy, itemDetail);

        assertSame(itemDetail, ctrl.matchResource(resources, "/items/123"));
        assertSame(itemsProxy, ctrl.matchResource(resources, "/items/123/sub"));
    }

    @Test
    void longestParentPrefixWins() {
        ApiGatewayResource apiProxy = resource("r1", "root", "{proxy+}", "/api/{proxy+}");
        ApiGatewayResource apiV1Proxy = resource("r2", "root", "{proxy+}", "/api/v1/{proxy+}");
        List<ApiGatewayResource> resources = List.of(apiV1Proxy, apiProxy);

        assertSame(apiV1Proxy, ctrl.matchResource(resources, "/api/v1/users"));
        assertSame(apiProxy, ctrl.matchResource(resources, "/api/v2"));
        assertNull(ctrl.matchResource(resources, "/api/"));
    }

    @Test
    void noMatchReturnsNull() {
        ApiGatewayResource authProxy = resource("r1", "root", "{proxy+}", "/auth/{proxy+}");

        assertNull(ctrl.matchResource(List.of(authProxy), "/"));
        assertNull(ctrl.matchResource(List.of(authProxy), "/other/path"));
    }
}
