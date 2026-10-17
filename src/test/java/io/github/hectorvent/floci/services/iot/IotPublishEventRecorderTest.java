package io.github.hectorvent.floci.services.iot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IotPublishEventRecorderTest {

    @Test
    void keepsOnlyTheMostRecentEvents() {
        IotPublishEventRecorder recorder = new IotPublishEventRecorder();
        int max = IotPublishEventRecorder.MAX_RETAINED_EVENTS;

        for (int i = 0; i <= max; i++) {
            recorder.record("t/" + i, "{}".getBytes(StandardCharsets.UTF_8));
        }

        List<IotPublishEvent> events = recorder.recentEvents();
        assertEquals(max, events.size());
        assertEquals("t/1", events.get(0).topic());
        assertEquals("t/" + max, events.get(events.size() - 1).topic());
    }
}
