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
    private String activeStateType;
    private boolean iterationStarted;
    private boolean iterationEnded;
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
        long last = lastEventId;
        for (HistoryChain chain : chains) {
            last = Math.max(last, chain.lastEventId);
        }
        continueFrom(last);
    }

    /**
     * Nothing more is recorded on a branch once it is cut. The state it was cut in is recorded by
     * the Parallel that cut it, through {@link #publishBeside}.
     */
    void abandon() {
        abandoned = true;
    }

    /*
     * The state this chain is in is published together with the event that enters or exits it,
     * and read together with the cut, all under this chain's lock. A cut therefore reports a state
     * only when its Entered event is recorded and its Exited event never will be.
     */

    /** Publishes a state's Entered event and marks the chain as inside that state. */
    synchronized long publishStateEntered(String stateType, String eventType, Map<String, Object> details) {
        long id = publish(eventType, details);
        activeStateType = stateType;
        return id;
    }

    /** Marks the chain as between states and publishes the state's Exited event. */
    synchronized void publishStateExited(String eventType, Map<String, Object> details) {
        activeStateType = null;
        publish(eventType, details);
    }

    /** Marks the chain as between states when a state ends without an Exited event. */
    synchronized void leaveState() {
        activeStateType = null;
    }

    /**
     * Marks the chain as between states and publishes an aside event, as a Task whose own failure
     * ends its branch records {@code TaskStateAborted}. A cut that lands first reports the state
     * instead, and this event is then not recorded, so the state is reported aborted exactly once.
     */
    synchronized void leaveStateAside(String eventType, Map<String, Object> details) {
        activeStateType = null;
        publishAside(eventType, details);
    }

    /**
     * Cuts the chain like {@link #abandon} and returns the type of the state it was in, or null
     * when it was between states.
     */
    synchronized String abandonInState() {
        abandoned = true;
        return activeStateType;
    }

    /**
     * Publishes the event that starts a Map iteration and marks it started, under the same lock a
     * cut takes, so an iteration is reported cut only when its start is recorded.
     */
    synchronized void publishIterationStart(String type, Map<String, Object> details) {
        if (publish(type, details) > 0) {
            iterationStarted = true;
        }
    }

    /**
     * Publishes the event that ends a Map iteration and marks the iteration ended, under the same
     * lock a cut takes, so an iteration is reported either ended or cut, never both.
     */
    synchronized void publishIterationEnd(String type, Map<String, Object> details) {
        iterationEnded = true;
        publish(type, details);
    }

    /** {@link #publishIterationEnd} for an event recorded aside, as a tolerated MapIterationFailed is. */
    synchronized void publishIterationEndAside(String type, Map<String, Object> details) {
        iterationEnded = true;
        publishAside(type, details);
    }

    /**
     * What a cut found an iteration doing: not yet started, already ended, or else inside
     * {@code stateType}, or between states when that is null.
     */
    record IterationCut(boolean started, boolean ended, String stateType) {
        /** True for an iteration whose start is recorded and whose end never will be. */
        boolean recorded() {
            return started && !ended;
        }
    }

    /**
     * Cuts the chain like {@link #abandonInState} and also reports whether the iteration had
     * recorded its start and its end. An iteration cut first records neither afterwards; one that
     * ended first is not reported as cut, and one that had not started is not reported at all.
     */
    synchronized IterationCut abandonIteration() {
        abandoned = true;
        return new IterationCut(iterationStarted, iterationEnded, activeStateType);
    }

    private boolean isAbandoned() {
        for (HistoryChain chain = this; chain != null; chain = chain.parent) {
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

    /**
     * Chained to the last event rather than to the tail, and the last event stays where it is, so
     * several events published this way all point at the same one. AWS records the
     * {@code *StateAborted} event of each branch a failure cuts this way, before the Parallel's
     * {@code ParallelStateFailed}, which points at that same event.
     */
    void publishBeside(String type, Map<String, Object> details) {
        if (isAbandoned()) {
            return;
        }
        long id = append(type, lastEventId, details, true);
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
            HistoryEvent event = new HistoryEvent();
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
