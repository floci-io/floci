package io.github.hectorvent.floci.services.rds.container;

import java.time.Instant;

/**
 * What the auto-pause of an RDS container asks of the cluster it belongs to, and what it reports
 * back. Both calls run on a background thread, never while the auto-pause holds its lock, so an
 * implementation may take the RDS service's lock.
 */
public interface AutoPauseListener {

    AutoPauseListener NONE = new AutoPauseListener() {
        @Override
        public boolean mayPause() {
            return false;
        }

        @Override
        public void onAutoPause(Event event, Instant at) {
        }
    };

    /**
     * The auto-pause steps Aurora reports as RDS-EVENT-0370 to 0374, plus {@code STILL_PAUSED},
     * repeated once a minute while the container stays paused.
     */
    enum Event {
        PAUSE_INITIATED,
        PAUSE_CANCELED,
        PAUSED,
        STILL_PAUSED,
        RESUME_INITIATED,
        RESUMED
    }

    /** Whether the cluster may pause now that it has been idle for SecondsUntilAutoPause. */
    boolean mayPause();

    void onAutoPause(Event event, Instant at);
}
