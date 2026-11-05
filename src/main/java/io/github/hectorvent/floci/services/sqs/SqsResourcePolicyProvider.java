package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Supplies SQS queue policies to IAM policy evaluation, together with the account that owns the
 * queue: the one in its ARN, also when the queue does not exist or has no policy, so a call to
 * another account's queue is judged as cross-account.
 */
@ApplicationScoped
public class SqsResourcePolicyProvider implements ResourcePolicyProvider {

    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");

    private final SqsService sqsService;

    @Inject
    public SqsResourcePolicyProvider(SqsService sqsService) {
        this.sqsService = sqsService;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!AwsArnUtils.isArnFor(resourceArn, "sqs")) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        // ListQueues and a request with no QueueUrl name every queue, which is not one queue's call.
        if (!ACCOUNT_ID.matcher(arn.accountId()).matches() || arn.region().isBlank()
                || arn.resource().isBlank() || arn.resource().contains("*")) {
            return List.of();
        }
        return List.of(new ResourcePolicy(sqsService.findQueuePolicy(resourceArn).orElse(null), arn.accountId()));
    }
}
