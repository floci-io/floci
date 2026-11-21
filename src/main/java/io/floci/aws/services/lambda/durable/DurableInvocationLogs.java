package io.floci.aws.services.lambda.durable;

import io.floci.aws.services.lambda.launcher.InvocationLogTail;

import java.util.concurrent.CompletableFuture;

/** Transient log capture shared by synchronous Tail callers attached to one durable execution. */
final class DurableInvocationLogs {

    private final InvocationLogTail tail = new InvocationLogTail();
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
    private int callers;

    synchronized void acquire() {
        callers++;
    }

    synchronized void release() {
        callers--;
    }

    synchronized boolean unused() {
        return callers == 0 && pending.isDone();
    }

    synchronized void beginInvocation() {
        pending = new CompletableFuture<>();
    }

    void complete(String encodedLogs) {
        CompletableFuture<Void> finished;
        synchronized (this) {
            tail.appendEncoded(encodedLogs);
            finished = pending;
        }
        finished.complete(null);
    }

    synchronized CompletableFuture<String> snapshot() {
        return pending.thenApply(ignored -> encoded());
    }

    private synchronized String encoded() {
        return tail.encoded();
    }

    synchronized void cancel() {
        pending.cancel(false);
    }
}
