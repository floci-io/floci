package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Supplies SNS topic policies to IAM policy evaluation, together with the account that owns the
 * topic: the one in its ARN, also when the topic does not exist, so a call to another account's
 * topic is judged as cross-account. A topic whose {@code Policy} attribute is not set is supplied the
 * default an API-created topic has.
 *
 * <p>Keyed on the ARN rather than the request's credential scope, so the same policy decides a call
 * an AWS service makes to the topic, such as SES publishing an event notification.
 */
@ApplicationScoped
public class SnsResourcePolicyProvider implements ResourcePolicyProvider {

    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    /** A topic name, as CreateTopic accepts it; subscriptions, endpoints and applications are not topics. */
    private static final Pattern TOPIC_NAME = Pattern.compile("[A-Za-z0-9_-]{1,256}(\\.fifo)?");

    private final SnsService snsService;

    @Inject
    public SnsResourcePolicyProvider(SnsService snsService) {
        this.snsService = snsService;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!AwsArnUtils.isArnFor(resourceArn, "sns")) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        if (!ACCOUNT_ID.matcher(arn.accountId()).matches() || arn.region().isBlank()
                || !TOPIC_NAME.matcher(arn.resource()).matches()) {
            return List.of();
        }
        String policy = snsService.findTopicPolicy(resourceArn).orElse(null);
        return List.of(new ResourcePolicy(policy, arn.accountId()));
    }
}
