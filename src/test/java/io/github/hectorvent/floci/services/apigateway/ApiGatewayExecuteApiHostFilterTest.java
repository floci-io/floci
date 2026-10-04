package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiGatewayExecuteApiHostFilterTest {

    private static final String API_ID = "abc123";
    private static final String REGION = "us-east-1";

    @Test
    void routesStageLessRequestToDefaultStageAndPreservesQuery() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(API_ID);
        lookup.addStage(API_ID, "$default");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.localhost.floci.io:4566",
                URI.create("http://abc123.execute-api.localhost.floci.io:4566/accounts?tenant=alpha"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayExecuteApiHostFilter(
                lookup, new RegionResolver(REGION, "000000000000"), routeContext)
                .filter(request.context());

        assertEquals("/execute-api/abc123/$default/accounts", request.routedUri().getRawPath());
        assertEquals("tenant=alpha", request.routedUri().getQuery());
        assertEquals(REGION, routeContext.httpApiRegion());
    }

    @Test
    void routesExplicitStageAndRemovesItFromRemainingPath() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(API_ID);
        lookup.addStage(API_ID, "dev");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.localhost.localstack.cloud",
                URI.create("http://abc123.execute-api.localhost.localstack.cloud/dev/accounts"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertEquals("/execute-api/abc123/dev/accounts", request.routedUri().getRawPath());
    }

    @Test
    void preservesEncodedPathAndQueryComponents() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(API_ID);
        lookup.addStage(API_ID, "$default");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.localhost.floci.io",
                URI.create("http://abc123.execute-api.localhost.floci.io/files/a%2Fb?value=a%2Fb"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertEquals("/execute-api/abc123/$default/files/a%2Fb", request.routedUri().getRawPath());
        assertEquals("value=a%2Fb", request.routedUri().getRawQuery());
    }

    @Test
    void routesStageRootWithTrailingSlash() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(API_ID);
        lookup.addStage(API_ID, "dev");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.localhost.floci.io",
                URI.create("http://abc123.execute-api.localhost.floci.io/dev"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertEquals("/execute-api/abc123/dev/", request.routedUri().getRawPath());
    }

    @Test
    void routesRestHostWhenNoV2ApiOwnsTheIdentifier() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.localhost.floci.io",
                URI.create("http://abc123.execute-api.localhost.floci.io/dev/deep/path?value=a%2Fb"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayExecuteApiHostFilter(
                lookup, new RegionResolver(REGION, "000000000000"), routeContext)
                .filter(request.context());

        assertEquals("/execute-api/abc123/dev/deep/path", request.routedUri().getRawPath());
        assertEquals("value=a%2Fb", request.routedUri().getRawQuery());
        assertNull(routeContext.httpApiRegion());
        assertEquals("/dev/deep/path", routeContext.signedRequestPath());
    }

    @Test
    void resolvesUnsignedApiOutsideDefaultRegion() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi("us-west-2", API_ID, "HTTP");
        lookup.addStage("us-west-2", API_ID, "$default");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.localhost.floci.io",
                URI.create("http://abc123.execute-api.localhost.floci.io/accounts"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertEquals("/execute-api/abc123/$default/accounts", request.routedUri().getRawPath());
    }

    @Test
    void normalizesMixedCaseHostApiId() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(API_ID);
        lookup.addStage(API_ID, "$default");
        RecordingRequest request = new RecordingRequest(
                "AbC123.execute-api.localhost.floci.io",
                URI.create("http://abc123.execute-api.localhost.floci.io/accounts"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertEquals("/execute-api/abc123/$default/accounts", request.routedUri().getRawPath());
    }

    @Test
    void rejectsDisabledExecuteApiEndpoint() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(API_ID);
        lookup.addStage(API_ID, "$default");
        lookup.disableExecuteApiEndpoint(API_ID);
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.localhost.floci.io",
                URI.create("http://abc123.execute-api.localhost.floci.io/accounts"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertNull(request.routedUri());
        assertEquals(404, request.abortedResponse().getStatus());
        assertEquals("{\"message\":\"Not Found\"}", request.abortedResponse().getEntity());
    }

    @Test
    void ignoresWebSocketApi() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(REGION, API_ID, "WEBSOCKET");
        lookup.addStage(API_ID, "$default");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.localhost.floci.io",
                URI.create("http://abc123.execute-api.localhost.floci.io/accounts"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertNull(request.routedUri());
    }

    @Test
    void routesRegionBearingAwsHost() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(API_ID);
        lookup.addStage(API_ID, "prod");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.us-east-1.amazonaws.com",
                URI.create("http://abc123.execute-api.us-east-1.amazonaws.com/prod/accounts"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertEquals("/execute-api/abc123/prod/accounts", request.routedUri().getRawPath());
    }

    @Test
    void routesRegionBearingLocalHostInNonDefaultRegion() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi("ap-northeast-2", API_ID, "HTTP");
        lookup.addStage("ap-northeast-2", API_ID, "$default");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.ap-northeast-2.localhost:4566",
                URI.create("http://abc123.execute-api.ap-northeast-2.localhost:4566/accounts"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayExecuteApiHostFilter(
                lookup, new RegionResolver(REGION, "000000000000"), routeContext)
                .filter(request.context());

        assertEquals("/execute-api/abc123/$default/accounts", request.routedUri().getRawPath());
        assertEquals("ap-northeast-2", routeContext.httpApiRegion());
    }

    /**
     * The regionless built-in suffix is a convenience form; the region-bearing one is what a
     * client derives from a stage's invoke URL. Unclaimed, the request fell through to S3's path
     * routes and answered NoSuchBucket for the stage segment.
     */
    @Test
    void routesRegionBearingBuiltInSuffixHost() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi("ap-northeast-2", API_ID, "HTTP");
        lookup.addStage("ap-northeast-2", API_ID, "$default");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.ap-northeast-2.localhost.floci.io:4566",
                URI.create("http://abc123.execute-api.ap-northeast-2.localhost.floci.io:4566/accounts"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayExecuteApiHostFilter(
                lookup, new RegionResolver(REGION, "000000000000"), routeContext)
                .filter(request.context());

        assertEquals("/execute-api/abc123/$default/accounts", request.routedUri().getRawPath());
        assertEquals("ap-northeast-2", routeContext.httpApiRegion());
    }

    @Test
    void routesRestApiOnRegionBearingBuiltInSuffixHost() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.us-east-1.localhost.floci.io:4566",
                URI.create("http://abc123.execute-api.us-east-1.localhost.floci.io:4566/prod/users"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayExecuteApiHostFilter(
                lookup, new RegionResolver(REGION, "000000000000"), routeContext)
                .filter(request.context());

        assertEquals("/execute-api/abc123/prod/users", request.routedUri().getRawPath());
        assertNull(routeContext.httpApiRegion());
    }

    @Test
    void extractsApiIdFromRegionBearingBuiltInSuffixHosts() {
        assertEquals("abc123", ApiGatewayExecuteApiHostFilter.extractApiId(
                "abc123.execute-api.us-east-1.localhost.floci.io:4566", null));
        assertEquals("abc123", ApiGatewayExecuteApiHostFilter.extractApiId(
                "abc123.execute-api.us-east-1.localhost.localstack.cloud", null));
        assertNull(ApiGatewayExecuteApiHostFilter.extractApiId(
                "abc123.execute-api.us-east-1.example.test", null));
    }

    /**
     * A region label is any region a partition recognizes, not a two-letter geo prefix: the EUSC
     * partition's {@code eusc-de-east-1} was rejected before the suffix was even looked at, on
     * every tail.
     */
    @Test
    void extractsApiIdFromEuscRegionHosts() {
        assertEquals("abc123", ApiGatewayExecuteApiHostFilter.extractApiId(
                "abc123.execute-api.eusc-de-east-1.localhost.floci.io:4566", null));
        assertEquals("abc123", ApiGatewayExecuteApiHostFilter.extractApiId(
                "abc123.execute-api.eusc-de-east-1.localhost:4566", null));
        assertEquals("abc123", ApiGatewayExecuteApiHostFilter.extractApiId(
                "abc123.execute-api.eusc-de-east-1.amazonaws.eu", null));
    }

    /**
     * A region launched after the vendored partition data was generated matches its partition's
     * region regex without being in the published list; signed requests already accept it.
     */
    @Test
    void extractsApiIdFromRegionNotYetInTheCatalog() {
        assertEquals("abc123", ApiGatewayExecuteApiHostFilter.extractApiId(
                "abc123.execute-api.eu-south-9.localhost.floci.io", null));
    }

    @Test
    void ignoresRegionShapedLabelThatIsNotAKnownRegion() {
        assertNull(ApiGatewayExecuteApiHostFilter.extractApiId(
                "abc123.execute-api.zz-nowhere-9.localhost.floci.io", null));
    }

    @Test
    void routesHttpApiOnEuscRegionBuiltInSuffixHost() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi("eusc-de-east-1", API_ID, "HTTP");
        lookup.addStage("eusc-de-east-1", API_ID, "$default");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.eusc-de-east-1.localhost.floci.io:4566",
                URI.create("http://abc123.execute-api.eusc-de-east-1.localhost.floci.io:4566/accounts"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayExecuteApiHostFilter(
                lookup, new RegionResolver(REGION, "000000000000"), routeContext)
                .filter(request.context());

        assertEquals("/execute-api/abc123/$default/accounts", request.routedUri().getRawPath());
        assertEquals("eusc-de-east-1", routeContext.httpApiRegion());
    }

    @Test
    void routesRegionBearingConfiguredHost() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi("ap-northeast-2", API_ID, "HTTP");
        lookup.addStage("ap-northeast-2", API_ID, "$default");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.ap-northeast-2.floci:4566",
                URI.create("http://abc123.execute-api.ap-northeast-2.floci:4566/accounts"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayExecuteApiHostFilter(
                lookup, new RegionResolver(REGION, "000000000000"), routeContext, "floci")
                .filter(request.context());

        assertEquals("/execute-api/abc123/$default/accounts", request.routedUri().getRawPath());
        assertEquals("ap-northeast-2", routeContext.httpApiRegion());
    }

    @Test
    void routesWebSocketConnectionsManagementCall() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(REGION, API_ID, "WEBSOCKET");
        lookup.addStage(API_ID, "prod");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.us-east-1.amazonaws.com",
                URI.create("http://abc123.execute-api.us-east-1.amazonaws.com/prod/@connections/xyz"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertEquals("/execute-api/abc123/prod/@connections/xyz", request.routedUri().getRawPath());
    }

    @Test
    void routesWebSocketConnectionsViaBuiltinSuffixHostAcrossRegions() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi("ap-northeast-2", API_ID, "WEBSOCKET");
        lookup.addStage("ap-northeast-2", API_ID, "prod");
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.localhost.floci.io:4566",
                URI.create("http://abc123.execute-api.localhost.floci.io:4566/prod/@connections/xyz"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertEquals("/execute-api/abc123/prod/@connections/xyz", request.routedUri().getRawPath());
    }

    @Test
    void rejectsDisabledExecuteApiEndpointForWebSocketConnections() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(REGION, API_ID, "WEBSOCKET");
        lookup.addStage(API_ID, "prod");
        lookup.disableExecuteApiEndpoint(API_ID);
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.us-east-1.amazonaws.com",
                URI.create("http://abc123.execute-api.us-east-1.amazonaws.com/prod/@connections/xyz"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertNull(request.routedUri());
        assertEquals(404, request.abortedResponse().getStatus());
    }

    @Test
    void doesNotMarkHttpRouteWhenNoStageMatches() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        lookup.addApi(API_ID);
        RecordingRequest request = new RecordingRequest(
                "abc123.execute-api.localhost.floci.io",
                URI.create("http://abc123.execute-api.localhost.floci.io/accounts"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayExecuteApiHostFilter(
                lookup, new RegionResolver(REGION, "000000000000"), routeContext)
                .filter(request.context());

        assertNull(request.routedUri());
        assertNull(routeContext.httpApiRegion());
    }

    @Test
    void ignoresNonExecuteApiHost() {
        FakeApiGatewayLookup lookup = new FakeApiGatewayLookup();
        RecordingRequest request = new RecordingRequest(
                "localhost:4566",
                URI.create("http://localhost:4566/accounts"));

        new ApiGatewayExecuteApiHostFilter(lookup, new RegionResolver(REGION, "000000000000"))
                .filter(request.context());

        assertNull(request.routedUri());
        assertEquals(0, lookup.apiLookups);
    }

    private static final class FakeApiGatewayLookup implements ApiGatewayExecuteApiHostFilter.ApiGatewayLookup {
        private final Map<String, String> protocols = new HashMap<>();
        private final Map<String, Boolean> stages = new HashMap<>();
        private final Map<String, Boolean> disabledExecuteApiEndpoints = new HashMap<>();
        private int apiLookups;

        void addApi(String apiId) {
            addApi(REGION, apiId, "HTTP");
        }

        void addApi(String region, String apiId, String protocolType) {
            protocols.put(region + "/" + apiId, protocolType);
        }

        void addStage(String apiId, String stageName) {
            addStage(REGION, apiId, stageName);
        }

        void addStage(String region, String apiId, String stageName) {
            stages.put(region + "/" + apiId + "/" + stageName, true);
        }

        void disableExecuteApiEndpoint(String apiId) {
            disabledExecuteApiEndpoints.put(REGION + "/" + apiId, true);
        }

        @Override
        public String resolveApiRegion(String preferredRegion, String apiId) {
            if (protocols.containsKey(preferredRegion + "/" + apiId)) {
                return preferredRegion;
            }
            String suffix = "/" + apiId;
            return protocols.keySet().stream()
                    .filter(key -> key.endsWith(suffix))
                    .map(key -> key.substring(0, key.indexOf('/')))
                    .findFirst()
                    .orElse(preferredRegion);
        }

        @Override
        public String protocolType(String region, String apiId) {
            apiLookups++;
            String protocolType = protocols.get(region + "/" + apiId);
            if (protocolType == null) {
                throw new AwsException("NotFoundException", "Invalid API id specified", 404);
            }
            return protocolType;
        }

        @Override
        public boolean executeApiEndpointDisabled(String region, String apiId) {
            return disabledExecuteApiEndpoints.getOrDefault(region + "/" + apiId, false);
        }

        @Override
        public void requireStage(String region, String apiId, String stageName) {
            if (!stages.containsKey(region + "/" + apiId + "/" + stageName)) {
                throw new AwsException("NotFoundException", "Stage not found", 404);
            }
        }
    }

    private static final class RecordingRequest {
        private final String host;
        private final URI requestUri;
        private URI routedUri;
        private Response abortedResponse;

        private RecordingRequest(String host, URI requestUri) {
            this.host = host;
            this.requestUri = requestUri;
        }

        private ContainerRequestContext context() {
            return (ContainerRequestContext) Proxy.newProxyInstance(
                    ContainerRequestContext.class.getClassLoader(),
                    new Class<?>[] { ContainerRequestContext.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getHeaderString" -> header((String) args[0]);
                        case "getUriInfo" -> uriInfo();
                        case "setRequestUri" -> {
                            routedUri = (URI) args[0];
                            yield null;
                        }
                        case "abortWith" -> {
                            abortedResponse = (Response) args[0];
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private URI routedUri() {
            return routedUri;
        }

        private Response abortedResponse() {
            return abortedResponse;
        }

        private String header(String name) {
            return "Host".equals(name) ? host : null;
        }

        private UriInfo uriInfo() {
            return (UriInfo) Proxy.newProxyInstance(
                    UriInfo.class.getClassLoader(),
                    new Class<?>[] { UriInfo.class },
                    (proxy, method, args) -> {
                        if ("getRequestUri".equals(method.getName())) {
                            return requestUri;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
