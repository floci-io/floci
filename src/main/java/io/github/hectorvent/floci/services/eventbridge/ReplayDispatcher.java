package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.services.eventbridge.model.ArchivedEvent;
import io.github.hectorvent.floci.services.eventbridge.model.Replay;
import io.github.hectorvent.floci.services.eventbridge.model.ReplayState;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@ApplicationScoped
public class ReplayDispatcher {

    private static final Logger LOG = Logger.getLogger(ReplayDispatcher.class);

    private final Vertx vertx;
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    @Inject
    public ReplayDispatcher(Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * Starts an async replay. Events are dispatched via {@code eventSender} in event-time order.
     *
     * @param replay          the replay metadata (must be in STARTING state)
     * @param events          all archived events for the source archive
     * @param eventSender     sends a batch of events to the destination bus (calls putEvents)
     * @param stateUpdater    called to transition replay state (replayName, newState)
     * @param progressUpdater called after each dispatched event with the event timestamp
     */
    void dispatch(Replay replay,
                  List<ArchivedEvent> events,
                  Consumer<List<Map<String, Object>>> eventSender,
                  BiConsumer<String, ReplayState> stateUpdater,
                  Consumer<Instant> progressUpdater) {

        String replayName = replay.getReplayName();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelFlags.put(replayName, cancelled);

        vertx.executeBlocking(promise -> {
            try {
                stateUpdater.accept(replayName, ReplayState.RUNNING);

                Instant start = replay.getEventStartTime();
                Instant end = replay.getEventEndTime();
                String destArn = replay.getDestinationArn();
                String destBusName = busNameFromArn(destArn);

                List<ArchivedEvent> window = events.stream()
                        .filter(e -> !e.getEventTime().isBefore(start) && !e.getEventTime().isAfter(end))
                        .sorted(Comparator.comparing(ArchivedEvent::getEventTime))
                        .toList();

                LOG.debugv("Replay {0}: dispatching {1} events to bus {2}", replayName, window.size(), destBusName);

                for (ArchivedEvent event : window) {
                    if (cancelled.get()) {
                        stateUpdater.accept(replayName, ReplayState.CANCELLED);
                        promise.complete();
                        return;
                    }
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("Source", event.getSource());
                    entry.put("DetailType", event.getDetailType());
                    entry.put("Detail", event.getDetail() != null ? event.getDetail() : "{}");
                    entry.put("EventBusName", destBusName);
                    eventSender.accept(List.of(entry));
                    progressUpdater.accept(event.getEventTime());
                }

                stateUpdater.accept(replayName, ReplayState.COMPLETED);
                promise.complete();
            } catch (Exception e) {
                LOG.warnv("Replay {0} failed: {1}", replayName, e.getMessage());
                stateUpdater.accept(replayName, ReplayState.FAILED);
                promise.fail(e);
            } finally {
                cancelFlags.remove(replayName);
            }
        });
    }

    /**
     * Signals a running replay to stop after the current event. Returns false if the replay is
     * not running (already completed, cancelled, or unknown).
     */
    boolean requestCancel(String replayName) {
        AtomicBoolean flag = cancelFlags.get(replayName);
        if (flag != null) {
            flag.set(true);
            return true;
        }
        return false;
    }

    private static String busNameFromArn(String arn) {
        if (arn == null) {
            return "default";
        }
        int idx = arn.lastIndexOf("event-bus/");
        return idx >= 0 ? arn.substring(idx + "event-bus/".length()) : arn;
    }
}
