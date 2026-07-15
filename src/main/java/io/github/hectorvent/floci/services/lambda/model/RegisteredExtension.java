package io.github.hectorvent.floci.services.lambda.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks one extension's Extensions API registration state within a single container's
 * {@code RuntimeApiServer} — its subscribed event types and the pending-event list its
 * {@code /extension/event/next} polling loop waits on.
 *
 * <p>Delivery uses the same synchronized-wait/notify long-poll shape as
 * {@code SqsService.receiveMessage}: producers ({@code notifyExtensionsOfInvoke}, {@code stop()})
 * always just add the event and notify under {@link #lock}, and the poller always waits on
 * {@link #lock} and rechecks its exit conditions after waking, rather than racing a queue poll
 * against a separately-tracked parked-request reference. That makes "an event was queued" and
 * "the poller observed it" atomic with respect to each other by construction, instead of relying
 * on callers to re-check for missed events in the gap between two independent operations.
 */
public class RegisteredExtension {

    private final String identifier;
    private final String name;
    private final List<String> subscribedEvents;
    private final Object lock = new Object();
    private final List<ExtensionEvent> pendingEvents = new ArrayList<>();

    public RegisteredExtension(String identifier, String name, List<String> subscribedEvents) {
        this.identifier = identifier;
        this.name = name;
        this.subscribedEvents = subscribedEvents;
    }

    public String getIdentifier() { return identifier; }
    public String getName() { return name; }
    public List<String> getSubscribedEvents() { return subscribedEvents; }

    public boolean isSubscribedTo(ExtensionEvent.Type type) {
        return subscribedEvents.contains(type.name());
    }

    /** The lock guarding {@link #pendingEvents}; producers notify it after adding an event. */
    public Object getLock() { return lock; }

    /** Queues an event for delivery and wakes any thread waiting on {@link #lock}. Must be
     *  called holding {@link #lock}. */
    public void offer(ExtensionEvent event) {
        pendingEvents.add(event);
        lock.notifyAll();
    }

    /** Removes and returns the next pending event, or {@code null} if none is queued. Must be
     *  called holding {@link #lock}. */
    public ExtensionEvent poll() {
        return pendingEvents.isEmpty() ? null : pendingEvents.remove(0);
    }
}
