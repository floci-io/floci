package io.github.hectorvent.floci.services.lambda.durable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stands in for the Lambda container in protocol tests: functions resolve for real, but each
 * invocation is handed to the test, which plays the SDK over the REST API and then answers.
 * Enabled per test class through {@link LambdaDurableScriptedProfile}.
 */
@Alternative
@ApplicationScoped
public class ScriptedDurableFunctionInvoker implements DurableFunctionInvoker {

    public record Invocation(ResolvedDurableTarget target, byte[] payload,
                             CompletableFuture<DurableInvocationResult> response) {
    }

    private final LambdaDurableFunctionInvoker real;
    private final BlockingQueue<Invocation> invocations = new LinkedBlockingQueue<>();

    @Inject
    public ScriptedDurableFunctionInvoker(LambdaDurableFunctionInvoker real) {
        this.real = real;
    }

    @Override
    public ResolvedDurableTarget resolve(String accountId, String region, String functionName, String qualifier) {
        return real.resolve(accountId, region, functionName, qualifier);
    }

    @Override
    public DurableInvocationResult invoke(ResolvedDurableTarget target, byte[] payload, String requestId) {
        Invocation invocation = new Invocation(target, payload, new CompletableFuture<>());
        invocations.add(invocation);
        try {
            return invocation.response().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the scripted response", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("no scripted response for the invocation", e);
        }
    }

    public Invocation awaitInvocation(Duration timeout) throws InterruptedException {
        Invocation invocation = invocations.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (invocation == null) {
            throw new AssertionError("no durable invocation within " + timeout);
        }
        return invocation;
    }

    public void drain() {
        invocations.clear();
    }
}
