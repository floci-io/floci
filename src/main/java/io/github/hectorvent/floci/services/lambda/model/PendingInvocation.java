package io.github.hectorvent.floci.services.lambda.model;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PendingInvocation {

    private final String requestId;
    private final byte[] payload;
    private final long timeoutMs;
    private volatile long deadlineMs;
    private final String functionArn;
    private final CompletableFuture<InvokeResult> resultFuture;
    private final CompletableFuture<Void> dispatchedFuture = new CompletableFuture<>();

    public PendingInvocation(String requestId, byte[] payload, long deadlineMs,
                              String functionArn, CompletableFuture<InvokeResult> resultFuture) {
        this.requestId = requestId;
        this.payload = payload;
        this.deadlineMs = deadlineMs;
        this.timeoutMs = Math.max(0, deadlineMs - System.currentTimeMillis());
        this.functionArn = functionArn;
        this.resultFuture = resultFuture;
    }

    public void prepareForDispatch() {
        deadlineMs = System.currentTimeMillis() + timeoutMs;
    }

    public void markDispatched() {
        resultFuture.orTimeout(Math.max(0, deadlineMs - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        dispatchedFuture.complete(null);
    }

    public String getRequestId() { return requestId; }
    public byte[] getPayload() { return payload; }
    public long getDeadlineMs() { return deadlineMs; }
    public String getFunctionArn() { return functionArn; }
    public CompletableFuture<InvokeResult> getResultFuture() { return resultFuture; }
    public CompletableFuture<Void> getDispatchedFuture() { return dispatchedFuture; }
}
