package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.RequestHost;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Provider
@PreMatching
@Priority(9)
@ApplicationScoped
public class ApiGatewayExecuteApiHostFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(ApiGatewayExecuteApiHostFilter.class);
    public static final String NOT_FOUND_BODY = "{\"message\":\"Not Found\"}";
    private static final String DEFAULT_STAGE = "$default";
    private static final Pattern EXECUTE_API_PREFIX = Pattern.compile("^([a-z0-9-]+)\\.execute-api\\.(.+)$",
            Pattern.CASE_INSENSITIVE);

    private final ApiGatewayLookup apiGatewayLookup;
    private final RegionResolver regionResolver;
    private final ApiGatewayExecuteRouteContext routeContext;
    private final String baseHostname;
    private final RequestContext requestContext;

    @Inject
    public ApiGatewayExecuteApiHostFilter(ApiGatewayV2Service apiGatewayV2Service,
                                          ApiGatewayService apiGatewayService,
                                          RegionResolver regionResolver,
                                          ApiGatewayExecuteRouteContext routeContext,
                                          EmulatorConfig config,
                                          RequestContext requestContext) {
        this(new ApiGatewayLookup() {
            @Override
            public Optional<ApiGatewayV2Service.ApiOwner> findApiOwner(String apiId) {
                return apiGatewayV2Service.findApiOwner(apiId);
            }

            @Override
            public Optional<String> findRestApiRegion(String apiId) {
                return apiGatewayService.findRestApiOwner(apiId).map(ApiGatewayService.ApiOwner::region);
            }

            @Override
            public String resolveApiRegion(String preferredRegion, String apiId) {
                return apiGatewayV2Service.resolveApiRegion(preferredRegion, apiId);
            }

            @Override
            public String protocolType(String region, String apiId) {
                return apiGatewayV2Service.getApi(region, apiId).getProtocolType();
            }

            @Override
            public boolean executeApiEndpointDisabled(String region, String apiId) {
                return apiGatewayV2Service.getApi(region, apiId).isDisableExecuteApiEndpoint();
            }

            @Override
            public void requireStage(String region, String apiId, String stageName) {
                apiGatewayV2Service.getStage(region, apiId, stageName);
            }
        }, regionResolver, routeContext, config.hostname().orElse("localhost"), requestContext);
    }

    ApiGatewayExecuteApiHostFilter(ApiGatewayLookup apiGatewayLookup, RegionResolver regionResolver) {
        this(apiGatewayLookup, regionResolver, new ApiGatewayExecuteRouteContext(), "localhost", null);
    }

    ApiGatewayExecuteApiHostFilter(ApiGatewayLookup apiGatewayLookup, RegionResolver regionResolver,
                                   ApiGatewayExecuteRouteContext routeContext) {
        this(apiGatewayLookup, regionResolver, routeContext, "localhost", null);
    }

    ApiGatewayExecuteApiHostFilter(ApiGatewayLookup apiGatewayLookup, RegionResolver regionResolver,
                                   ApiGatewayExecuteRouteContext routeContext, String baseHostname) {
        this(apiGatewayLookup, regionResolver, routeContext, baseHostname, null);
    }

    ApiGatewayExecuteApiHostFilter(ApiGatewayLookup apiGatewayLookup, RegionResolver regionResolver,
                                   ApiGatewayExecuteRouteContext routeContext, String baseHostname,
                                   RequestContext requestContext) {
        this.apiGatewayLookup = apiGatewayLookup;
        this.regionResolver = regionResolver;
        this.routeContext = routeContext;
        this.baseHostname = baseHostname;
        this.requestContext = requestContext;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String host = RequestHost.of(requestContext);
        URI originalUri = requestContext.getUriInfo().getRequestUri();
        String originalPath = originalUri.getRawPath();

        RouteDecision decision = decide(host, originalPath, requestContext.getHeaderString("Authorization"));
        if (this.requestContext != null) {
            if (decision.owner() != null) {
                this.requestContext.setAccountId(decision.owner().accountId());
                this.requestContext.setRegion(decision.owner().region());
            }
        }

        switch (decision.kind()) {
            case NONE -> { }
            case DISABLED -> requestContext.abortWith(notFoundResponse());
            case REST_API -> routeToRestApi(requestContext, originalUri, originalPath, decision.apiId());
            case HTTP_API -> routeToHttpApi(requestContext, host, originalUri, originalPath, decision);
        }
    }

    /**
     * Whether a request for this execute-api host and raw path would be rewritten onto an API by
     * {@link #filter}. False when the host names no API, the region label is not the API's region,
     * the API is not invokable on this path (WebSocket, disabled endpoint, no stage), or the path is
     * already an internal {@code /execute-api/} path.
     */
    public boolean routes(String host, String originalPath) {
        try {
            RouteDecision decision = decide(host, originalPath, null);
            Optional<String> apiRegion = switch (decision.kind()) {
                case HTTP_API -> Optional.of(decision.region());
                case REST_API -> apiGatewayLookup.findRestApiRegion(decision.apiId());
                case NONE, DISABLED -> Optional.empty();
            };
            String hostRegion = regionResolver.resolveRegionFromHost(host);
            return apiRegion.isPresent() && (hostRegion == null || hostRegion.equals(apiRegion.get()));
        } catch (AwsException e) {
            LOG.debugv(e, "Execute API host did not resolve to one API: apiId={0}", extractApiId(host, baseHostname));
            return false;
        }
    }

    private static Response notFoundResponse() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(NOT_FOUND_BODY)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * Decides what {@link #filter} does for a host and raw path, without touching the request. The
     * lookups run under the account of the API's owner, and the caller's account and region are
     * restored afterwards.
     */
    RouteDecision decide(String host, String originalPath, String authorization) {
        String apiId = extractApiId(host, baseHostname);
        if (apiId == null) {
            return RouteDecision.none(null);
        }
        ApiGatewayV2Service.ApiOwner owner = apiGatewayLookup.findApiOwner(apiId).orElse(null);
        // Host matching alone cannot tell whether this request was already rewritten: the Host
        // header still names the execute-api subdomain afterwards. Rewriting twice would produce
        // /execute-api/{apiId}/execute-api/{apiId}/... and a 404, so bail out if another
        // execute-api filter got here first.
        if (originalPath != null && originalPath.startsWith("/execute-api/")) {
            return RouteDecision.none(owner);
        }
        if (owner == null) {
            // Region: the host label when it is a real AWS region (…execute-api.{region}.…), else
            // the SigV4 credential scope. The cross-region apiId scan (resolveApiRegion) is the
            // final fallback so an API created outside the default region still resolves —
            // including on regionless built-in suffixes (issue #1871).
            String hostRegion = regionResolver.resolveRegionFromHost(host);
            String preferredRegion = hostRegion != null
                    ? hostRegion
                    : regionResolver.resolveRegionFromAuth(authorization);
            return decideInRegion(apiId, apiGatewayLookup.resolveApiRegion(preferredRegion, apiId),
                    originalPath, owner);
        }
        return underAccountOf(owner, () -> decideInRegion(apiId, owner.region(), originalPath, owner));
    }

    private RouteDecision decideInRegion(String apiId, String region, String originalPath,
                                         ApiGatewayV2Service.ApiOwner owner) {
        try {
            String protocolType = apiGatewayLookup.protocolType(region, apiId);
            // HTTP APIs route all their invoke traffic through this filter. WEBSOCKET APIs route
            // ONLY their @connections management calls (issue #1846) — the $connect upgrade itself
            // is a Vert.x WebSocket handshake handled earlier by WebSocketHandler, never here.
            boolean routable = "HTTP".equals(protocolType)
                    || ("WEBSOCKET".equals(protocolType) && isConnectionsManagementPath(originalPath));
            if (!routable) {
                return RouteDecision.none(owner);
            }
            if (apiGatewayLookup.executeApiEndpointDisabled(region, apiId)) {
                return RouteDecision.disabled(owner);
            }
        } catch (AwsException e) {
            // The v2 owner lookup is account-independent. When it found no owner, let the
            // REST execute controller resolve the API after account context is established.
            if (owner == null) {
                return RouteDecision.restApi(apiId);
            }
            LOG.debugv(e, "Execute API host did not resolve to a routable API: apiId={0}, region={1}",
                    apiId, region);
            return RouteDecision.none(owner);
        }

        String path = originalPath == null ? "" : stripLeadingSlash(originalPath);
        String firstSegment = firstSegment(path);
        if (!firstSegment.isEmpty() && stageExists(region, apiId, firstSegment)) {
            return RouteDecision.httpApi(apiId, region, owner, firstSegment, stripFirstSegment(path));
        }
        if (!stageExists(region, apiId, DEFAULT_STAGE)) {
            return RouteDecision.none(owner);
        }
        return RouteDecision.httpApi(apiId, region, owner, DEFAULT_STAGE, path);
    }

    private <T> T underAccountOf(ApiGatewayV2Service.ApiOwner owner, Supplier<T> action) {
        if (requestContext == null) {
            return action.get();
        }
        String callerAccount = requestContext.getAccountId();
        String callerRegion = requestContext.getRegion();
        requestContext.setAccountId(owner.accountId());
        requestContext.setRegion(owner.region());
        try {
            return action.get();
        } finally {
            requestContext.setAccountId(callerAccount);
            requestContext.setRegion(callerRegion);
        }
    }

    private void routeToHttpApi(ContainerRequestContext requestContext, String host, URI originalUri,
                                String originalPath, RouteDecision decision) {
        String newPath = "/execute-api/" + decision.apiId() + "/" + decision.stage() + "/";
        if (!decision.remainingPath().isEmpty()) {
            newPath += decision.remainingPath();
        }

        URI newUri = UriBuilder.fromUri(originalUri)
                .replacePath(newPath)
                .buildFromEncoded();
        LOG.debugv("Execute API host routing: {0}{1} -> {2}", host, originalPath, newUri.getRawPath());
        routeContext.routeToHttpApi(decision.region());
        // AWS_IAM dispatch rebuilds the caller's canonical request, which covers the path they
        // signed: the virtual-host path, not the /execute-api/... form this rewrite produces.
        routeContext.recordSignedRequestPath(originalPath);
        requestContext.setRequestUri(newUri);
    }

    private void routeToRestApi(ContainerRequestContext requestContext, URI originalUri,
                                String originalPath, String apiId) {
        String path = originalPath == null || originalPath.isEmpty() ? "/" : originalPath;
        URI newUri = UriBuilder.fromUri(originalUri)
                .replacePath("/execute-api/" + apiId + path)
                .buildFromEncoded();
        LOG.debugv("REST execute-api host routing: {0} -> {1}", originalPath, newUri.getRawPath());
        routeContext.routeToRestApi();
        routeContext.recordSignedRequestPath(originalPath);
        requestContext.setRequestUri(newUri);
    }

    private boolean stageExists(String region, String apiId, String stageName) {
        try {
            apiGatewayLookup.requireStage(region, apiId, stageName);
            return true;
        } catch (AwsException e) {
            LOG.debugv(e, "Execute API stage lookup did not resolve: apiId={0}, region={1}, stage={2}",
                    apiId, region, stageName);
            return false;
        }
    }

    enum RouteKind { NONE, DISABLED, REST_API, HTTP_API }

    /**
     * The outcome of the routing decision. {@code owner} is the HTTP or WebSocket API owner found for
     * the host, whatever the outcome, so that {@link #filter} can switch to its account, or null.
     */
    record RouteDecision(RouteKind kind, String apiId, ApiGatewayV2Service.ApiOwner owner,
                         String region, String stage, String remainingPath) {

        static RouteDecision none(ApiGatewayV2Service.ApiOwner owner) {
            return new RouteDecision(RouteKind.NONE, null, owner, null, null, null);
        }

        static RouteDecision disabled(ApiGatewayV2Service.ApiOwner owner) {
            return new RouteDecision(RouteKind.DISABLED, null, owner, null, null, null);
        }

        static RouteDecision restApi(String apiId) {
            return new RouteDecision(RouteKind.REST_API, apiId, null, null, null, null);
        }

        static RouteDecision httpApi(String apiId, String region, ApiGatewayV2Service.ApiOwner owner,
                                     String stage, String remainingPath) {
            return new RouteDecision(RouteKind.HTTP_API, apiId, owner, region, stage, remainingPath);
        }
    }

    interface ApiGatewayLookup {
        default Optional<ApiGatewayV2Service.ApiOwner> findApiOwner(String apiId) {
            return Optional.empty();
        }

        default Optional<String> findRestApiRegion(String apiId) {
            return Optional.empty();
        }

        String resolveApiRegion(String preferredRegion, String apiId);

        String protocolType(String region, String apiId);

        boolean executeApiEndpointDisabled(String region, String apiId);

        void requireStage(String region, String apiId, String stageName);
    }

    /**
     * Extracts the {@code apiId} from an execute-api virtual host, or {@code null} when the host
     * is not an execute-api host. Region-bearing hosts accept the configured Floci hostname, the
     * local {@code localhost} form, the public built-in suffixes, and AWS's
     * {@code amazonaws.com} form. The built-in suffixes are also accepted regionless, as a
     * convenience form.
     */
    public static String extractApiId(String host, String baseHostname) {
        if (host == null) {
            return null;
        }

        Matcher matcher = EXECUTE_API_PREFIX.matcher(stripPort(host));
        if (!matcher.matches()) {
            return null;
        }

        String tail = matcher.group(2);
        if (isBuiltInSuffix(tail)) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }

        int firstDot = tail.indexOf('.');
        if (firstDot <= 0 || !RegionResolver.isKnownRegion(tail.substring(0, firstDot))) {
            return null;
        }

        String endpointHost = tail.substring(firstDot + 1);
        if ("localhost".equalsIgnoreCase(endpointHost)
                || isBuiltInSuffix(endpointHost)
                || AwsPartitions.isDnsSuffix(endpointHost)
                || (baseHostname != null && baseHostname.equalsIgnoreCase(endpointHost))) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean isBuiltInSuffix(String host) {
        for (String suffix : EmbeddedDnsServer.BUILTIN_SUFFIXES) {
            if (suffix.equalsIgnoreCase(host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the path is a WebSocket {@code @connections} management route,
     * {@code /{stage}/@connections[/{connectionId}]} (raw or percent-encoded {@code @}).
     */
    private static boolean isConnectionsManagementPath(String path) {
        return path != null && (path.contains("/@connections") || path.contains("/%40connections"));
    }

    private static String stripPort(String host) {
        int colonIndex = host.lastIndexOf(':');
        if (colonIndex > 0) {
            String port = host.substring(colonIndex + 1);
            if (!port.isEmpty() && port.chars().allMatch(Character::isDigit)) {
                return host.substring(0, colonIndex);
            }
        }
        return host;
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String firstSegment(String path) {
        int slash = path.indexOf('/');
        return slash >= 0 ? path.substring(0, slash) : path;
    }

    private static String stripFirstSegment(String path) {
        int slash = path.indexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : "";
    }
}
