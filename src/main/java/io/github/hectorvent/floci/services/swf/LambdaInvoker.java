package io.github.hectorvent.floci.services.swf;

/**
 * The slice of Lambda invocation SWF needs, so {@link SwfService} depends on a two-method
 * seam rather than on {@code LambdaService} itself.
 *
 * <p>This keeps the service unit-testable without a Docker daemon or the Lambda container
 * stack: {@code SwfServiceTest} supplies a stub, while at runtime {@link SwfLambdaInvoker}
 * delegates to the real Lambda service and its containers.
 */
interface LambdaInvoker {

    /**
     * Invokes {@code functionName} synchronously and returns its response.
     *
     * @throws io.github.hectorvent.floci.core.common.AwsException if the function cannot be
     *         resolved or invoked, so the caller can record the AWS error code as the
     *         LambdaFunctionFailed {@code reason}
     */
    LambdaInvocationResult invoke(String region, String functionName, byte[] payload);
}
