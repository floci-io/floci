package io.floci.aws.core.common;

import java.util.Optional;

/**
 * A service whose sessions take Spark Connect calls through {@link SparkConnectProxy}: it tells where a call carrying
 * an authentication token goes. Called off the event loop, as it may read the service's storage.
 */
public interface SparkConnectTargets {

    /**
     * Where a call carrying {@code token} goes, or empty when {@code token} is not one this service issued. A call
     * routed to a server counts as running in its session from now until {@link Route#ended} runs.
     */
    Optional<Route> route(String token);

    /**
     * Where a call goes: the server at {@code host} and {@code port}, with {@code ended} to run once the call is over;
     * or, when {@code refusal} is not null, nowhere, the call ending at once with the gRPC status {@code status} and
     * {@code refusal} as its message.
     */
    record Route(String host, int port, Runnable ended, int status, String refusal) {

        /** gRPC's status for a call no server is there to take. */
        public static final int UNAVAILABLE = 14;

        /** gRPC's status for a call whose credentials are missing, unknown or expired. */
        public static final int UNAUTHENTICATED = 16;

        /** A call that goes to the server at {@code host} and {@code port}. */
        public static Route to(String host, int port, Runnable ended) {
            return new Route(host, port, ended, 0, null);
        }

        /** A call that ends at once with {@code status} and {@code refusal}. */
        public static Route refused(int status, String refusal) {
            return new Route(null, 0, () -> { }, status, refusal);
        }
    }
}
