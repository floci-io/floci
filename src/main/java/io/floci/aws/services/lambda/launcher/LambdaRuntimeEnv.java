package io.floci.aws.services.lambda.launcher;

import io.floci.aws.services.lambda.model.LambdaFunction;

import java.util.ArrayList;
import java.util.List;

/** The Lambda runtime environment every launcher gives an execution environment, before the SDK baseline. */
public final class LambdaRuntimeEnv {

    private LambdaRuntimeEnv() {
    }

    /** Returns a mutable list, so a launcher can add or override variables after it. */
    public static List<String> of(LambdaFunction fn, String runtimeApi, String logGroup, String logStream) {
        List<String> env = new ArrayList<>();
        env.add("AWS_LAMBDA_RUNTIME_API=" + runtimeApi);
        env.add("AWS_LAMBDA_FUNCTION_NAME=" + fn.getFunctionName());
        env.add("AWS_LAMBDA_FUNCTION_MEMORY_SIZE=" + fn.getMemorySize());
        env.add("AWS_LAMBDA_FUNCTION_TIMEOUT=" + fn.getTimeout());
        env.add("AWS_LAMBDA_LOG_GROUP_NAME=" + logGroup);
        env.add("AWS_LAMBDA_LOG_STREAM_NAME=" + logStream);
        // LoggingConfig: the runtime interface clients read these to pick the log format and the
        // application log level, so without them a JSON function still logs text.
        if (fn.getLogFormat() != null) {
            env.add("AWS_LAMBDA_LOG_FORMAT=" + fn.getLogFormat());
        }
        if (fn.applicationLogLevelOrDefault() != null) {
            env.add("AWS_LAMBDA_LOG_LEVEL=" + fn.applicationLogLevelOrDefault());
        }
        if (fn.getHandler() != null && !fn.getHandler().isBlank()) {
            env.add("_HANDLER=" + fn.getHandler());
        }
        return env;
    }

    /**
     * Adds the function's own {@code Environment} and its version after the SDK baseline. Credential
     * injection is all-or-nothing: a partial override (only {@code AWS_ACCESS_KEY_ID}, say) must never
     * join the baseline's other two values, which would pair a user-chosen key with the baseline's
     * secret and session token, a tuple nothing can verify. The function's credentials apply only as a
     * complete triad, and only without an execution role session, which is then the authoritative
     * credential source.
     */
    public static void addFunctionEnvironment(List<String> env, LambdaFunction fn,
                                              boolean hasExecutionRoleCredentials) {
        if (fn.getEnvironment() != null) {
            boolean functionCredentialsApply = !hasExecutionRoleCredentials
                    && ContainerLauncher.definesFullCredentialTriad(fn.getEnvironment());
            fn.getEnvironment().forEach((key, value) -> {
                if (!ContainerLauncher.isAwsCredentialVariable(key) || functionCredentialsApply) {
                    env.add(key + "=" + value);
                }
            });
        }
        env.removeIf(value -> value.startsWith("AWS_LAMBDA_FUNCTION_VERSION="));
        env.add("AWS_LAMBDA_FUNCTION_VERSION=" + fn.getVersion());
    }
}
