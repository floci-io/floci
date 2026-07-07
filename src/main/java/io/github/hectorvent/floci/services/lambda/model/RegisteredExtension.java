package io.github.hectorvent.floci.services.lambda.model;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import io.vertx.ext.web.RoutingContext;

/**
 * Tracks one extension's Extensions API registration state within a single container's
 * {@code RuntimeApiServer} — its subscribed event types and the queue/parked-poller pair
 * used to deliver {@link ExtensionEvent}s to its {@code /extension/event/next} polling loop,
 * mirroring the runtime invocation queue/park pattern already used for
 * {@code /runtime/invocation/next}.
 */
public class RegisteredExtension {

    private final String identifier;
    private final String name;
    private final List<String> subscribedEvents;
    private final ConcurrentLinkedQueue<ExtensionEvent> pendingEvents = new ConcurrentLinkedQueue<>();
    private volatile RoutingContext waitingContext;

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

    public ConcurrentLinkedQueue<ExtensionEvent> getPendingEvents() { return pendingEvents; }

    public synchronized RoutingContext takeWaitingContext() {
        RoutingContext ctx = waitingContext;
        waitingContext = null;
        return ctx;
    }

    public synchronized void setWaitingContext(RoutingContext ctx) {
        waitingContext = ctx;
    }
}
