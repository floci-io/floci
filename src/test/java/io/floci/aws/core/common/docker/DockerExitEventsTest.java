package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerExitEventsTest {

    private final DockerClient docker = mock(DockerClient.class);
    private final DockerClient streaming = mock(DockerClient.class);
    private final List<ResultCallback.Adapter<Event>> subscriptions = new CopyOnWriteArrayList<>();
    private final List<String> exits = new CopyOnWriteArrayList<>();
    private DockerExitEvents events;

    @BeforeEach
    void subscribeOnDemand() {
        EventsCmd command = mock(EventsCmd.class, Answers.RETURNS_SELF);
        when(streaming.eventsCmd()).thenReturn(command);
        when(command.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Event> callback = invocation.getArgument(0);
            subscriptions.add(callback);
            callback.onStart(() -> { });
            return callback;
        });
        events = new DockerExitEvents(docker, streaming, true);
    }

    @AfterEach
    void close() {
        events.close();
    }

    private void inspected(String containerId, String status, Long exitCode) {
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(docker.inspectContainerCmd(containerId)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getStatus()).thenReturn(status);
        when(state.getExitCodeLong()).thenReturn(exitCode);
    }

    private static Event die(String containerId, Map<String, String> attributes) {
        return new Event("die", containerId, "image", 0L).withAction("die")
                .withEventActor(new EventActor().withId(containerId).withAttributes(attributes));
    }

    // Catches: an exit reported with the event's code when the inspect knows better, or not at all.
    @Test
    void aDieEventReportsTheInspectedExitCodeOnce() {
        inspected("c1", "exited", 3L);
        events.register("c1", code -> exits.add("c1=" + code));

        subscriptions.get(0).onNext(die("c1", Map.of("exitCode", "137")));
        subscriptions.get(0).onNext(die("c1", Map.of("exitCode", "137")));

        await("the exit should be reported").atMost(Duration.ofSeconds(5)).until(() -> exits.size() == 1);
        assertEquals(List.of("c1=3"), exits);
        assertEquals(0, events.registeredCount(), "a reported container is no longer watched");
    }

    // Catches: an exit that happened while the stream was down never being reported, or a container
    // still being created reported as exited by the sweep.
    @Test
    void aDroppedStreamSubscribesAgainAndTheSweepReportsOnlyEndedContainers() {
        inspected("ended", "exited", 1L);
        inspected("starting", "created", 0L);
        events.register("ended", code -> exits.add("ended=" + code));
        events.register("starting", code -> exits.add("starting=" + code));

        subscriptions.get(0).onError(new RuntimeException("daemon restarted"));

        await("the stream should be opened again").atMost(Duration.ofSeconds(5))
                .until(() -> subscriptions.size() == 2);
        await("the sweep should report the container that ended").atMost(Duration.ofSeconds(5))
                .until(() -> exits.size() == 1);
        assertEquals(List.of("ended=1"), exits);
        assertEquals(1, events.registeredCount(), "the container still being created stays watched");
    }

    // Catches: an event and the reconnect sweep both reporting the same exit.
    @Test
    void anEventAndTheSweepReportAnExitOnce() {
        inspected("c1", "exited", 2L);
        events.register("c1", code -> exits.add("c1=" + code));

        subscriptions.get(0).onNext(die("c1", null));
        events.sweep();

        await("the exit should be reported once").during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5))
                .until(() -> exits.size() == 1);
        assertEquals(List.of("c1=2"), exits);
    }

    // Catches: a container removed while the stream was down staying registered forever.
    @Test
    void theSweepForgetsAContainerThatIsGone() {
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("gone")).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("No such container"));
        events.register("gone", code -> exits.add("gone=" + code));

        events.sweep();

        assertEquals(0, events.registeredCount());
        assertTrue(exits.isEmpty());
    }

    // Catches: a stop event, which fires before the exit, being taken for the exit.
    @Test
    void onlyDieCounts() {
        events.register("c1", code -> exits.add("c1=" + code));

        events.onEvent(new Event("stop", "c1", "image", 0L).withAction("stop")
                .withEventActor(new EventActor().withId("c1")));

        assertEquals(1, events.registeredCount());
        verify(streaming, times(1)).eventsCmd();
    }

    @Test
    void theExitCodeAttributeFallsBackToMinusOne() {
        assertEquals(137, DockerExitEvents.exitCodeAttribute(die("c", Map.of("exitCode", "137"))));
        assertEquals(-1, DockerExitEvents.exitCodeAttribute(die("c", Map.of("exitCode", "oops"))));
        assertEquals(-1, DockerExitEvents.exitCodeAttribute(die("c", Map.of())));
        assertEquals(-1, DockerExitEvents.exitCodeAttribute(die("c", null)));
        assertEquals(-1, DockerExitEvents.exitCodeAttribute(new Event("die", "c", "image", 0L)));
    }
}
