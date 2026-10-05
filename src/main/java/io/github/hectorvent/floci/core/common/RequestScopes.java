package io.github.hectorvent.floci.core.common;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.ManagedContext;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Runs work that happens outside an HTTP request, such as a background worker, as a given account
 * and, optionally, in a given region.
 */
public final class RequestScopes {

    private RequestScopes() {}

    /**
     * Account-aware storage and S3 read the account from the request context, so a worker on
     * a fresh thread would otherwise fall back to the default account.
     */
    public static void runAs(String accountId, Runnable body) {
        runAs(accountId, null, body);
    }

    /**
     * {@link #runAs(String, Runnable)} that also sets the region, and the region's partition, so
     * {@link RegionResolver#getRegion()}, {@link RegionResolver#getPartition()} and region-keyed
     * lookups inside {@code body} see the resource's region instead of the default one.
     */
    public static void runAs(String accountId, String region, Runnable body) {
        callAs(accountId, region, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T callAs(String accountId, Supplier<T> body) {
        return callAs(accountId, null, body);
    }

    public static <T> T callAs(String accountId, String region, Supplier<T> body) {
        try {
            return callAsChecked(accountId, region, body::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // A Supplier cannot throw a checked exception, so this is unreachable in practice.
            throw new IllegalStateException(e);
        }
    }

    public static <T> T callAsChecked(String accountId, Callable<T> body) throws Exception {
        return callAsChecked(accountId, null, body);
    }

    /**
     * {@link #callAs} for a body that throws checked exceptions, which propagate unchanged. Sets the
     * account when {@code accountId} is non-null and the region and its partition when
     * {@code region} is non-null; a region outside every published partition leaves the partition
     * unset, so {@link RegionResolver#getPartition()} falls back to the deployment's, as it does for
     * a request. Runs the body directly when both are null or Arc is not running; restores the
     * previous values when the request scope was already active, and terminates the scope it
     * activated.
     */
    public static <T> T callAsChecked(String accountId, String region, Callable<T> body) throws Exception {
        ArcContainer container = Arc.container();
        if ((accountId == null && region == null) || container == null || !container.isRunning()) {
            return body.call();
        }
        ManagedContext requestContext = container.requestContext();
        boolean alreadyActive = requestContext.isActive();
        if (!alreadyActive) {
            requestContext.activate();
        }
        RequestContext ctx = container.instance(RequestContext.class).get();
        String previousAccountId = alreadyActive ? ctx.getAccountId() : null;
        String previousRegion = alreadyActive ? ctx.getRegion() : null;
        String previousPartition = alreadyActive ? ctx.getPartition() : null;
        try {
            if (accountId != null) {
                ctx.setAccountId(accountId);
            }
            if (region != null) {
                ctx.setRegion(region);
                ctx.setPartition(AwsPartitions.forRegion(region).map(AwsPartition::id).orElse(null));
            }
            return body.call();
        } finally {
            if (!alreadyActive) {
                requestContext.terminate();
            } else {
                ctx.setAccountId(previousAccountId);
                ctx.setRegion(previousRegion);
                ctx.setPartition(previousPartition);
            }
        }
    }
}
