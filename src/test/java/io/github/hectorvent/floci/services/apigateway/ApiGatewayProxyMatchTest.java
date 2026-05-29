package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.websocket.WebSocketConnectionManager;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.core.common.RegionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ApiGatewayProxyMatchTest {

    @Mock ApiGatewayService apiGatewayService;
    @Mock ApiGatewayV2Service apiGatewayV2Service;
    @Mock LambdaService lambdaService;
    @Mock VtlTemplateEngine vtlEngine;
    @Mock AwsServiceRouter serviceRouter;
    @Mock WebSocketConnectionManager webSocketConnectionManager;
    @Mock ElbV2Service elbV2Service;

    private ApiGatewayExecuteController ctrl;

    @BeforeEach
    void setUp() {
        ctrl = new ApiGatewayExecuteController(apiGatewayService, apiGatewayV2Service, lambdaService,
                new RegionResolver("us-east-1", "000000000000"),
                new ObjectMapper(), vtlEngine, serviceRouter, webSocketConnectionManager, elbV2Service);
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

    // ──────────────────────────── ELB_LISTENER_ARN ────────────────────────────

    @Test
    void elbListenerArnMatchesAlbAndExtractsRegion() {
        Matcher m = ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/my-lb/abcdef0123456789/0011223344556677");
        assertTrue(m.matches());
        assertEquals("us-east-1", m.group(1));
    }

    @Test
    void elbListenerArnMatchesNlb() {
        Matcher m = ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/net/my-nlb/abcdef0123456789/0011223344556677");
        assertTrue(m.matches());
        assertEquals("us-west-2", m.group(1));
    }

    @Test
    void elbListenerArnMatchesGovAndChinaPartitions() {
        assertTrue(ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "arn:aws-us-gov:elasticloadbalancing:us-gov-west-1:0:listener/app/lb/a/b").matches());
        assertTrue(ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "arn:aws-cn:elasticloadbalancing:cn-north-1:0:listener/net/lb/a/b").matches());
    }

    @Test
    void elbListenerArnRejectsNonListenerElbArns() {
        // LoadBalancer ARN, not a listener
        assertFalse(ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "arn:aws:elasticloadbalancing:us-east-1:0:loadbalancer/app/my-lb/abc").matches());
        // Target group ARN
        assertFalse(ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "arn:aws:elasticloadbalancing:us-east-1:0:targetgroup/my-tg/abc").matches());
        // Listener rule ARN
        assertFalse(ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "arn:aws:elasticloadbalancing:us-east-1:0:listener-rule/app/lb/a/b/c").matches());
    }

    @Test
    void elbListenerArnRejectsUnsupportedListenerSubtypes() {
        // gateway / unknown lb subtypes — not yet supported by HttpAlbIntegration in CDK
        assertFalse(ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "arn:aws:elasticloadbalancing:us-east-1:0:listener/gwy/lb/a/b").matches());
        assertFalse(ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "arn:aws:elasticloadbalancing:us-east-1:0:listener/unknown/lb/a/b").matches());
    }

    @Test
    void elbListenerArnRejectsOtherServiceArns() {
        // Lambda function ARN — what AWS_PROXY integrations use; must NOT trigger ELB resolution
        assertFalse(ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "arn:aws:lambda:us-east-1:0:function:my-fn").matches());
        // Plain http URL — what regular HTTP_PROXY integrations use
        assertFalse(ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher(
                "http://example.com/path").matches());
        // Empty / blank
        assertFalse(ApiGatewayExecuteController.ELB_LISTENER_ARN.matcher("").matches());
    }
}
