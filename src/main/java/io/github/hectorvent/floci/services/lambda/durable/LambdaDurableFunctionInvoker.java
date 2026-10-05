package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaTargetResolver;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Runs durable invocations on the real Lambda containers. */
@ApplicationScoped
public class LambdaDurableFunctionInvoker implements DurableFunctionInvoker {

    private final LambdaTargetResolver targetResolver;
    private final LambdaExecutorService executorService;

    @Inject
    public LambdaDurableFunctionInvoker(LambdaTargetResolver targetResolver, LambdaExecutorService executorService) {
        this.targetResolver = targetResolver;
        this.executorService = executorService;
    }

    @Override
    public ResolvedDurableTarget resolve(String accountId, String region, String functionName, String qualifier) {
        LambdaFunction fn = targetResolver.resolveInvokeTargetForAccount(accountId, region, functionName, qualifier);
        String version = fn.getVersion();
        String qualifiedArn = "$LATEST".equals(version) ? fn.getFunctionArn() + ":$LATEST" : fn.getFunctionArn();
        return new ResolvedDurableTarget(accountId, region, fn.getFunctionName(), qualifiedArn, version,
                fn.isDurable(),
                fn.getDurableExecutionTimeout() != null ? fn.getDurableExecutionTimeout() : 0,
                fn.getDurableRetentionPeriodInDays() != null ? fn.getDurableRetentionPeriodInDays() : 0);
    }

    /**
     * Resolves the function again on every invocation. An execution is pinned to a version, and a
     * {@code $LATEST} execution resumes on whatever code $LATEST holds now, as on AWS.
     */
    @Override
    public DurableInvocationResult invoke(ResolvedDurableTarget target, byte[] payload) {
        LambdaFunction fn = targetResolver.resolveInvokeTargetForAccount(target.accountId(), target.region(),
                target.functionName(), target.version());
        InvokeResult result = RequestScopes.callAs(target.accountId(),
                () -> executorService.invoke(fn, payload, InvocationType.RequestResponse));
        return new DurableInvocationResult(result.getRequestId(), result.getPayload(), result.getFunctionError());
    }
}
