package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import jakarta.annotation.Priority;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Provider
@PreMatching
@Priority(9)
public class ApiGatewayExecuteApiHostFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(ApiGatewayExecuteApiHostFilter.class);
    private static final Pattern EXECUTE_API_HOST = Pattern.compile(
            "^([a-z0-9-]+)\\.execute-api\\.localhost\\.(?:floci\\.io|localstack\\.cloud)$",
            Pattern.CASE_INSENSITIVE);

    private final ApiGatewayLookup apiGatewayLookup;
    private final RegionResolver regionResolver;
    private final ApiGatewayExecuteRouteContext routeContext;

    @Inject
    public ApiGatewayExecuteApiHostFilter(ApiGatewayV2Service apiGatewayV2Service,
                                          RegionResolver regionResolver,
                                          ApiGatewayExecuteRouteContext routeContext) {
        this(new ApiGatewayLookup() {
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
        }, regionResolver, routeContext);
    }

    ApiGatewayExecuteApiHostFilter(ApiGatewayLookup apiGatewayLookup, RegionResolver regionResolver) {
        this(apiGatewayLookup, regionResolver, new ApiGatewayExecuteRouteContext());
    }

    ApiGatewayExecuteApiHostFilter(ApiGatewayLookup apiGatewayLookup, RegionResolver regionResolver,
                                   ApiGatewayExecuteRouteContext routeContext) {
        this.apiGatewayLookup = apiGatewayLookup;
        this.regionResolver = regionResolver;
        this.routeContext = routeContext;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String host = requestContext.getHeaderString("Host");
        if (host == null) {
            return;
        }

        Matcher matcher = EXECUTE_API_HOST.matcher(stripPort(host));
        if (!matcher.matches()) {
            return;
        }

        String apiId = matcher.group(1).toLowerCase(Locale.ROOT);
        String preferredRegion = regionResolver.resolveRegionFromAuth(
                requestContext.getHeaderString("Authorization"));
        String region = apiGatewayLookup.resolveApiRegion(preferredRegion, apiId);
        try {
            if (!"HTTP".equals(apiGatewayLookup.protocolType(region, apiId))) {
                return;
            }
            if (apiGatewayLookup.executeApiEndpointDisabled(region, apiId)) {
                requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"message\":\"Forbidden\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build());
                return;
            }
        } catch (AwsException e) {
            LOG.debugv(e, "Execute API host did not resolve to an HTTP API: apiId={0}, region={1}",
                    apiId, region);
            return;
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
        String resolveApiRegion(String preferredRegion, String apiId);

        String protocolType(String region, String apiId);

        boolean executeApiEndpointDisabled(String region, String apiId);

        void requireStage(String region, String apiId, String stageName);
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
