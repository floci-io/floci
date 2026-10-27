package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiGatewayExecuteApiHostFilterDecisionTest {

    private static final String API_ID = "abc123";
    private static final String REGION = "us-east-1";
    private static final String VIEWER_ACCOUNT = "000000000000";
    private static final String OWNER_ACCOUNT = "111122223333";
    private static final String HOST = API_ID + ".execute-api." + REGION + ".amazonaws.com";
    private static final String WRONG_REGION_HOST = API_ID + ".execute-api.eu-west-1.amazonaws.com";

    private final RequestContext viewer = viewerContext();
    private final StubLookup lookup = new StubLookup(viewer);
    private final ApiGatewayExecuteApiHostFilter filter = new ApiGatewayExecuteApiHostFilter(
            lookup, new RegionResolver(REGION, VIEWER_ACCOUNT), new ApiGatewayExecuteRouteContext(),
            "localhost", viewer);

    @Test
    void httpApiOfAnotherAccountIsLookedUpUnderTheOwnerAndTheViewerContextIsRestored() {
        lookup.owner = Optional.of(new ApiGatewayV2Service.ApiOwner(OWNER_ACCOUNT, REGION));
        lookup.httpApi = true;

        ApiGatewayExecuteApiHostFilter.RouteDecision decision = filter.decide(HOST, "/dev/accounts", null);

        assertEquals(ApiGatewayExecuteApiHostFilter.RouteKind.HTTP_API, decision.kind());
        assertEquals("dev", decision.stage());
        assertEquals("accounts", decision.remainingPath());
        assertEquals(List.of(OWNER_ACCOUNT, OWNER_ACCOUNT), lookup.accountsSeen);
        assertEquals(VIEWER_ACCOUNT, viewer.getAccountId());
        assertEquals(REGION, viewer.getRegion());
    }

    @Test
    void viewerContextIsRestoredWhenALookupFails() {
        lookup.owner = Optional.of(new ApiGatewayV2Service.ApiOwner(OWNER_ACCOUNT, REGION));

        ApiGatewayExecuteApiHostFilter.RouteDecision decision = filter.decide(HOST, "/dev/accounts", null);

        assertEquals(ApiGatewayExecuteApiHostFilter.RouteKind.NONE, decision.kind());
        assertEquals(VIEWER_ACCOUNT, viewer.getAccountId());
    }

    @Test
    void httpApiOfAnotherAccountRoutes() {
        lookup.owner = Optional.of(new ApiGatewayV2Service.ApiOwner(OWNER_ACCOUNT, REGION));
        lookup.httpApi = true;

        assertTrue(filter.routes(HOST, "/dev/accounts"));
    }

    @Test
    void httpApiWithAnotherRegionLabelDoesNotRoute() {
        lookup.owner = Optional.of(new ApiGatewayV2Service.ApiOwner(OWNER_ACCOUNT, REGION));
        lookup.httpApi = true;

        assertFalse(filter.routes(WRONG_REGION_HOST, "/dev/accounts"));
    }

    @Test
    void restApiInTheHostRegionRoutes() {
        lookup.restRegion = Optional.of(REGION);

        assertTrue(filter.routes(HOST, "/dev/"));
    }

    @Test
    void restApiWithAnotherRegionLabelDoesNotRoute() {
        lookup.restRegion = Optional.of(REGION);

        assertFalse(filter.routes(WRONG_REGION_HOST, "/dev/"));
    }

    @Test
    void restApiThatDoesNotExistDoesNotRoute() {
        assertFalse(filter.routes(HOST, "/dev/"));
    }

    @Test
    void ambiguousApiIdDoesNotRoute() {
        lookup.ambiguous = true;

        assertFalse(filter.routes(HOST, "/dev/accounts"));
    }

    @Test
    void internalExecuteApiPathDoesNotRoute() {
        lookup.restRegion = Optional.of(REGION);

        assertFalse(filter.routes(HOST, "/execute-api/" + API_ID + "/dev/"));
    }

    private static RequestContext viewerContext() {
        RequestContext context = new RequestContext();
        context.setAccountId(VIEWER_ACCOUNT);
        context.setRegion(REGION);
        return context;
    }

    private static final class StubLookup implements ApiGatewayExecuteApiHostFilter.ApiGatewayLookup {
        private final RequestContext viewer;
        private final List<String> accountsSeen = new ArrayList<>();
        private Optional<ApiGatewayV2Service.ApiOwner> owner = Optional.empty();
        private Optional<String> restRegion = Optional.empty();
        private boolean httpApi;
        private boolean ambiguous;

        private StubLookup(RequestContext viewer) {
            this.viewer = viewer;
        }

        @Override
        public Optional<ApiGatewayV2Service.ApiOwner> findApiOwner(String apiId) {
            if (ambiguous) {
                throw new AwsException("ConflictException", "ambiguous", 409);
            }
            return owner;
        }

        @Override
        public Optional<String> findRestApiRegion(String apiId) {
            return restRegion;
        }

        @Override
        public String resolveApiRegion(String preferredRegion, String apiId) {
            return preferredRegion;
        }

        @Override
        public String protocolType(String region, String apiId) {
            accountsSeen.add(viewer.getAccountId());
            if (!httpApi) {
                throw new AwsException("NotFoundException", "Invalid API id specified", 404);
            }
            return "HTTP";
        }

        @Override
        public boolean executeApiEndpointDisabled(String region, String apiId) {
            return false;
        }

        @Override
        public void requireStage(String region, String apiId, String stageName) {
            accountsSeen.add(viewer.getAccountId());
            if (!"dev".equals(stageName)) {
                throw new AwsException("NotFoundException", "Stage not found", 404);
            }
        }
    }
}
