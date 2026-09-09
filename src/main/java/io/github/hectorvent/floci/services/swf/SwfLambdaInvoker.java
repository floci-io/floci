package io.github.hectorvent.floci.services.swf;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;

/**
 * Runtime {@link LambdaInvoker}: runs the function through Floci's own Lambda service, which
 * executes it in a container exactly as an {@code Invoke} API call would.
 *
 * <p>Uses {@link InvocationType#RequestResponse} because SWF needs the response body to record
 * as the LambdaFunctionCompleted {@code result} — an {@code Event} invocation would return
 * immediately with nothing to record, the same reason the EventBridge and Scheduler invokers
 * pick {@code Event} for their fire-and-forget targets and this one does not.
 */
@ApplicationScoped
public class SwfLambdaInvoker implements LambdaInvoker {

    private final LambdaService lambdaService;

    @Inject
    public SwfLambdaInvoker(LambdaService lambdaService) {
        this.lambdaService = lambdaService;
    }

    @Override
    public LambdaInvocationResult invoke(String region, String functionName, byte[] payload) {
        InvokeResult result = lambdaService.invoke(region, functionName, payload,
                InvocationType.RequestResponse);
        byte[] body = result.getPayload();
        return new LambdaInvocationResult(
                body == null ? "" : new String(body, StandardCharsets.UTF_8),
                result.getFunctionError());
    }
}
