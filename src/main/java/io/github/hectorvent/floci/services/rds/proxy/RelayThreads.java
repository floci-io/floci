package io.github.hectorvent.floci.services.rds.proxy;

/**
 * Starts the threads that copy a database session's bytes between client and backend.
 *
 * <p>They are platform threads on purpose. A relay thread blocks on a socket read between every
 * request and every response, so the latency of waking it up is paid twice per round trip. A
 * virtual thread parked on a socket is woken by the poller and then has to be scheduled on a
 * carrier thread; a platform thread is woken by the kernel directly. Measured with an otherwise
 * identical 8 KiB copy loop between pgbench and PostgreSQL in one Docker network: 0.198 ms per
 * {@code SELECT 1} on virtual threads, 0.113 ms on platform threads, 0.063 ms without a relay.
 * The accept loop and the per-connection handshake stay on virtual threads: they run once per
 * connection, not once per message.
 */
final class RelayThreads {

    private RelayThreads() {
    }

    static Thread start(String name, Runnable relay) {
        return Thread.ofPlatform().daemon().name(name).start(relay);
    }
}
