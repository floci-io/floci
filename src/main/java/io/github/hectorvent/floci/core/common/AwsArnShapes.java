package io.github.hectorvent.floci.core.common;

import java.util.regex.Pattern;

/** Resource shapes shared by services; account, partition and API error rules stay with callers. */
public final class AwsArnShapes {

    private static final Pattern SQS_QUEUE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,80}|[A-Za-z0-9_-]{1,75}\\.fifo");

    private AwsArnShapes() {
    }

    public static boolean isIamRole(AwsArnUtils.Arn arn) {
        return "iam".equals(arn.service()) && arn.region().isEmpty()
                && !arn.accountId().isBlank() && arn.resource().startsWith("role/")
                && !arn.resource().substring(5).isBlank();
    }

    public static boolean isSqsQueueName(String name) {
        return name != null && SQS_QUEUE_NAME.matcher(name).matches();
    }
}
