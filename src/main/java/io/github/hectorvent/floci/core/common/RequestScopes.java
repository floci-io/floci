package io.github.hectorvent.floci.core.common;

import io.quarkus.arc.Arc;

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
        var container = Arc.container();
        if (accountId == null || container == null || !container.isRunning()) {
            return body.get();
        }
        var requestContext = container.requestContext();
        var alreadyActive = requestContext.isActive();
        if (!alreadyActive) {
            requestContext.activate();
        }
        var ctx = container.instance(RequestContext.class).get();
        var previousAccountId = alreadyActive ? ctx.getAccountId() : null;
        try {
            ctx.setAccountId(accountId);
            return body.get();
        } finally {
            if (!alreadyActive) {
                requestContext.terminate();
            } else {
                ctx.setAccountId(previousAccountId);
            }
        }
    }
}
