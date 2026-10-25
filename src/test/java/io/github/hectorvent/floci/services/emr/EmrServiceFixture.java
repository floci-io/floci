package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An {@link EmrService} on in-memory storage, with a clock the test moves and wakeups the test runs,
 * recording the {@code aws.emr} events it publishes. Each event is handed to {@link #target} as it is
 * published, as EventBridge's first delivery is, and checked to be published once the service is let go,
 * and, for a wakeup's, after it, as the timer's own thread never sends them.
 */
final class EmrServiceFixture {

    static final String REGION = "us-east-1";
    static final String ACCOUNT = "000000000000";

    /** A rule's target, given each event as it is published. */
    @FunctionalInterface
    interface Target {
        void deliver(JsonNode event) throws Exception;
    }

    private record Wakeup(Instant due, Runnable task) {}

    final ObjectMapper objectMapper = new ObjectMapper();
    final List<JsonNode> published = new ArrayList<>();
    final MutableClock clock;
    final EmrEventPublisher publisher;
    // Not final: the events' check reads it, from the mock set up before the service is built.
    EmrService service;
    Target target = event -> { };
    private final List<Wakeup> wakeups = new ArrayList<>();
    private final List<Runnable> deliveries = new ArrayList<>();
    private boolean waking;
    private final InMemoryStorageFactory storage;
    private final int[] durations;

    /** The service as configured by default, every duration 0. */
    EmrServiceFixture() {
        this(0, 0, 0);
    }

    EmrServiceFixture(int startupDelaySeconds, int stepDurationSeconds, int terminationDelaySeconds) {
        this(new InMemoryStorageFactory(ACCOUNT), new MutableClock(),
                startupDelaySeconds, stepDurationSeconds, terminationDelaySeconds);
    }

    private EmrServiceFixture(InMemoryStorageFactory storage, MutableClock clock, int... durations) {
        this.storage = storage;
        this.clock = clock;
        this.durations = durations;
        EventBridgeService eventBridge = mock(EventBridgeService.class);
        doAnswer(invocation -> {
            // The call that made the change has let go of the service by now.
            assertFalse(Thread.holdsLock(service), "published while EmrService was held");
            assertFalse(waking, "published by a wakeup itself");
            JsonNode event = objectMapper.createObjectNode()
                    .put("detail-type", invocation.<String>getArgument(1))
                    .set("detail", objectMapper.readTree(invocation.<String>getArgument(2)));
            published.add(event);
            target.deliver(event);
            return null;
        }).when(eventBridge).publishServiceEvent(eq("aws.emr"), anyString(), anyString(), isNull(), eq(REGION));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().emr().defaultReleaseLabel()).thenReturn("emr-7.5.0");
        when(config.services().emr().clusterStartupDelaySeconds()).thenReturn(durations[0]);
        when(config.services().emr().stepDurationSeconds()).thenReturn(durations[1]);
        when(config.services().emr().clusterTerminationDelaySeconds()).thenReturn(durations[2]);
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        publisher = new EmrEventPublisher(eventBridge, objectMapper, regionResolver);
        service = new EmrService(storage, config, regionResolver, publisher, clock, new EmrService.Wakeups() {
            @Override
            public void at(Instant due, Runnable task) {
                wakeups.add(new Wakeup(due, task));
            }

            @Override
            public void deliver(Runnable delivery) {
                deliveries.add(delivery);
            }
        });
    }

    /** A new service on the same storage and clock, as after a restart of Floci with persistent storage. */
    EmrServiceFixture restart() {
        return new EmrServiceFixture(storage, clock, durations);
    }

    /**
     * Moves the clock on by {@code duration} and runs the wakeups due by then, as the service's timer
     * would, the ones they set included, each followed by the delivery of its events.
     */
    void pass(Duration duration) {
        clock.advance(duration);
        Instant now = clock.instant();
        List<Wakeup> due;
        while (!(due = wakeups.stream().filter(wakeup -> !wakeup.due().isAfter(now)).toList()).isEmpty()) {
            wakeups.removeAll(due);
            for (Wakeup wakeup : due) {
                waking = true;
                try {
                    wakeup.task().run();
                } finally {
                    waking = false;
                }
                while (!deliveries.isEmpty()) {
                    deliveries.remove(0).run();
                }
            }
        }
    }

    /** The times of the wakeups not yet run. */
    List<Instant> wakeupTimes() {
        return wakeups.stream().map(Wakeup::due).toList();
    }

    /** Each event's state, a step's followed by its name. */
    List<String> states() {
        return published.stream().map(event -> {
            JsonNode detail = event.path("detail");
            return detail.has("stepId")
                    ? detail.path("state").asText() + " " + detail.path("name").asText()
                    : detail.path("state").asText();
        }).toList();
    }

    static EmrCluster cluster(String name, boolean keepAlive, EmrStep... steps) {
        EmrCluster cluster = new EmrCluster();
        cluster.setName(name);
        cluster.setKeepJobFlowAliveWhenNoSteps(keepAlive);
        cluster.setSteps(new ArrayList<>(List.of(steps)));
        return cluster;
    }

    static EmrStep step(String name) {
        EmrStep step = new EmrStep();
        step.setName(name);
        step.setActionOnFailure("CONTINUE");
        return step;
    }
}
