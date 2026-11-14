package io.floci.aws.services.kms;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Supplies a KMS key's key policy to IAM policy evaluation, together with the account that owns the key: the one in
 * its ARN, also when the key does not exist or has no policy, so a call on another account's key is judged as
 * cross-account, needing the key policy as well as the caller's own. An alias has no policy of its own, and an ARN
 * with its id open names no one key.
 */
@ApplicationScoped
public class KmsResourcePolicyProvider implements ResourcePolicyProvider {

    private static final String KEY_PREFIX = "key/";

    private final KmsService kms;

    @Inject
    public KmsResourcePolicyProvider(KmsService kms) {
        this.kms = kms;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        Optional<AwsArnUtils.Arn> parsed = ResourcePolicyProvider.regionalArn(resourceArn, "kms");
        if (parsed.isEmpty()) {
            return List.of();
        }
        String target = parsed.get().resource();
        if (!target.startsWith(KEY_PREFIX) || target.length() == KEY_PREFIX.length() || target.contains("*")) {
            return List.of();
        }
        return List.of(new ResourcePolicy(kms.findKeyPolicy(resourceArn).orElse(null), parsed.get().accountId()));
    }
}
