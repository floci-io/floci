package io.github.hectorvent.floci.services.lambda.durable;

/**
 * The slice of Lambda invocation the durable engine needs, so {@link DurableExecutionService}
 * depends on a two-method seam rather than on the container stack. Tests supply a scripted
 * stand-in for the function. At runtime {@link LambdaDurableFunctionInvoker} runs the real one.
 */
public interface DurableFunctionInvoker {

    /** The function a durable execution runs on, or an AwsException when the name does not resolve. */
    ResolvedDurableTarget resolve(String accountId, String region, String functionName, String qualifier);

    DurableInvocationResult invoke(ResolvedDurableTarget target, byte[] payload, String requestId);

    record ResolvedDurableTarget(String accountId,
                                 String region,
                                 String functionName,
                                 String functionArn,
                                 String version,
                                 boolean durable,
                                 int executionTimeoutSeconds,
                                 int retentionPeriodInDays) {
    }

    record DurableInvocationResult(String requestId, byte[] payload, String functionError) {
    }
}
