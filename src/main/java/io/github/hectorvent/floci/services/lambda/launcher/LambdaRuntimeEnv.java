package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;

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
}
