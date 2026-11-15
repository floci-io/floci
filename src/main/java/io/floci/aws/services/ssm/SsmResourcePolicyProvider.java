package io.floci.aws.services.ssm;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Supplies the grant sharing an SSM document gives to IAM policy evaluation: the accounts its owner shared it with
 * through {@code ModifyDocumentPermission} may associate it, as a resource policy on the document would let them.
 * The owner is the account in the document's ARN, so a call naming another account's document is judged as
 * cross-account.
 */
@ApplicationScoped
public class SsmResourcePolicyProvider implements ResourcePolicyProvider {

    private static final String DOCUMENT_PREFIX = "document/";

    private final SsmService ssmService;

    @Inject
    public SsmResourcePolicyProvider(SsmService ssmService) {
        this.ssmService = ssmService;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        Optional<AwsArnUtils.Arn> parsed = ResourcePolicyProvider.regionalArn(resourceArn, "ssm");
        if (parsed.isEmpty() || !parsed.get().resource().startsWith(DOCUMENT_PREFIX)) {
            return List.of();
        }
        AwsArnUtils.Arn arn = parsed.get();
        List<String> sharedWith = ssmService.documentSharedWith(arn.accountId(),
                arn.resource().substring(DOCUMENT_PREFIX.length()), arn.region());
        if (sharedWith.isEmpty()) {
            return List.of(new ResourcePolicy(null, arn.accountId()));
        }
        String principals = sharedWith.stream()
                .map(account -> "\"" + AwsArnUtils.Arn.global(arn.partition(), "iam", account, "root") + "\"")
                .collect(Collectors.joining(","));
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":["
                + principals + "]},\"Action\":\"ssm:CreateAssociation\",\"Resource\":\"" + resourceArn + "\"}]}";
        return List.of(new ResourcePolicy(policy, arn.accountId()));
    }
}
