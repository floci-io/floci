package io.floci.aws.services.redshiftserverless;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.IamConditionContextResolver;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.floci.aws.services.iam.IamService.CallerArns;
import io.floci.aws.services.iam.RequestCallerResolver;
import io.floci.aws.services.iam.RequestPrincipal;
import io.floci.aws.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Narrows the snapshots another account owns to the ones its resource policy shares with the caller, for
 * ListSnapshots, which IAM cannot filter item by item. A snapshot with no policy, or a policy that does not
 * allow the caller {@code redshift-serverless:GetSnapshot}, is not shared. GetSnapshot and RestoreFromSnapshot
 * name one snapshot, so IAM enforcement checks them through {@link RedshiftServerlessResourcePolicyProvider}.
 */
@ApplicationScoped
public class RedshiftServerlessSharedSnapshots {

    static final String GET_ACTION = "redshift-serverless:GetSnapshot";

    private final RedshiftServerlessResourcePolicyService policies;
    private final IamPolicyEvaluator evaluator;
    private final RegionResolver regionResolver;
    private final RequestCallerResolver callers;
    private final RedshiftServerlessResourcePolicyProvider resourcePolicyProvider;

    @Inject
    public RedshiftServerlessSharedSnapshots(RedshiftServerlessResourcePolicyService policies,
                                             IamPolicyEvaluator evaluator, RegionResolver regionResolver,
                                             RequestCallerResolver callers,
                                             RedshiftServerlessResourcePolicyProvider resourcePolicyProvider) {
        this.policies = policies;
        this.evaluator = evaluator;
        this.regionResolver = regionResolver;
        this.callers = callers;
        this.resourcePolicyProvider = resourcePolicyProvider;
    }

    /**
     * The snapshots of {@code ownerAccount} that are shared with the caller. One that is not shared is left out,
     * as it is for any caller that cannot see it, rather than refused.
     */
    public List<RedshiftServerlessSnapshot> readable(String ownerAccount, List<RedshiftServerlessSnapshot> snapshots,
                                                     String region) {
        return snapshots.stream().filter(snapshot -> isShared(snapshot, ownerAccount, region)).toList();
    }

    private boolean isShared(RedshiftServerlessSnapshot snapshot, String ownerAccount, String region) {
        String policy = policies.policyOfSnapshot(ownerAccount, snapshot, region).orElse(null);
        return policy != null && allows(policy, snapshot, ownerAccount, region);
    }

    private boolean allows(String policy, RedshiftServerlessSnapshot snapshot, String ownerAccount, String region) {
        CallerArns caller = callers.caller();
        // an account in one partition cannot be granted a snapshot of another, whatever the policy names
        if (!AwsArnUtils.parse(snapshot.getSnapshotArn()).partition()
                .equals(AwsArnUtils.parse(caller.callerArn()).partition())) {
            return false;
        }
        Map<String, List<String>> context = new HashMap<>();
        caller.putPrincipalKeys(context);
        Map<String, List<String>> effective = IamConditionContextResolver.withGlobalContext(context,
                snapshot.getSnapshotArn(), region, regionResolver.getAccountId(), ownerAccount, GET_ACTION);
        String normalizedPolicy = resourcePolicyProvider.withResource(policy, snapshot.getSnapshotArn());
        ResourcePolicyDecision decision = evaluator.evaluateResourcePolicyFor(List.of(normalizedPolicy),
                RequestPrincipal.caller(caller), GET_ACTION, snapshot.getSnapshotArn(), effective);
        // ALLOW_ACCOUNT is a grant to the caller's whole account, which a cross-account policy may give.
        return decision == ResourcePolicyDecision.ALLOW || decision == ResourcePolicyDecision.ALLOW_ACCOUNT
                || decision == ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER;
    }
}
