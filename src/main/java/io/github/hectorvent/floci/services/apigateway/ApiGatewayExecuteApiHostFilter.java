package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Provider
@PreMatching
@Priority(9)
@ApplicationScoped
public class ApiGatewayExecuteApiHostFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(ApiGatewayExecuteApiHostFilter.class);
    private static final Pattern EXECUTE_API_PREFIX = Pattern.compile("^([a-z0-9-]+)\\.execute-api\\.(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWS_REGION = Pattern.compile("^[a-z]{2}-[a-z-]+-\\d+$",
            Pattern.CASE_INSENSITIVE);

    private final ApiGatewayLookup apiGatewayLookup;
    private final RegionResolver regionResolver;
    private final ApiGatewayExecuteRouteContext routeContext;
    private final String baseHostname;
    private final RequestContext requestContext;

    @Inject
    public ApiGatewayExecuteApiHostFilter(ApiGatewayV2Service apiGatewayV2Service,
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
        String host = requestContext.getHeaderString("Host");
        if (host == null) {
            return;
        }

        String apiId = extractApiId(host, baseHostname);
        if (apiId == null) {
            return;
        }
        Optional<ApiGatewayV2Service.ApiOwner> owner = apiGatewayLookup.findApiOwner(apiId);
        String region;
        if (owner.isPresent()) {
            ApiGatewayV2Service.ApiOwner apiOwner = owner.get();
            if (this.requestContext != null) {
                this.requestContext.setAccountId(apiOwner.accountId());
                this.requestContext.setRegion(apiOwner.region());
            }
            region = apiOwner.region();
        } else {
            // Region: the host label when it is a real AWS region (…execute-api.{region}.…), else
            // the SigV4 credential scope. The cross-region apiId scan (resolveApiRegion) is the
            // final fallback so an API created outside the default region still resolves —
            // including on regionless built-in suffixes (issue #1871).
            String hostRegion = regionResolver.resolveRegionFromHost(host);
            String preferredRegion = hostRegion != null
                    ? hostRegion
                    : regionResolver.resolveRegionFromAuth(requestContext.getHeaderString("Authorization"));
            region = apiGatewayLookup.resolveApiRegion(preferredRegion, apiId);
        }

        URI originalUri = requestContext.getUriInfo().getRequestUri();
        String originalPath = originalUri.getRawPath();

        // Host matching alone cannot tell whether this request was already rewritten: the Host
        // header still names the execute-api subdomain afterwards. Rewriting twice would produce
        // /execute-api/{apiId}/execute-api/{apiId}/... and a 404, so bail out if another
        // execute-api filter got here first.
        if (originalPath != null && originalPath.startsWith("/execute-api/")) {
            return;
        }

        try {
            String protocolType = apiGatewayLookup.protocolType(region, apiId);
            // HTTP APIs route all their invoke traffic through this filter. WEBSOCKET APIs route
            // ONLY their @connections management calls (issue #1846) — the $connect upgrade itself
            // is a Vert.x WebSocket handshake handled earlier by WebSocketHandler, never here.
            boolean routable = "HTTP".equals(protocolType)
                    || ("WEBSOCKET".equals(protocolType) && isConnectionsManagementPath(originalPath));
            if (!routable) {
                return;
            }
            if (apiGatewayLookup.executeApiEndpointDisabled(region, apiId)) {
                requestContext.abortWith(Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"message\":\"Not Found\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build());
                return;
            }
        } catch (AwsException e) {
            LOG.debugv(e, "Execute API host did not resolve to a routable API: apiId={0}, region={1}",
                    apiId, region);
            return;
        }

        String path = originalPath == null ? "" : stripLeadingSlash(originalPath);
        String firstSegment = firstSegment(path);

        String stageName = "$default";
        String remainingPath = path;
        if (!firstSegment.isEmpty() && stageExists(region, apiId, firstSegment)) {
            stageName = firstSegment;
            remainingPath = stripFirstSegment(path);
        } else if (!stageExists(region, apiId, stageName)) {
            return;
        }

        String newPath = "/execute-api/" + apiId + "/" + stageName + "/";
        if (!remainingPath.isEmpty()) {
            newPath += remainingPath;
        }

        URI newUri = UriBuilder.fromUri(originalUri)
                .replacePath(newPath)
                .buildFromEncoded();
        LOG.debugv("Execute API host routing: {0}{1} -> {2}", host, originalPath, newUri.getRawPath());
        routeContext.routeToHttpApi(region);
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

    interface ApiGatewayLookup {
        default Optional<ApiGatewayV2Service.ApiOwner> findApiOwner(String apiId) {
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
     * local {@code localhost} form, and AWS's {@code amazonaws.com} form. The public built-in
     * suffixes remain regionless convenience forms.
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
        if ("localhost.floci.io".equalsIgnoreCase(tail)
                || "localhost.localstack.cloud".equalsIgnoreCase(tail)) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }

        int firstDot = tail.indexOf('.');
        if (firstDot <= 0 || !AWS_REGION.matcher(tail.substring(0, firstDot)).matches()) {
            return null;
        }

        String endpointHost = tail.substring(firstDot + 1);
        if ("localhost".equalsIgnoreCase(endpointHost)
                || "amazonaws.com".equalsIgnoreCase(endpointHost)
                || (baseHostname != null && baseHostname.equalsIgnoreCase(endpointHost))) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
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
