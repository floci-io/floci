package io.github.hectorvent.floci.services.iot;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class IotPublishEventRecorder {

    private final List<IotPublishEvent> events = new ArrayList<>();

    public synchronized void record(String topic, byte[] payload) {
        events.add(new IotPublishEvent(topic, payload == null ? new byte[0] : payload.clone()));
    }

    public synchronized List<IotPublishEvent> recentEvents() {
        return List.copyOf(events);
    }

    public synchronized void clear() {
        events.clear();
    }
}
