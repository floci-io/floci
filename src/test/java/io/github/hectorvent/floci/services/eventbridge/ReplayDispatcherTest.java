package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.services.eventbridge.model.Replay;
import io.github.hectorvent.floci.services.eventbridge.model.ReplayState;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDispatcherTest {

    private Vertx vertx;
    private ReplayDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        dispatcher = new ReplayDispatcher(vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    void cancellationIsScopedToReplayArnWhenNamesRepeat() throws InterruptedException {
        Replay first = replay("arn:aws:events:us-east-1:111111111111:replay/shared");
        Replay second = replay("arn:aws:events:us-west-2:222222222222:replay/shared");
        CountDownLatch firstEventStarted = new CountDownLatch(1);
        CountDownLatch releaseEvents = new CountDownLatch(1);
        CountDownLatch terminalStates = new CountDownLatch(2);
        Map<String, ReplayState> terminalStateByArn = new ConcurrentHashMap<>();

        dispatch(first, firstEventStarted, releaseEvents, terminalStates, terminalStateByArn);
        dispatch(second, firstEventStarted, releaseEvents, terminalStates, terminalStateByArn);

        assertTrue(firstEventStarted.await(5, TimeUnit.SECONDS));
        assertTrue(dispatcher.requestCancel(first.getReplayArn()));
        releaseEvents.countDown();

        assertTrue(terminalStates.await(5, TimeUnit.SECONDS));
        assertEquals(ReplayState.CANCELLED, terminalStateByArn.get(first.getReplayArn()));
        assertEquals(ReplayState.COMPLETED, terminalStateByArn.get(second.getReplayArn()));
    }

    private void dispatch(Replay replay,
                          CountDownLatch firstEventStarted,
                          CountDownLatch releaseEvents,
                          CountDownLatch terminalStates,
                          Map<String, ReplayState> terminalStateByArn) {
        dispatcher.dispatch(
                replay,
                List.of(archivedEvent(), archivedEvent()),
                events -> {
                    firstEventStarted.countDown();
                    await(releaseEvents);
                },
                (ignoredName, state) -> {
                    if (state == ReplayState.CANCELLED || state == ReplayState.COMPLETED) {
                        terminalStateByArn.put(replay.getReplayArn(), state);
                        terminalStates.countDown();
                    }
                },
                ignoredTime -> { });
    }

    private static Replay replay(String arn) {
        Replay replay = new Replay();
        replay.setReplayName("shared");
        replay.setReplayArn(arn);
        replay.setEventStartTime(Instant.EPOCH);
        replay.setEventEndTime(Instant.MAX);
        return replay;
    }

    private static io.github.hectorvent.floci.services.eventbridge.model.ArchivedEvent archivedEvent() {
        io.github.hectorvent.floci.services.eventbridge.model.ArchivedEvent event =
                new io.github.hectorvent.floci.services.eventbridge.model.ArchivedEvent();
        event.setEventTime(Instant.EPOCH.plusSeconds(1));
        return event;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
