package io.floci.aws.services.redshiftserverless;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamConditionContextResolver;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.floci.aws.services.iam.IamService.CallerArns;
import io.floci.aws.services.iam.RequestCallerResolver;
import io.floci.aws.services.iam.RequestPrincipal;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Whether a caller may use the customer managed KMS key that encrypts a snapshot another account owns. Redshift
 * Serverless asks KMS for it on the caller's behalf when the snapshot is restored, so no request names the key and
 * IAM enforcement never sees it: the restore is refused here unless the key policy allows the caller
 * {@code kms:Decrypt}. A snapshot under the AWS owned key needs no key access, which is how AWS lets it be shared
 * without touching KMS.
 *
 * <p>Only the key policy is read. AWS also asks the caller's own identity policy for the key when the key policy
 * names only the caller's account; that half is not checked yet. With IAM enforcement off nothing is evaluated,
 * as for every other policy.
 */
@ApplicationScoped
public class RedshiftServerlessSnapshotKeyAccess {

    static final String DECRYPT_ACTION = "kms:Decrypt";

    private final EmulatorConfig config;
    private final KmsService kms;
    private final IamPolicyEvaluator evaluator;
    private final RegionResolver regionResolver;
    private final RequestCallerResolver callers;

    @Inject
    public RedshiftServerlessSnapshotKeyAccess(EmulatorConfig config, KmsService kms, IamPolicyEvaluator evaluator,
                                               RegionResolver regionResolver, RequestCallerResolver callers) {
        this.config = config;
        this.kms = kms;
        this.evaluator = evaluator;
        this.regionResolver = regionResolver;
        this.callers = callers;
    }

    /** Refuses the restore of {@code snapshot}, owned by {@code ownerAccount}, when the caller may not use its key. */
    public void requireRestoreAccess(RedshiftServerlessSnapshot snapshot, String ownerAccount, String region) {
        if (!config.services().iam().enforcementEnabled()) {
            return;
        }
        Optional<String> keyArn = customerKeyArn(snapshot.getKmsKeyId(), ownerAccount, region);
        if (keyArn.isEmpty()) {
            return;
        }
        CallerArns caller = callers.caller();
        String policy = kms.findKeyPolicyOfAccount(keyArn.get()).orElse(null);
        if (policy == null || !allows(policy, caller, keyArn.get(), region)) {
            throw new AwsException("AccessDeniedException", "User: " + caller.callerArn()
                    + " is not authorized to perform: " + DECRYPT_ACTION + " on resource: " + keyArn.get()
                    + ", the key of snapshot " + snapshot.getSnapshotName(), 400);
        }
    }

    /**
     * The ARN of the customer managed key, or empty for a snapshot with none or under the AWS owned key. A bare key
     * id or alias name is the owner's, in the snapshot's Region.
     */
    private Optional<String> customerKeyArn(String kmsKeyId, String ownerAccount, String region) {
        if (kmsKeyId == null || kmsKeyId.isBlank() || RedshiftServerlessService.AWS_OWNED_KMS_KEY.equals(kmsKeyId)) {
            return Optional.empty();
        }
        if (AwsArnUtils.isArnFor(kmsKeyId, "kms")) {
            return Optional.of(kmsKeyId);
        }
        String resource = kmsKeyId.startsWith("alias/") ? kmsKeyId : "key/" + kmsKeyId;
        return Optional.of(regionResolver.buildArn("kms", region, ownerAccount, resource));
    }

    private boolean allows(String policy, CallerArns caller, String keyArn, String region) {
        String keyAccount = AwsArnUtils.parse(keyArn).accountId();
        Map<String, List<String>> context = new HashMap<>();
        caller.putPrincipalKeys(context);
        Map<String, List<String>> effective = IamConditionContextResolver.withGlobalContext(context, keyArn, region,
                regionResolver.getAccountId(), keyAccount, DECRYPT_ACTION);
        ResourcePolicyDecision decision = evaluator.evaluateResourcePolicyFor(List.of(policy),
                RequestPrincipal.caller(caller), DECRYPT_ACTION, keyArn, effective);
        // ALLOW_ACCOUNT is a grant to the caller's whole account, which a key policy gives another account this way.
        return decision == ResourcePolicyDecision.ALLOW || decision == ResourcePolicyDecision.ALLOW_ACCOUNT
                || decision == ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER;
    }
}
