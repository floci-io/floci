package io.floci.aws.core.common;

import io.floci.aws.services.iam.IamActionRegistry;
import io.floci.aws.services.iam.IamAuthorizedActions;
import io.floci.aws.services.iam.ResourceArnBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;

/**
 * Works out which service a request reaches, the operation it calls there and the IAM action that operation is
 * authorized as, from the service that will actually handle it rather than the scope it was signed with.
 *
 * <p>Query traffic shares the dispatch resolver with {@link AwsQueryController}; a Smithy RPC v2 call takes the
 * service and operation its route dispatches; a JSON call the service its protocol claim names and the operation in
 * its {@code X-Amz-Target}; and REST traffic the JAX-RS resource class that won route matching, so a REST request is
 * never moved by an {@code X-Amz-Target} it carries.
 *
 * <p>The route is only known once JAX-RS has matched one, so it is called from a post-matching filter with that
 * request's {@link ResourceInfo}. Stateless, and safe to share between requests.
 */
@ApplicationScoped
public class RequestOperationResolver {

    private final IamActionRegistry actionRegistry;
    private final AwsQueryServiceResolver queryServiceResolver;
    private final ResourceArnBuilder arnBuilder;
    private final ResolvedServiceCatalog catalog;

    @Inject
    public RequestOperationResolver(IamActionRegistry actionRegistry,
                                    AwsQueryServiceResolver queryServiceResolver,
                                    ResourceArnBuilder arnBuilder,
                                    ResolvedServiceCatalog catalog) {
        this.actionRegistry = actionRegistry;
        this.queryServiceResolver = queryServiceResolver;
        this.arnBuilder = arnBuilder;
        this.catalog = catalog;
    }

    /**
     * Resolves both the IAM namespace and action from the service that will actually handle the request.
     *
     * @param auth         the request's {@code Authorization} header, or the {@code Credential=} string built from a
     *                     presigned URL, which the Query dispatch reads the signing service from
     * @param claimedScope the canonical credential scope the request is signed with, kept when no service is found
     * @param ctx          the request, after route matching
     * @param resourceInfo the matched route, or {@code null} when none matched
     */
    public ResolvedOperation resolve(String auth, String claimedScope, ContainerRequestContext ctx,
                                     ResourceInfo resourceInfo) {
        Object claimValue = ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY);
        // Form POSTs are also recognized without a claim; GET dispatch requires the Query claim.
        if (IamActionRegistry.isQueryRequest(ctx)) {
            String queryAction = actionRegistry.queryAction(ctx);
            if (queryAction == null || queryAction.isBlank()) {
                return new ResolvedOperation(claimedScope, null);
            }
            String service = queryServiceResolver.resolve(auth, queryAction, actionRegistry.queryVersion(ctx));
            String servingScope = catalog.byExternalKey(service)
                    .map(descriptor -> iamServiceScope(descriptor, claimedScope))
                    .orElseGet(() -> catalog.canonicalCredentialScope(service));
            return new ResolvedOperation(servingScope,
                    IamAuthorizedActions.actionFor(servingScope, queryAction), servingScope + ":" + queryAction);
        }

        // Read from the route rather than the claim: a path the claim filter takes for RPC v2 can
        // reach another route, and an X-Amz-Target header names an operation this endpoint ignores.
        ProtocolClaim rpcV2Route = rpcV2RouteClaim(ctx, resourceInfo);
        if (rpcV2Route != null) {
            if (rpcV2Route.service() == null) {
                return new ResolvedOperation(claimedScope, null); // no service: the endpoint answers 404
            }
            String servingScope = iamServiceScope(rpcV2Route.service(), claimedScope);
            return new ResolvedOperation(servingScope,
                    actionRegistry.resolveOperation(servingScope, rpcV2Route.operation(), ctx),
                    servingScope + ":" + rpcV2Route.operation());
        }

        ResolvedOperation routeOperation = restRoute(ctx, resourceInfo);
        if (routeOperation != null) {
            return routeOperation;
        }

        // A REST request is authorized by its route alone, never by an X-Amz-Target it carries.
        boolean rest = claimValue instanceof ProtocolClaim claim && claim.protocol() == WireProtocol.REST;
        String servingScope = actionRegistry.routedScope(servingCredentialScope(claimedScope, ctx));
        if (rest) {
            return operationForRoute(servingScope, ctx, resourceInfo);
        }
        String targetOperation = IamActionRegistry.targetOperation(ctx);
        String action = actionRegistry.resolve(servingScope, ctx);
        return targetOperation == null ? new ResolvedOperation(servingScope, action)
                : new ResolvedOperation(servingScope, action, servingScope + ":" + targetOperation);
    }

    /**
     * The claim of a call JAX-RS routed to the Smithy RPC v2 endpoint, made from the service and
     * operation in the route's path parameters, which are what that endpoint dispatches. Null for
     * any other request.
     */
    ProtocolClaim rpcV2RouteClaim(ContainerRequestContext ctx, ResourceInfo resourceInfo) {
        if (resourceInfo == null || !AwsJsonCborController.class.equals(resourceInfo.getResourceClass())) {
            return null;
        }
        return ProtocolClaimer.rpcV2RouteClaim(catalog, ctx.getUriInfo().getPathParameters());
    }

    /**
     * The action a REST request's matched route maps to, read from the route alone. Null for any
     * other request, and for a route that maps no action.
     */
    ResolvedOperation restRoute(ContainerRequestContext ctx, ResourceInfo resourceInfo) {
        if (!(ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim)
                || claim.protocol() != WireProtocol.REST
                || resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return null;
        }
        Class<?> resourceClass = resourceInfo.getResourceClass();
        if (SharedTagsController.class.equals(resourceClass) || V1TagsController.class.equals(resourceClass)) {
            return taggedServiceOperation(ctx, resourceInfo);
        }
        return catalog.byResourceClass(resourceClass)
                .map(descriptor -> restOperation(descriptor, ctx, resourceInfo))
                .orElse(null);
    }

    /**
     * A shared tagging route hands the request to the service its ARN names, so it is checked as that
     * service whatever scope it is signed with. A service whose routes map no action for it leaves it unchecked,
     * as any route that maps none; an ARN naming no service Floci serves, which the controller refuses, is left
     * to the signing scope.
     */
    private ResolvedOperation taggedServiceOperation(ContainerRequestContext ctx, ResourceInfo resourceInfo) {
        String arn = arnBuilder.taggedArn(ctx);
        if (!AwsArnUtils.isArn(arn)) {
            return null;
        }
        String service = AwsArnUtils.parse(arn).service();
        return catalog.byCredentialScope(service)
                .map(descriptor -> {
                    ResolvedOperation operation = restOperation(descriptor, ctx, resourceInfo);
                    return operation != null
                            ? operation
                            : new ResolvedOperation(catalog.canonicalCredentialScope(service), null);
                })
                .orElse(null);
    }

    private String routeAction(String scope, ContainerRequestContext ctx, ResourceInfo resourceInfo) {
        String matched = actionRegistry.resolveMatchedRoute(scope, resourceInfo);
        return matched != null ? matched : actionRegistry.resolveRoute(scope, ctx);
    }

    private ResolvedOperation restOperation(ServiceDescriptor descriptor, ContainerRequestContext ctx,
                                            ResourceInfo resourceInfo) {
        return descriptor.credentialScopes().stream()
                .map(catalog::canonicalCredentialScope)
                .map(actionRegistry::routedScope)
                .distinct()
                .sorted()
                .map(scope -> operationForRoute(scope, ctx, resourceInfo))
                .filter(operation -> operation.action() != null)
                .findFirst()
                .orElse(null);
    }

    private ResolvedOperation operationForRoute(String scope, ContainerRequestContext ctx,
                                                ResourceInfo resourceInfo) {
        String action = routeAction(scope, ctx, resourceInfo);
        return new ResolvedOperation(scope, action, actionRegistry.operationForRoute(scope, action, ctx));
    }

    /**
     * The scope of the service that will actually serve this request, which is not always the one
     * the caller signed for. Everything keyed by scope (the action, its ARNs, its condition keys)
     * has to describe the service that runs, or a policy naming that service never matches.
     *
     * <p>Only the claim decides this, never {@code X-Amz-Target} read directly: the target routes
     * a request solely under the conditions {@link ProtocolClaimer} applies, and reading it here
     * would let a header attached to, say, an S3 request move the authorization to another
     * service while S3 still served it.
     *
     * <p>{@link WireProtocol#AWS_QUERY} is resolved before this method by
     * {@link #resolve(String, String, ContainerRequestContext, ResourceInfo)}, which shares
     * {@link AwsQueryServiceResolver} with the controller so IAM enforcement follows the service
     * that will handle the request.
     */
    private String servingCredentialScope(String claimedScope, ContainerRequestContext ctx) {
        if (ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                && claim.service() != null
                && claim.protocol() != WireProtocol.AWS_QUERY) {
            return iamServiceScope(claim.service(), claimedScope);
        }
        return claimedScope;
    }

    /** The descriptor's own scope, keeping {@code claimedScope} when the descriptor accepts it. */
    private String iamServiceScope(ServiceDescriptor descriptor, String claimedScope) {
        if (descriptor.credentialScopes().contains(claimedScope)) {
            return claimedScope;
        }
        // The service's own key, not just any scope it signs under: pricing accepts both
        // "pricing" and "api.pricing", and only the former is the namespace its policies name.
        // Sorting the rest keeps the choice stable, since credentialScopes is a Set.of whose
        // iteration order changes per JVM run.
        if (descriptor.credentialScopes().contains(descriptor.externalKey())) {
            return catalog.canonicalCredentialScope(descriptor.externalKey());
        }
        return descriptor.credentialScopes().stream()
                .filter(scope -> scope.equals(catalog.canonicalCredentialScope(scope)))
                .sorted()
                .findFirst()
                .orElse(catalog.canonicalCredentialScope(descriptor.externalKey()));
    }

    /**
     * The scope and action a request is authorized as, and the operation it calls as
     * {@code <namespace>:<Operation>}, which differs from the action for one AWS authorizes under another name.
     * A modeled REST route retains its operation when AWS authorizes it under another action name. The action is
     * {@code null} when nothing maps the request, and so is the operation when nothing names one.
     */
    public record ResolvedOperation(String credentialScope, String action, String operation) {
        ResolvedOperation(String credentialScope, String action) {
            this(credentialScope, action, action);
        }
    }
}
