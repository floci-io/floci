package io.github.hectorvent.floci.core.common;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.ManagedContext;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Runs work that happens outside an HTTP request, such as a background worker, as a given account. */
public final class RequestScopes {

    private RequestScopes() {}

    /**
     * Account-aware storage and S3 read the account from the request context, so a worker on
     * a fresh thread would otherwise fall back to the default account.
     */
    public static void runAs(String accountId, Runnable body) {
        callAs(accountId, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T callAs(String accountId, Supplier<T> body) {
        try {
            return callAsChecked(accountId, body::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // A Supplier cannot throw a checked exception, so this is unreachable in practice.
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@link #callAs} for a body that throws checked exceptions, which propagate unchanged. Runs
     * the body directly when {@code accountId} is null or Arc is not running; restores the previous
     * account when the request scope was already active, and terminates the scope it activated.
     */
    public static <T> T callAsChecked(String accountId, Callable<T> body) throws Exception {
        ArcContainer container = Arc.container();
        if (accountId == null || container == null || !container.isRunning()) {
            return body.call();
        }
        ManagedContext requestContext = container.requestContext();
        boolean alreadyActive = requestContext.isActive();
        if (!alreadyActive) {
            requestContext.activate();
        }
        RequestContext ctx = container.instance(RequestContext.class).get();
        String previousAccountId = alreadyActive ? ctx.getAccountId() : null;
        try {
            ctx.setAccountId(accountId);
            return body.call();
        } finally {
            if (!alreadyActive) {
                requestContext.terminate();
            } else {
                ctx.setAccountId(previousAccountId);
            }
        }
    }
}
