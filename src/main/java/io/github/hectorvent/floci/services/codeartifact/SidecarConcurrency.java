package io.github.hectorvent.floci.services.codeartifact;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Bounds the fan-out {@code listPackageVersions} needs when a sidecar has no single call that
 * returns every version's publish time at once: pypiserver answers with no timestamp field at
 * all (one {@code HEAD} per version), and Reposilite's per-version details page is the only way
 * to read a version's own file timestamps (one {@code GET} per version). Running those
 * sequentially means a one-result page still waits on every version in the package; running them
 * all at once risks opening as many sockets against the sidecar container as the package has
 * versions. Package-private: both callers live in this package, and the ordering/exception
 * guarantees below are shaped around what {@code listPackageVersions} specifically needs, not a
 * general-purpose utility.
 */
final class SidecarConcurrency {

    /**
     * How many per-item requests run against one sidecar container at once, no matter how many
     * {@code listPackageVersions} calls against that same container are in flight at the same
     * time: large enough that a package with a realistic number of versions sees a real speedup,
     * small enough that the container backing a sidecar can't be flooded with hundreds of
     * simultaneous connections.
     */
    private static final int MAX_CONCURRENT_REQUESTS = 8;

    /**
     * One entry per sidecar container, shared across every {@code mapBounded} call for that
     * container so {@link #MAX_CONCURRENT_REQUESTS} holds in aggregate: a per-call-only limit
     * would let two simultaneous {@code listPackageVersions} calls against the same container each
     * open that many connections, doubling the real cap. The key is whatever identity a caller
     * already has for "which container": pypiserver's own {@code repositoryContainerId} (one
     * container per CodeArtifact repository), or a single shared constant for Reposilite (one
     * container for every Maven repository). Callers whose key dies with the container (pypiserver's
     * {@code repositoryContainerId}, not Reposilite's shared constant) must bracket every call with
     * {@link #beginUse} and {@link #endUse}, and call {@link #release} once that container is torn
     * down, or this map grows by one entry for every repository ever deleted, for as long as the
     * process runs.
     */
    private static final Map<Object, ContainerUse> SIDECAR_PERMITS = new ConcurrentHashMap<>();

    /**
     * One sidecar container's {@link Semaphore} plus the bookkeeping {@link #beginUse}/{@link
     * #endUse}/{@link #release} need to agree on when it is actually safe to drop this entry:
     * {@code activeCalls} is the number of calls currently between {@link #beginUse} and {@link
     * #endUse} for this key, and {@code releasing} records that {@link #release} ran while at least
     * one of them was still in flight. Every field is only ever touched from inside a {@link
     * Map#compute}/{@link Map#computeIfAbsent}/{@link Map#computeIfPresent} remapping function for
     * this exact key, which {@link ConcurrentHashMap} runs under a lock scoped to that key, so plain
     * (non-atomic) fields are safe here.
     */
    private static final class ContainerUse {
        private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_REQUESTS);
        private int activeCalls;
        private boolean releasing;
    }

    /**
     * Virtual threads, not a platform-thread pool: {@code mapBounded} submits every item up front and
     * lets the semaphore above do the actual gating, so a package with thousands of versions would
     * otherwise start as many platform threads per call, each parked in {@link Semaphore#acquire()}.
     * Same approach {@link io.github.hectorvent.floci.services.codepipeline.CodePipelineService} already
     * uses for its own I/O-bound fan-out.
     */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private SidecarConcurrency() {
    }

    /**
     * Applies {@code perItem} to every element of {@code items}, in parallel up to {@link
     * #MAX_CONCURRENT_REQUESTS} at a time against {@code sidecarKey}'s container, and returns the
     * results in the same order as {@code items}. A single item's failure fails the whole call,
     * exactly as the original sequential loops this replaces did: the first failure's own
     * exception propagates (unwrapped from the {@link ExecutionException} every {@link Future}
     * wraps it in), the same error the caller would have seen from a plain sequential loop that
     * stopped on that item.
     *
     * <p>Every other item is then cancelled too: one still queued never starts at all, and one
     * already running is interrupted. Without this, a failure (or the caller thread being
     * interrupted) would leave the rest of the package's requests running against the sidecar in
     * the background even after the call itself has already failed.
     *
     * <p>Zero or one item skips the executor and the permit entirely and calls {@code perItem}
     * directly on the calling thread, since there is nothing to parallelize and nothing worth
     * bounding for it.
     */
    static <T, R> List<R> mapBounded(Object sidecarKey, List<T> items, Function<T, R> perItem) {
        if (items.size() <= 1) {
            List<R> result = new ArrayList<>(items.size());
            for (T item : items) {
                result.add(perItem.apply(item));
            }
            return result;
        }
        Semaphore permits = SIDECAR_PERMITS.computeIfAbsent(sidecarKey, key -> new ContainerUse()).semaphore;
        List<Future<R>> futures = new ArrayList<>(items.size());
        for (T item : items) {
            futures.add(EXECUTOR.submit(() -> runWithPermit(permits, perItem, item)));
        }
        boolean succeeded = false;
        try {
            List<R> results = new ArrayList<>(items.size());
            for (Future<R> future : futures) {
                results.add(await(future));
            }
            succeeded = true;
            return results;
        } finally {
            if (!succeeded) {
                for (Future<R> future : futures) {
                    future.cancel(true);
                }
            }
        }
    }

    /**
     * Marks the start of one call against {@code sidecarKey}'s container, before that call does
     * anything else against it (a {@code listPackageVersions} HTTP fetch happens before the
     * {@link #mapBounded} that actually consumes the permit). Pairs with {@link #endUse} in a
     * {@code try}/{@code finally} around the whole call: without this, a {@link #release} racing a
     * call that has only fetched its version listing so far, and has not reached {@link #mapBounded}
     * yet, would remove the entry before that call ever registers using it, and {@link #mapBounded}
     * would then cache a brand new one for a container that is already gone, orphaned forever since
     * nothing will call {@link #release} for that key a second time.
     */
    static void beginUse(Object sidecarKey) {
        SIDECAR_PERMITS.compute(sidecarKey, (key, existing) -> {
            ContainerUse use = existing != null ? existing : new ContainerUse();
            use.activeCalls++;
            return use;
        });
    }

    /** Marks the end of one call {@link #beginUse} started, dropping the entry if {@link #release}
     * ran while this was the last call still using it. */
    static void endUse(Object sidecarKey) {
        SIDECAR_PERMITS.computeIfPresent(sidecarKey, (key, use) -> {
            use.activeCalls--;
            return use.activeCalls == 0 && use.releasing ? null : use;
        });
    }

    /**
     * Drops the cached permit for {@code sidecarKey} once its container has actually been torn
     * down, unless a call already between {@link #beginUse} and {@link #endUse} is still using it,
     * in which case the last matching {@link #endUse} drops it instead. A no-op (leaves nothing
     * behind) if the key was never used. Only meaningful for a key tied to one container's lifetime
     * (pypiserver's {@code repositoryContainerId}); never call this for Reposilite's shared constant,
     * since its one container keeps serving every other Maven repository.
     */
    static void release(Object sidecarKey) {
        SIDECAR_PERMITS.compute(sidecarKey, (key, existing) -> {
            ContainerUse use = existing != null ? existing : new ContainerUse();
            use.releasing = true;
            return use.activeCalls == 0 ? null : use;
        });
    }

    private static <T, R> R runWithPermit(Semaphore permits, Function<T, R> perItem, T item)
            throws InterruptedException {
        permits.acquire();
        try {
            return perItem.apply(item);
        } finally {
            permits.release();
        }
    }

    private static <R> R await(Future<R> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting on a sidecar request", e);
        }
    }
}
