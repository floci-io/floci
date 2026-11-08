package io.floci.aws.services.lambda;

import io.floci.aws.services.lambda.model.LambdaFunction;

/**
 * Where a function's execution environments write their logs: the log group its LoggingConfig
 * names, else {@code /aws/lambda/<name>}. Once LoggingConfig names a group, which several
 * functions can share, AWS puts the function's name in each stream's name, even when the group
 * named is the default one: {@code 2026/10/16/my-fn[$LATEST]<id>} rather than
 * {@code 2026/10/16/[$LATEST]<id>}.
 */
public final class LambdaLogDestinations {

    private LambdaLogDestinations() {
    }

    public static String logGroup(LambdaFunction fn) {
        return namesLogGroup(fn) ? fn.getLogGroup() : defaultLogGroup(fn.getFunctionName());
    }

    /** The group of a function whose LoggingConfig names none: {@code /aws/lambda/<name>}. */
    public static String defaultLogGroup(String functionName) {
        return "/aws/lambda/" + functionName;
    }

    /** The stream's name after its {@code yyyy/MM/dd/} date, for the environment {@code instanceId}. */
    public static String logStreamSuffix(LambdaFunction fn, String instanceId) {
        String version = "[$LATEST]" + instanceId;
        return namesLogGroup(fn) ? fn.getFunctionName() + version : version;
    }

    /**
     * Whether {@code logStreamName} in the function's log group is the function's own: every stream
     * of the default group is, and in a group LoggingConfig names, which several functions can
     * share, the ones that carry its name.
     */
    public static boolean isOwnStream(LambdaFunction fn, String logStreamName) {
        if (!namesLogGroup(fn)) {
            return true;
        }
        return logStreamName != null && logStreamName.contains("/" + fn.getFunctionName() + "[");
    }

    private static boolean namesLogGroup(LambdaFunction fn) {
        return fn.getLogGroup() != null && !fn.getLogGroup().isBlank();
    }
}
