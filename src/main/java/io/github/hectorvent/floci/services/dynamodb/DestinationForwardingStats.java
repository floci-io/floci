package io.github.hectorvent.floci.services.dynamodb;

/**
 * An immutable snapshot of a single Kinesis CDC destination's forwarding health, produced by
 * {@link KinesisStreamingForwarder#forwardingStats()}. Copied under the destination's monitor so
 * the counters and queue depth are internally consistent.
 *
 * @param health one of HEALTHY, RETRYING, GAVE_UP
 */
record DestinationForwardingStats(
        String health,
        long forwarded,
        long retried,
        long dropped,
        long overflowed,
        long discardedOnDisable,
        int queueDepth,
        String lastErrorType) {
}
