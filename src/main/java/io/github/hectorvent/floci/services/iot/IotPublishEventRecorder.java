package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class IotPublishEventRecorder implements Resettable {

    private final List<IotPublishEvent> events = new ArrayList<>();

    public synchronized void record(String topic, byte[] payload) {
        events.add(new IotPublishEvent(topic, payload == null ? new byte[0] : payload.clone()));
    }

    public synchronized List<IotPublishEvent> recentEvents() {
        return List.copyOf(events);
    }

    /** Recorded publish events are inspection state; this already existed but was never
     *  reachable from a state reset because the class did not declare {@link Resettable}. */
    @Override
    public synchronized void clear() {
        events.clear();
    }
}
