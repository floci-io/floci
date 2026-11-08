package io.floci.aws.services.eventbridge;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Supplies event bus policies to IAM policy evaluation, together with the account that owns the bus:
 * the one in its ARN, also when the bus does not exist or has no policy, so a call to another
 * account's bus is judged as cross-account.
 */
@ApplicationScoped
public class EventBridgeResourcePolicyProvider implements ResourcePolicyProvider {

    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final String BUS_PREFIX = "event-bus/";

    private final EventBridgeService eventBridgeService;

    @Inject
    public EventBridgeResourcePolicyProvider(EventBridgeService eventBridgeService) {
        this.eventBridgeService = eventBridgeService;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!AwsArnUtils.isArnFor(resourceArn, "events")) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        String name = arn.resource().startsWith(BUS_PREFIX) ? arn.resource().substring(BUS_PREFIX.length()) : "";
        if (!ACCOUNT_ID.matcher(arn.accountId()).matches() || arn.region().isBlank()
                || name.isBlank() || name.contains("*") || name.contains("/")) {
            return List.of();
        }
        return List.of(new ResourcePolicy(eventBridgeService.findBusPolicy(resourceArn).orElse(null),
                arn.accountId()));
    }
}
