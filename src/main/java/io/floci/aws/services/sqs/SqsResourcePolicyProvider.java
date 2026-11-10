package io.floci.aws.services.sqs;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Supplies SQS queue policies to IAM policy evaluation, together with the account that owns the
 * queue: the one in its ARN, also when the queue does not exist or has no policy, so a call to
 * another account's queue is judged as cross-account.
 */
@ApplicationScoped
public class SqsResourcePolicyProvider implements ResourcePolicyProvider {


    private final SqsService sqsService;

    @Inject
    public SqsResourcePolicyProvider(SqsService sqsService) {
        this.sqsService = sqsService;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        Optional<AwsArnUtils.Arn> parsed = ResourcePolicyProvider.regionalArn(resourceArn, "sqs");
        if (parsed.isEmpty()) {
            return List.of();
        }
        AwsArnUtils.Arn arn = parsed.get();
        // ListQueues and a request with no QueueUrl name every queue, which is not one queue's call.
        if (arn.resource().isBlank() || arn.resource().contains("*")) {
            return List.of();
        }
        return List.of(new ResourcePolicy(sqsService.findQueuePolicy(resourceArn).orElse(null), arn.accountId()));
    }
}
