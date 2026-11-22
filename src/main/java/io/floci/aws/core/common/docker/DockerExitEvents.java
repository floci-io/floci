package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import io.floci.aws.config.EmulatorConfig;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Container exits seen through one Docker events subscription for the whole daemon, instead of one
 * {@code /wait} stream, with its streaming connection and thread, per watched container
 * ({@code floci.docker.events-exit-detection}). The AWS ECS agent watches its containers the same way.
 *
 * <p>Events are edge-triggered where a wait is level-triggered: an exit the subscription was not
 * connected for is never delivered. So a container is registered before it starts, the first
 * subscription is confirmed before any registration returns, and after the stream drops (a daemon
 * restart, a lost connection) the subscription is opened again and every registered container is
 * inspected, which reports an exit the gap hid. Only {@code die} counts: {@code stop} fires on the
 * request, before the exit, and an out-of-memory kill also ends in {@code die}. The exit code comes
 * from an inspect, with the event's {@code exitCode} attribute as the fallback, and each container's
 * exit is reported once, off the events thread.
 */
@ApplicationScoped
public class DockerExitEvents {

    private static final Logger LOG = Logger.getLogger(DockerExitEvents.class);
    private static final Duration SUBSCRIBE_CONFIRMATION = Duration.ofSeconds(5);
    private static final Duration FIRST_RESUBSCRIBE_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RESUBSCRIBE_DELAY = Duration.ofSeconds(30);

    private final DockerClient dockerClient;
    private final DockerClient streamingDocker;
    private final boolean enabled;
    private final Map<String, Registration> registered = new ConcurrentHashMap<>();
    private final Object subscriptionLock = new Object();
    private volatile ResultCallback.Adapter<Event> subscription;
    private volatile boolean shutdown;
    private int failedSubscriptions;

    @Inject
    public DockerExitEvents(DockerClient dockerClient, @StreamingDocker DockerClient streamingDocker,
                            EmulatorConfig config) {
        this(dockerClient, streamingDocker, config.docker().eventsExitDetection());
    }

    DockerExitEvents(DockerClient dockerClient, DockerClient streamingDocker, boolean enabled) {
        this.dockerClient = dockerClient;
        this.streamingDocker = streamingDocker;
        this.enabled = enabled;
    }

    /** Whether exits are watched through the events subscription rather than one wait per container. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Watches {@code containerId} for its exit, calling {@code onExit} once with its exit code (-1 when
     * unknown). Call it before the container starts; if the start fails, {@link #unregister} it.
     */
    public void register(String containerId, IntConsumer onExit) {
        registered.put(containerId, new Registration(containerId, onExit));
        ensureSubscribed();
    }

    /** Stops watching a container that never started or that its owner no longer needs watched. */
    public void unregister(String containerId) {
        registered.remove(containerId);
    }

    int registeredCount() {
        return registered.size();
    }

    private void ensureSubscribed() {
        synchronized (subscriptionLock) {
            if (subscription != null || shutdown) {
                return;
            }
            subscribe();
        }
    }

    /** Opens the subscription and waits for Docker to confirm it, so no exit after this returns is missed. */
    private void subscribe() {
        ResultCallback.Adapter<Event> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Event event) {
                onEvent(event);
            }

            @Override
            public void onError(Throwable throwable) {
                super.onError(throwable);
                streamEnded(this, throwable);
            }

            @Override
            public void onComplete() {
                super.onComplete();
                streamEnded(this, null);
            }
        };
        subscription = callback;
        try {
            streamingDocker.eventsCmd()
                    .withEventTypeFilter(EventType.CONTAINER)
                    .withEventFilter("die")
                    .exec(callback);
            if (!callback.awaitStarted(SUBSCRIBE_CONFIRMATION.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warnv("Docker did not confirm the container events subscription within {0} s; exits are"
                        + " checked by inspect once it does", SUBSCRIBE_CONFIRMATION.toSeconds());
            }
            synchronized (subscriptionLock) {
                failedSubscriptions = 0;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            streamEnded(callback, e);
        }
    }

    private void streamEnded(ResultCallback.Adapter<Event> ended, Throwable cause) {
        long delayMillis;
        synchronized (subscriptionLock) {
            if (subscription != ended || shutdown) {
                return;
            }
            subscription = null;
            failedSubscriptions++;
            delayMillis = Math.min(MAX_RESUBSCRIBE_DELAY.toMillis(),
                    FIRST_RESUBSCRIBE_DELAY.toMillis() << Math.min(failedSubscriptions - 1, 5));
        }
        LOG.debugv("Docker container events stream ended ({0}); subscribing again in {1} ms",
                cause == null ? "closed" : cause.toString(), delayMillis);
        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(this::resubscribe);
    }

    private void resubscribe() {
        synchronized (subscriptionLock) {
            if (subscription != null || shutdown) {
                return;
            }
            subscribe();
        }
        sweep();
    }

    /** Inspects every registered container, reporting the exits a gap in the subscription hid. */
    void sweep() {
        for (Registration registration : registered.values()) {
            InspectContainerResponse.ContainerState state;
            try {
                state = dockerClient.inspectContainerCmd(registration.containerId()).exec().getState();
            } catch (NotFoundException e) {
                registered.remove(registration.containerId(), registration);
                continue;
            } catch (RuntimeException e) {
                LOG.debugv("Could not inspect container {0} after the events stream reconnected: {1}",
                        registration.containerId(), e.getMessage());
                continue;
            }
            // "created" has not started yet; only a container that ran and ended has exited.
            String status = state == null ? null : state.getStatus();
            if ("exited".equals(status) || "dead".equals(status)) {
                Long exitCode = state.getExitCodeLong();
                report(registration, exitCode != null ? exitCode.intValue() : -1);
            }
        }
    }

    void onEvent(Event event) {
        if (event == null || !"die".equals(event.getAction() != null ? event.getAction() : event.getStatus())) {
            return;
        }
        String containerId = event.getActor() != null && event.getActor().getId() != null
                ? event.getActor().getId() : event.getId();
        Registration registration = containerId == null ? null : registered.get(containerId);
        if (registration != null) {
            report(registration, null, exitCodeAttribute(event));
        }
    }

    private void report(Registration registration, int exitCode) {
        report(registration, exitCode, exitCode);
    }

    /** Reports an exit once, off the caller's thread; {@code known} is a code already read by inspect. */
    private void report(Registration registration, Integer known, int fallback) {
        if (!registration.reported().compareAndSet(false, true)) {
            return;
        }
        registered.remove(registration.containerId(), registration);
        Thread.ofVirtual().name("floci-container-exit").start(() -> {
            int exitCode = known != null ? known : inspectedExitCode(registration.containerId(), fallback);
            try {
                registration.onExit().accept(exitCode);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Handling the exit of container {0} failed", registration.containerId());
            }
        });
    }

    private int inspectedExitCode(String containerId, int fallback) {
        try {
            InspectContainerResponse.ContainerState state = dockerClient.inspectContainerCmd(containerId).exec().getState();
            Long exitCode = state == null ? null : state.getExitCodeLong();
            return exitCode != null ? exitCode.intValue() : fallback;
        } catch (RuntimeException e) {
            LOG.debugv("Could not inspect exited container {0}; using its event''s exit code: {1}",
                    containerId, e.getMessage());
            return fallback;
        }
    }

    /** The event's {@code exitCode} attribute, or -1 when it is missing or not a number. */
    static int exitCodeAttribute(Event event) {
        Map<String, String> attributes = event.getActor() == null ? null : event.getActor().getAttributes();
        String exitCode = attributes == null ? null : attributes.get("exitCode");
        if (exitCode == null) {
            return -1;
        }
        try {
            return Integer.parseInt(exitCode.trim());
        } catch (NumberFormatException e) {
            LOG.debugv("Container event carried a non-numeric exit code: {0}", exitCode);
            return -1;
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        close();
    }

    void close() {
        ResultCallback.Adapter<Event> open;
        synchronized (subscriptionLock) {
            shutdown = true;
            open = subscription;
            subscription = null;
        }
        if (open != null) {
            try {
                open.close();
            } catch (IOException e) {
                LOG.debugv("Could not close the Docker container events stream: {0}", e.getMessage());
            }
        }
    }

    private record Registration(String containerId, IntConsumer onExit, AtomicBoolean reported) {
        Registration(String containerId, IntConsumer onExit) {
            this(containerId, onExit, new AtomicBoolean());
        }
    }
}
