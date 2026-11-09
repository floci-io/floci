package io.floci.aws.services.redshiftserverless;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamConditionContextResolver;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.IamService.CallerArns;
import io.floci.aws.services.iam.RequestPrincipal;
import io.floci.aws.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether the caller may use a snapshot another account owns. The snapshot's resource policy
 * is the only thing that can grant it: a snapshot with no policy, or a policy that does not allow
 * the caller, is not shared. The caller's own identity policies are the IAM filter's to check, when
 * IAM enforcement is on, so only the resource side is read here.
 */
@ApplicationScoped
public class RedshiftServerlessSharedSnapshots {

    static final String RESTORE_ACTION = "redshift-serverless:RestoreFromSnapshot";

    private final RedshiftServerlessService serverless;
    private final RedshiftServerlessResourcePolicyService policies;
    private final IamPolicyEvaluator evaluator;
    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final Instance<RequestContext> requestContext;

    @Inject
    public RedshiftServerlessSharedSnapshots(RedshiftServerlessService serverless,
                                             RedshiftServerlessResourcePolicyService policies,
                                             IamPolicyEvaluator evaluator, IamService iamService,
                                             RegionResolver regionResolver, Instance<RequestContext> requestContext) {
        this.serverless = serverless;
        this.policies = policies;
        this.evaluator = evaluator;
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
    }

    /** Whether {@code ownerAccount} names an account other than the caller's. */
    public boolean isOtherAccount(String ownerAccount) {
        return ownerAccount != null && !ownerAccount.isBlank() && !ownerAccount.equals(regionResolver.getAccountId());
    }

    /**
     * The snapshot {@code snapshotName} of {@code ownerAccount}, once its policy allows the caller to restore
     * it. A snapshot that does not exist is reported the same way for every caller.
     */
    public RedshiftServerlessSnapshot requireRestorable(String ownerAccount, String snapshotName, String region) {
        RedshiftServerlessSnapshot snapshot = serverless.findSnapshotOfAccount(ownerAccount, snapshotName, region)
                .orElseThrow(() -> ServerlessSupport.notFound("The snapshot " + snapshotName + " was not found."));
        String policy = policies.policyOfSnapshot(ownerAccount, snapshot, region).orElse(null);
        if (policy == null || !allows(policy, snapshot, ownerAccount, region)) {
            throw new AwsException("AccessDeniedException", "User: " + callerArns().callerArn()
                    + " is not authorized to perform: " + RESTORE_ACTION + " on resource: "
                    + snapshot.getSnapshotArn(), 400);
        }
        return snapshot;
    }

    private boolean allows(String policy, RedshiftServerlessSnapshot snapshot, String ownerAccount, String region) {
        CallerArns caller = callerArns();
        Map<String, List<String>> context = new HashMap<>();
        caller.putPrincipalKeys(context);
        Map<String, List<String>> effective = IamConditionContextResolver.withGlobalContext(context,
                snapshot.getSnapshotArn(), region, regionResolver.getAccountId(), ownerAccount, RESTORE_ACTION);
        ResourcePolicyDecision decision = evaluator.evaluateResourcePolicyFor(List.of(policy),
                RequestPrincipal.caller(caller), RESTORE_ACTION, snapshot.getSnapshotArn(), effective);
        // ALLOW_ACCOUNT is a grant to the caller's whole account, which a cross-account policy may give.
        return decision == ResourcePolicyDecision.ALLOW || decision == ResourcePolicyDecision.ALLOW_ACCOUNT
                || decision == ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER;
    }

    private CallerArns callerArns() {
        String accountId = regionResolver.getAccountId();
        return iamService.resolveCallerArns(accessKeyId())
                .orElseGet(() -> CallerArns.accountRoot(regionResolver.buildGlobalArn("iam", accountId, "root"),
                        accountId));
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
