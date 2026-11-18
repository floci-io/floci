package io.floci.aws.services.appsync.events;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Hands published events to the realtime subscriptions whose channel matches. Subscriptions live in
 * memory only, keyed by the owning account and API: they belong to open WebSocket connections and
 * end with them, as on AWS.
 */
@ApplicationScoped
public class EventsChannelBroker {

    private static final Logger LOG = Logger.getLogger(EventsChannelBroker.class);

    /**
     * One subscription of one connection. {@code sink} receives the subscription id and one event,
     * the JSON string as it was published.
     */
    public record Subscription(String connectionId, String id, EventsChannel channel, BiConsumer<String, String> sink) {
    }

    private final Map<String, List<Subscription>> subscriptions = new ConcurrentHashMap<>();

    public void subscribe(String accountId, String apiId, Subscription subscription) {
        subscriptions.computeIfAbsent(key(accountId, apiId), k -> new CopyOnWriteArrayList<>()).add(subscription);
    }

    /** Removes one subscription; false when the connection holds none with that id. */
    public boolean unsubscribe(String accountId, String apiId, String connectionId, String id) {
        List<Subscription> list = subscriptions.get(key(accountId, apiId));
        return list != null && list.removeIf(s -> s.connectionId().equals(connectionId) && s.id().equals(id));
    }

    /** Whether the connection already holds a subscription with this id. */
    public boolean hasSubscription(String accountId, String apiId, String connectionId, String id) {
        List<Subscription> list = subscriptions.get(key(accountId, apiId));
        return list != null && list.stream().anyMatch(s -> s.connectionId().equals(connectionId) && s.id().equals(id));
    }

    /** Drops every subscription of a closed connection. */
    public void removeConnection(String connectionId) {
        subscriptions.values().forEach(list -> list.removeIf(s -> s.connectionId().equals(connectionId)));
    }

    /** Delivers each event to every matching subscription, in order; returns how many deliveries were made. */
    public int publish(String accountId, String apiId, EventsChannel channel, List<String> events) {
        List<Subscription> list = subscriptions.get(key(accountId, apiId));
        if (list == null) {
            return 0;
        }
        int delivered = 0;
        for (Subscription subscription : list) {
            if (!subscription.channel().matches(channel)) {
                continue;
            }
            for (String event : events) {
                try {
                    subscription.sink().accept(subscription.id(), event);
                    delivered++;
                } catch (RuntimeException e) {
                    LOG.debugv(e, "Could not deliver an event on {0} to subscription {1}",
                            channel.path(), subscription.id());
                }
            }
        }
        return delivered;
    }

    int subscriptionCount() {
        return subscriptions.values().stream().mapToInt(List::size).sum();
    }

    private static String key(String accountId, String apiId) {
        return accountId + "\0" + apiId;
    }
}
