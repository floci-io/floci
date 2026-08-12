package io.github.hectorvent.floci.services.lambda.model;

import java.util.ArrayDeque;
import java.util.List;
import io.vertx.ext.web.RoutingContext;

/**
 * Tracks one extension's Extensions API registration state within a single container's
 * {@code RuntimeApiServer} — its subscribed event types and the queue/parked-poller pair
 * used to deliver {@link ExtensionEvent}s to its {@code /extension/event/next} polling loop.
 *
 * Not thread-safe on its own — all access to {@code pendingEvents}/{@code waitingContext}
 * must happen while holding the owning {@code RuntimeApiServer}'s lock. This class
 * intentionally has no internal synchronization; see {@code RuntimeApiServer}'s class doc
 * for the locking discipline.
 */
public class RegisteredExtension {

    private final String identifier;
    private final String name;
    private final List<String> subscribedEvents;
    private final ArrayDeque<ExtensionEvent> pendingEvents = new ArrayDeque<>();
    private RoutingContext waitingContext;
    private boolean firstNextReceived;

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

    public ArrayDeque<ExtensionEvent> getPendingEvents() { return pendingEvents; }

    public RoutingContext takeWaitingContext() {
        RoutingContext ctx = waitingContext;
        waitingContext = null;
        return ctx;
    }

    public void setWaitingContext(RoutingContext ctx) {
        waitingContext = ctx;
    }

    /**
     * Whether a poll is currently parked, without consuming it — unlike
     * {@link #takeWaitingContext()}, which clears the context as it hands it over. Called under the
     * owning server's lock, like the rest of this class.
     */
    public boolean hasWaitingContext() {
        return waitingContext != null;
    }

    /**
     * Records that this extension has issued its first {@code /extension/event/next}, which is
     * what AWS treats as the extension being init-ready (registering alone only obtains an
     * identifier). Called under the owning server's lock, like the rest of this class.
     *
     * @return true only on the first call, so the caller counts the readiness barrier down once
     *         per extension no matter how many times it polls.
     */
    public boolean markFirstNextReceived() {
        if (firstNextReceived) {
            return false;
        }
        firstNextReceived = true;
        return true;
    }
}
