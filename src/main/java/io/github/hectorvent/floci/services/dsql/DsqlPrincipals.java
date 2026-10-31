package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is calling, and whether an IAM principal a cluster policy names exists: what DSQL checks a
 * policy against. A credential that names no IAM identity is its account's root, as for STS.
 */
@ApplicationScoped
public class DsqlPrincipals {

    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final Instance<RequestContext> requestContext;

    @Inject
    public DsqlPrincipals(IamService iamService, RegionResolver regionResolver,
                          Instance<RequestContext> requestContext) {
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
    }

    public CallerArns caller() {
        return callerOf(accessKeyId());
    }

    /** Who signs with {@code accessKeyId}, as {@link #caller()} names the caller of a request. */
    public CallerArns callerOf(String accessKeyId) {
        String accountId = regionResolver.getAccountId();
        return iamService.resolveCallerArns(accessKeyId)
                .orElseGet(() -> CallerArns.accountRoot(regionResolver.buildGlobalArn("iam", accountId, "root"),
                        accountId));
    }

    /**
     * The context a cluster policy is read in for {@code caller}: its principal keys, the global
     * keys of the cluster in {@code region}, and {@code keys}, such as a connection's
     * {@code aws:SourceIp}.
     */
    public Map<String, List<String>> policyContext(CallerArns caller, String clusterArn, String region,
                                                   Map<String, List<String>> keys) {
        Map<String, List<String>> context = new HashMap<>(keys);
        caller.putPrincipalKeys(context);
        String accountId = regionResolver.getAccountId();
        return IamConditionContextResolver.withGlobalContext(context, clusterArn, region, accountId, accountId);
    }

    /**
     * Whether the IAM principal {@code arn} names exists. Floci takes every account to exist, so an
     * account's root always does; a role or a user is looked up in its account by its name.
     */
    public boolean exists(String arn) {
        return iamService.heldPrincipalArn(arn).isPresent();
    }

    private String accessKeyId() {
        try {
            return requestContext.isResolvable() ? requestContext.get().getAccessKeyId() : null;
        } catch (ContextNotActiveException e) {
            // A call outside a request, such as CloudFormation's, is the account's own.
            return null;
        }
    }
}
