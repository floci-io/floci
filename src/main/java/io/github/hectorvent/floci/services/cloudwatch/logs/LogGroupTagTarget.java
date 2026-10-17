package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;

/** The same locally owned group identity for direct tag authorization and execution. */
public record LogGroupTagTarget(String name, String arn) {

    public static boolean supports(String action) {
        return "logs:TagResource".equals(action) || "logs:UntagResource".equals(action)
                || "logs:ListTagsForResource".equals(action);
    }

    public static LogGroupTagTarget resolve(String input, String region, String account, String partition) {
        String prefix = new AwsArnUtils.Arn(partition, "logs", region, account, "log-group:").toString();
        if (AwsArnUtils.isArn(input) && !input.startsWith(prefix)) {
            throw new AwsException("ResourceNotFoundException", "The specified log group does not exist.", 400);
        }
        // Plain names remain literal; a marker inside a name must not select another group.
        String name = input.startsWith(prefix) ? input.substring(prefix.length()) : input;
        if (input.startsWith(prefix) && name.endsWith(":*")) {
            name = name.substring(0, name.length() - 2);
        }
        return new LogGroupTagTarget(name,
                new AwsArnUtils.Arn(partition, "logs", region, account, "log-group:" + name).toString());
    }
}
