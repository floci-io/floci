package io.github.hectorvent.floci.services.iot;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@ApplicationScoped
public class IotPublishEventRecorder {

    static final int MAX_RETAINED_EVENTS = 1_000;

    private final Deque<IotPublishEvent> events = new ArrayDeque<>();

    public synchronized void record(String topic, byte[] payload) {
        events.addLast(new IotPublishEvent(topic, payload == null ? new byte[0] : payload.clone()));
        while (events.size() > MAX_RETAINED_EVENTS) {
            events.pollFirst();
        }
    }

    public synchronized List<IotPublishEvent> recentEvents() {
        return List.copyOf(events);
    }

    public synchronized void clear() {
        events.clear();
    }
}
