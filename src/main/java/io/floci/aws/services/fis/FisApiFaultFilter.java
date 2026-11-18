package io.floci.aws.services.fis;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsProtocolClaimFilter;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.ProtocolClaim;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestOperationResolver;
import io.floci.aws.core.common.RequestOperationResolver.ResolvedOperation;
import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SigV4CredentialScope;
import io.floci.aws.core.common.WireProtocol;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.IamService.CallerArns;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Fails the calls a running {@code aws:fis:inject-api-*} action impairs: calls made with a session of one of the
 * action's target roles, to one of the operations it names in the namespace it names, at the percentage it sets.
 *
 * <p>Post-matching, so the operation is read from the route that will serve the call, as IAM enforcement reads it,
 * whatever protocol the call uses. It runs after {@link IamEnforcementFilter}, so a call IAM refuses is refused
 * whatever an experiment does. Until an experiment is running in the caller's account and Region, a request costs
 * one lookup of an empty list.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.USER + 100)
public class FisApiFaultFilter implements ContainerRequestFilter {

    private final EmulatorConfig config;
    private final FisService fisService;
    private final IamService iamService;
    private final AccountResolver accountResolver;
    private final RequestContext requestContext;
    private final RequestOperationResolver operationResolver;
    private final ResolvedServiceCatalog catalog;
    private final ResourceInfo resourceInfo;
    /** An instance field, not a static one, so the native image needs no run-time initialization for it. */
    private final RandomGenerator random;

    @Inject
    public FisApiFaultFilter(EmulatorConfig config, FisService fisService, IamService iamService,
                             AccountResolver accountResolver, RequestContext requestContext,
                             RequestOperationResolver operationResolver, ResolvedServiceCatalog catalog,
                             @Context ResourceInfo resourceInfo) {
        this(config, fisService, iamService, accountResolver, requestContext, operationResolver, catalog,
                resourceInfo, new Random());
    }

    FisApiFaultFilter(EmulatorConfig config, FisService fisService, IamService iamService,
                      AccountResolver accountResolver, RequestContext requestContext,
                      RequestOperationResolver operationResolver, ResolvedServiceCatalog catalog,
                      ResourceInfo resourceInfo, RandomGenerator random) {
        this.config = config;
        this.fisService = fisService;
        this.iamService = iamService;
        this.accountResolver = accountResolver;
        this.requestContext = requestContext;
        this.operationResolver = operationResolver;
        this.catalog = catalog;
        this.resourceInfo = resourceInfo;
        this.random = random;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!config.services().fis().enabled()) {
            return;
        }
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        List<FisApiFault> faults = fisService.activeApiFaults(region);
        if (faults.isEmpty()) {
            return;
        }
        String auth = IamEnforcementFilter.requestAuthorization(ctx.getHeaderString("Authorization"),
                ctx.getUriInfo().getQueryParameters());
        String accessKeyId = auth == null ? null : accountResolver.extractAccessKeyId(auth);
        String scope = auth == null ? null : SigV4CredentialScope.serviceName(auth).orElse(null);
        if (accessKeyId == null || scope == null) {
            return;
        }
        String roleArn = iamService.resolveCallerArns(accessKeyId).map(CallerArns::principalArn).orElse(null);
        if (roleArn == null || faults.stream().noneMatch(fault -> fault.roleArns().contains(roleArn))) {
            return;
        }
        ResolvedOperation resolved = operationResolver.resolve(auth, catalog.canonicalCredentialScope(scope), ctx,
                resourceInfo);
        String operation = afterNamespace(resolved.operation());
        if (operation == null) {
            return;
        }
        String iamNamespace = resolved.action() == null ? null : beforeColon(resolved.action());
        Set<String> namespaces = iamNamespace == null || iamNamespace.equals(resolved.credentialScope())
                ? Set.of(resolved.credentialScope())
                : Set.of(resolved.credentialScope(), iamNamespace);
        for (FisApiFault fault : faults) {
            if (fault.matches(roleArn, namespaces, operation) && random.nextInt(100) < fault.percentage()) {
                ctx.abortWith(FisApiErrors.response(fault.kind(), resolved.credentialScope(), claimedProtocol(ctx),
                        catalog.byCredentialScope(resolved.credentialScope()).orElse(null), ctx));
                return;
            }
        }
    }

    /** An operation's own name, without the {@code <namespace>:} the resolver puts before it. */
    private static String afterNamespace(String operation) {
        if (operation == null) {
            return null;
        }
        int colon = operation.indexOf(':');
        return colon < 0 ? operation : operation.substring(colon + 1);
    }

    /** The IAM namespace an action is in, the {@code <namespace>} of {@code <namespace>:<Action>}. */
    private static String beforeColon(String action) {
        int colon = action.indexOf(':');
        return colon < 0 ? action : action.substring(0, colon);
    }

    private static WireProtocol claimedProtocol(ContainerRequestContext ctx) {
        return ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                ? claim.protocol() : null;
    }
}
