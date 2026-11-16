package io.floci.aws.services.dsql;

import io.floci.aws.core.common.IamConditionContextResolver;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.IamService.CallerArns;
import io.floci.aws.services.iam.RequestCallerResolver;
import jakarta.enterprise.context.ApplicationScoped;
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
    private final RequestCallerResolver callers;

    @Inject
    public DsqlPrincipals(IamService iamService, RegionResolver regionResolver, RequestCallerResolver callers) {
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.callers = callers;
    }

    public CallerArns caller() {
        return callers.caller();
    }

    /** Who signs with {@code accessKeyId}, as {@link #caller()} names the caller of a request. */
    public CallerArns callerOf(String accessKeyId) {
        return callers.callerOf(accessKeyId);
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
}
