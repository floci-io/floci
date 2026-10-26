package io.github.hectorvent.floci.services.sqs;

import java.time.Instant;

/**
 * What a queue's API calls did since the last AWS/SQS metrics tick, and when the queue was last
 * acted on, which decides whether CloudWatch still considers it active.
 */
final class SqsQueueActivity {

    /** The samples of one metric within one tick. */
    record Samples(long count, double sum, double minimum, double maximum) {

        static final Samples NONE = new Samples(0, 0, 0, 0);

        Samples add(double value) {
            if (count == 0) {
                return new Samples(1, value, value, value);
            }
            return new Samples(count + 1, sum + value, Math.min(minimum, value), Math.max(maximum, value));
        }
    }

    record Snapshot(Samples sent, Samples sentSize, Samples deduplicated, Samples received,
                    Samples emptyReceives, Samples deleted, Instant lastActivity) {
    }

    private Samples sent = Samples.NONE;
    private Samples sentSize = Samples.NONE;
    private Samples deduplicated = Samples.NONE;
    private Samples received = Samples.NONE;
    private Samples emptyReceives = Samples.NONE;
    private Samples deleted = Samples.NONE;
    private Instant lastActivity;

    synchronized void accessed() {
        lastActivity = Instant.now();
    }

    /** A FIFO send that was deduplicated still counts as sent, as on AWS. */
    synchronized void sent(int size, boolean wasDeduplicated) {
        sent = sent.add(1);
        sentSize = sentSize.add(size);
        deduplicated = deduplicated.add(wasDeduplicated ? 1 : 0);
        lastActivity = Instant.now();
    }

    synchronized void received(int messages) {
        if (messages == 0) {
            emptyReceives = emptyReceives.add(1);
        } else {
            received = received.add(messages);
        }
        lastActivity = Instant.now();
    }

    synchronized void deleted() {
        deleted = deleted.add(1);
        lastActivity = Instant.now();
    }

    /** Hands over the samples gathered since the previous call and starts the next tick empty. */
    synchronized Snapshot drain() {
        Snapshot snapshot = new Snapshot(sent, sentSize, deduplicated, received, emptyReceives, deleted, lastActivity);
        sent = Samples.NONE;
        sentSize = Samples.NONE;
        deduplicated = Samples.NONE;
        received = Samples.NONE;
        emptyReceives = Samples.NONE;
        deleted = Samples.NONE;
        return snapshot;
    }
}
