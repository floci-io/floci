package io.floci.aws.core.common;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.apigateway.ApiGatewayController;
import io.floci.aws.services.appconfig.AppConfigController;
import io.floci.aws.services.aps.ApsController;
import io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreResourcePolicyController;
import io.floci.aws.services.codegurureviewer.CodeGuruReviewerController;
import io.floci.aws.services.dsql.DsqlController;
import io.floci.aws.services.iot.IotController;
import io.floci.aws.services.msk.MskController;
import io.floci.aws.services.rdsdata.RdsDataController;
import io.floci.aws.services.s3.S3Controller;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Stops a known REST JSON service request from being handled by S3's path-style wildcard routes
 * or by another service controller sharing the path template (Issue #6426).
 * This runs after route matching so {@link ResourceInfo} identifies a genuine endpoint match,
 * complementing the pre-matching unknown-scope guard in {@link AwsProtocolClaimFilter}.
 */
@Provider
@Priority(Priorities.ENTITY_CODER + 100)
public class AwsRestRouteScopeFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AwsRestRouteScopeFilter.class);

    @Context
    ResourceInfo resourceInfo;

    record RouteCollision(Class<?> controllerClass, Predicate<String> paths, Set<String> conflictingScopes) {
        RouteCollision(Class<?> controllerClass, String pathPrefix, Set<String> conflictingScopes) {
            this(controllerClass, SignedServiceRoute.under(pathPrefix), conflictingScopes);
        }
    }

    // Issue #6426: Map of controllers, path prefixes, and foreign signing service scopes that collide
    // on shared JAX-RS path templates when operations are unimplemented for the signed service.
    private static final List<RouteCollision> ROUTE_COLLISIONS = List.of(
            new RouteCollision(ApiGatewayController.class, "/v2/apis", Set.of("appsync")),
            new RouteCollision(AppConfigController.class, "/applications",
                    Set.of("app-integrations", "servicecatalog")),
            new RouteCollision(MskController.class, "/v1/configurations", Set.of("mq")),
            new RouteCollision(CodeGuruReviewerController.class, "/associations", Set.of("securityhub")),
            new RouteCollision(IotController.class, "/jobs", Set.of("macie2")),
            new RouteCollision(IotController.class, "/rules", Set.of("connect")),
            new RouteCollision(ApsController.class, "/workspaces", Set.of("connect")),
            new RouteCollision(DsqlController.class, "/cluster/get", Set.of("inspector2")),
            new RouteCollision(RdsDataController.class, "/CommitTransaction", Set.of("lakeformation")),
            new RouteCollision(BedrockAgentCoreResourcePolicyController.class, "/resourcepolicy",
                    Set.of("bedrock"))
    );

    private final ResolvedServiceCatalog catalog;
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    public AwsRestRouteScopeFilter(ResolvedServiceCatalog catalog,
                                   jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.catalog = catalog;
        this.configProvider = configProvider;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        Object claimValue = ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY);
        if (!(claimValue instanceof ProtocolClaim claim) || claim.protocol() != WireProtocol.REST
                || resourceInfo == null) {
            return;
        }

        Class<?> resourceClass = RoutedResource.resourceClass(resourceInfo);
        if (resourceClass == null) {
            return;
        }

        if (resourceClass == S3Controller.class) {
            SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"))
                    .flatMap(catalog::byCredentialScope)
                    .filter(descriptor -> descriptor.supportsProtocol(ServiceProtocol.REST_JSON))
                    .filter(descriptor -> !"s3".equals(descriptor.externalKey()))
                    .ifPresent(descriptor -> reject(ctx, descriptor.externalKey(), "an S3 wildcard route"));
            return;
        }

        // Issue #6426: Reject cross-service REST calls matching another service's controller route
        String rawPath = ctx.getUriInfo().getPath();
        String normalizedPath = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        Optional<String> scope = SignedServiceRoute.signingService(ctx);
        if (scope.isEmpty()) {
            return;
        }

        for (RouteCollision collision : ROUTE_COLLISIONS) {
            if (collision.controllerClass() == resourceClass
                    && collision.paths().test(normalizedPath)
                    && collision.conflictingScopes().contains(scope.get())) {
                reject(ctx, scope.get(), "a colliding route on another service");
                return;
            }
        }
    }

    private void reject(ContainerRequestContext ctx, String service, String matchedRoute) {
        if (!configProvider.get().protocols().rejectUnknownServiceScope()) {
            LOG.debugv("Known REST JSON service {0} matched {1}; rejection disabled by config",
                    service, matchedRoute);
            return;
        }
        String method = ctx.getMethod();
        String path = ctx.getUriInfo().getPath();
        LOG.infov("Rejecting {0} request that matched {1}: {2} {3}",
                service, matchedRoute, method, path);
        ctx.abortWith(AwsProtocolClaimFilter.unknownOperationResponse(404,
                "Unknown operation: " + method + " " + path));
    }
}
