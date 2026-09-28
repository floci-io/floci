package io.github.hectorvent.floci.core.common.docker;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * A single HTTP GET health probe, and a bounded poll built on it, shared by every sidecar manager
 * that waits for its own container to come up: {@link PerKeyContainerPool} and
 * {@code ReposiliteSidecarManager} both had their own copy of this before it moved here.
 */
public final class SidecarHealthHelper {

    private static final Logger LOG = Logger.getLogger(SidecarHealthHelper.class);
    private static final int HEALTH_POLL_MAX_MS = 30_000;
    private static final int HEALTH_POLL_INTERVAL_MS = 500;
    private static final int PROBE_TIMEOUT_MS = 500;

    private SidecarHealthHelper() {
    }

    /** {@code true} once a GET to {@code baseUrl + healthPath} returns HTTP 200. */
    public static boolean probeHealth(String baseUrl, String healthPath) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(baseUrl + healthPath).toURL()
                    .openConnection();
            connection.setConnectTimeout(PROBE_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_TIMEOUT_MS);
            return connection.getResponseCode() == 200;
        } catch (IOException e) {
            LOG.debugv(e, "Sidecar health probe failed for {0}", baseUrl);
            return false;
        }
    }

    /**
     * Polls {@link #probeHealth} every {@value #HEALTH_POLL_INTERVAL_MS} ms until it succeeds or
     * {@value #HEALTH_POLL_MAX_MS} ms have passed, in which case this throws.
     */
    public static void waitForHealth(String baseUrl, String healthPath) {
        long deadline = System.currentTimeMillis() + HEALTH_POLL_MAX_MS;
        while (System.currentTimeMillis() < deadline) {
            if (probeHealth(baseUrl, healthPath)) {
                return;
            }
            try {
                Thread.sleep(HEALTH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for sidecar container", e);
            }
        }
        throw new IllegalStateException("Sidecar container at " + baseUrl + " did not become healthy within "
                + HEALTH_POLL_MAX_MS + " ms");
    }
}
