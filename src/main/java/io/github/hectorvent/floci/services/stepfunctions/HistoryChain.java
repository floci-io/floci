package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;

import java.util.AbstractList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One {@code previousEventId} chain of an execution's history: the top-level state loop, a Parallel
 * branch, or a Map iteration. All chains share the execution's history and event limit.
 */
final class HistoryChain {

    private final List<HistoryEvent> history;
    private final AtomicLong producedEventCount;
    private final AtomicBoolean ended;
    private final HistoryChain parent;
    private volatile boolean abandoned;
    private long lastEventId;
    private long tailEventId;

    private HistoryChain(List<HistoryEvent> history, AtomicLong producedEventCount, AtomicBoolean ended,
                         HistoryChain parent, long lastEventId) {
        this.history = history;
        this.producedEventCount = producedEventCount;
        this.ended = ended;
        this.parent = parent;
        this.lastEventId = lastEventId;
        this.tailEventId = lastEventId;
    }

    /** Starts at 0, not at ExecutionStarted. AWS leaves the first state's Entered event unchained. */
    static HistoryChain of(List<HistoryEvent> history) {
        return new HistoryChain(history, new AtomicLong(history.size()), new AtomicBoolean(), null, 0L);
    }

    /** A Distributed Map item is a child execution. Its events are counted, not kept. */
    static HistoryChain ofChildExecution() {
        return new HistoryChain(new CountOnlyHistory(), new AtomicLong(), new AtomicBoolean(), null, 0L);
    }

    HistoryChain fork() {
        return new HistoryChain(history, producedEventCount, ended, this, lastEventId);
    }

    boolean isBranch() {
        return parent != null;
    }

    long lastEventId() {
        return lastEventId;
    }

    void continueFrom(long eventId) {
        lastEventId = eventId;
        tailEventId = eventId;
    }

    void continueAfter(Collection<HistoryChain> chains) {
        var last = lastEventId;
        for (HistoryChain chain : chains) {
            last = Math.max(last, chain.lastEventId);
        }
        continueFrom(last);
    }

    /** AWS records nothing of a branch it cut. */
    void abandon() {
        abandoned = true;
    }

    private boolean isAbandoned() {
        for (var chain = this; chain != null; chain = chain.parent) {
            if (chain.abandoned) {
                return true;
            }
        }
        return false;
    }

    /** Returns the event id, or 0 once the execution has ended. */
    long publish(String type, Map<String, Object> details) {
        if (isAbandoned()) {
            return 0L;
        }
        long id = append(type, lastEventId, details, true);
        if (id > 0) {
            lastEventId = id;
            tailEventId = id;
        }
        return id;
    }

    /**
     * The event does not become the chain's tail. AWS records {@code *StateSucceeded},
     * {@code MapRunSucceeded}, {@code MapIterationFailed} and {@code TaskStateAborted} this way.
     */
    void publishAside(String type, Map<String, Object> details) {
        if (isAbandoned()) {
            return;
        }
        long id = append(type, tailEventId, details, true);
        if (id > 0) {
            tailEventId = id;
        }
    }

    /** The terminal event does not count towards the limit, and nothing is recorded after it. */
    void end(String type, Map<String, Object> details) {
        end(type, tailEventId, details);
    }

    /** ExecutionTimedOut points at 0. */
    void end(String type, long previousEventId, Map<String, Object> details) {
        synchronized (history) {
            append(type, previousEventId, details, false);
            ended.set(true);
        }
    }

    /**
     * Synchronized on the history because StopExecution appends the terminal event from another
     * thread. An event counts towards the limit only once the history is known to take it.
     */
    private long append(String type, long previousEventId, Map<String, Object> details, boolean counted) {
        synchronized (history) {
            if (ended.get()) {
                return 0L;
            }
            if (counted) {
                AslExecutor.countTowardsHistoryEventLimit(producedEventCount);
            }
            var event = new HistoryEvent();
            event.setId(history.size() + 1L);
            event.setPreviousEventId(previousEventId);
            event.setType(type);
            event.setDetails(details);
            if (!history.add(event)) {
                ended.set(true);
                return 0L;
            }
            return event.getId();
        }
    }

    private static final class CountOnlyHistory extends AbstractList<HistoryEvent> {
        private int size;

        @Override
        public boolean add(HistoryEvent event) {
            size++;
            return true;
        }

        @Override
        public HistoryEvent get(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public int size() {
            return size;
        }
    }
}
