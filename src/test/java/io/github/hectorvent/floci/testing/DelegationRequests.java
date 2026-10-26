package io.github.hectorvent.floci.testing;

/**
 * The ARN a delegation request is named by, which both IAM delegation suites need: the one the
 * wire passes to {@code GetHumanReadableSummary} and the one a policy statement is written
 * against. Floci mints it from the request id under the account that asks about it, so the two
 * suites cannot read the format differently while one of them is the only one updated.
 */
public final class DelegationRequests {

    private DelegationRequests() {
    }

    /** The ARN of one request, as {@code ResourceArnBuilder} mints it. */
    public static String entityArn(String accountId, String id) {
        return "arn:aws:iam::" + accountId + ":delegation-request/" + id;
    }
}
