package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Shrinks the shared request worker pool to four threads, and changes nothing else, so a test can
 * occupy every worker with four held requests and check what else is still served. Quarkus builds
 * the application once per distinct profile class, so the classes that need only this override
 * share this one rather than each declaring an identical nested profile.
 */
public class FourWorkerThreadsProfile implements QuarkusTestProfile {

    /** The worker pool size this profile configures. */
    public static final int WORKER_THREADS = 4;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.thread-pool.core-threads", String.valueOf(WORKER_THREADS),
                "quarkus.thread-pool.max-threads", String.valueOf(WORKER_THREADS));
    }
}
