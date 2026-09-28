package io.github.hectorvent.floci.services.rds.container;

import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

/**
 * Aurora Serverless v2 auto-pause for one RDS backend container.
 *
 * <p>Every client of the container, a proxied connection, a Data API call or a command Floci runs
 * inside it, holds a {@link RdsBackendGate.Lease} while it uses the container. Once no lease has
 * been held for {@code SecondsUntilAutoPause}, the container is frozen with {@code docker pause},
 * which keeps its data and its published port. The next lease request thaws it and waits until
 * it runs again.
 *
 * <p>The state moves from RUNNING to PAUSING, PAUSED and RESUMING, and back to RUNNING. Every
 * transition happens under {@link #lock}; the Docker calls and the listener run without it, so a
 * slow daemon or a listener waiting for the RDS service never holds up a thread that needs the
 * lock. A lease request that finds the container PAUSING cancels the pause, and one that finds it
 * RESUMING waits for the resume, so no client reaches a frozen container and none is turned away.
 */
final class AutoPauseController {

    private static final Logger LOG = Logger.getLogger(AutoPauseController.class);

    /** How often a paused container reports zero capacity, as a paused Aurora instance does. */
    private static final Duration PAUSED_REPORT_INTERVAL = Duration.ofMinutes(1);

    enum State {
        RUNNING,
        PAUSING,
        PAUSED,
        RESUMING
    }

    /** Runs a task after a delay; cancelling the returned future stops a task that has not started. */
    @FunctionalInterface
    interface Scheduler {
        Future<?> schedule(Runnable task, Duration delay);
    }

    /** The Docker freezer. Both calls succeed when the container is already in the requested state. */
    interface Freezer {
        void pause(String containerId);

        void unpause(String containerId);
    }

    private final String containerId;
    private final String host;
    private final int port;
    private final Freezer freezer;
    private final Scheduler scheduler;
    private final Executor notifier;
    private final Clock clock;
    private final Duration resumeDelay;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition settled = lock.newCondition();

    // Everything below is guarded by lock.
    private final List<Signal> pendingSignals = new ArrayList<>();
    private State state = State.RUNNING;
    private int leases;
    private Integer secondsUntilAutoPause;
    private AutoPauseListener listener = AutoPauseListener.NONE;
    // The one timer of the current state: the idle timer while RUNNING, the zero-capacity report
    // while PAUSED, the resume delay while RESUMING. A task whose token is not the current one
    // was cancelled or belongs to an earlier state, and does nothing.
    private Future<?> timer;
    private long timerToken;
    private boolean pauseCanceled;
    private boolean closed;
    private Instant lastSignal = Instant.EPOCH;

    AutoPauseController(String containerId, String host, int port, Freezer freezer,
                        Scheduler scheduler, Executor notifier, Clock clock, Duration resumeDelay) {
        this.containerId = containerId;
        this.host = host;
        this.port = port;
        this.freezer = freezer;
        this.scheduler = scheduler;
        this.notifier = notifier;
        this.clock = clock;
        this.resumeDelay = resumeDelay;
    }

    boolean serves(String backendHost, int backendPort) {
        return port == backendPort && Objects.equals(host, backendHost);
    }

    boolean runs(String candidateContainerId) {
        return containerId.equals(candidateContainerId);
    }

    State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    int leaseCount() {
        lock.lock();
        try {
            return leases;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the idle interval after which the container pauses; null keeps it running. The idle
     * clock restarts when the interval changes, and a container paused or pausing under the old
     * interval is resumed, as Aurora resumes an instance whose scaling range changes.
     */
    void configure(Integer seconds, AutoPauseListener newListener) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            listener = newListener != null ? newListener : AutoPauseListener.NONE;
            if (Objects.equals(secondsUntilAutoPause, seconds)) {
                return;
            }
            secondsUntilAutoPause = seconds;
            switch (state) {
                case RUNNING -> {
                    cancelTimer();
                    armIdleTimer();
                }
                case PAUSING -> pauseCanceled = true;
                case PAUSED -> startTimer(this::resumeInBackground, Duration.ZERO);
                case RESUMING -> {
                    // Finishing the resume arms the idle timer with the new interval.
                }
            }
        } finally {
            unlockAndDispatch();
        }
    }

    /**
     * Returns a lease that keeps the container awake, resuming it first when it is paused. Waits
     * while a pause or a resume is under way; a pause still in progress is cancelled.
     *
     * @throws InterruptedException when interrupted while waiting
     * @throws IllegalStateException when Docker cannot resume the container, which stays paused
     */
    RdsBackendGate.Lease acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                if (closed && state != State.PAUSING && state != State.RESUMING) {
                    return RdsBackendGate.Lease.NONE;
                }
                if (state == State.RUNNING) {
                    leases++;
                    cancelTimer();
                    return new ControllerLease();
                }
                if (state == State.PAUSED) {
                    resume();
                } else {
                    if (state == State.PAUSING) {
                        pauseCanceled = true;
                    }
                    settled.await();
                }
            }
        } finally {
            unlockAndDispatch();
        }
    }

    /**
     * Stops auto-pausing for good, thawing the container when it is paused. A pause or a resume
     * in progress is not waited for: the thread running it thaws the container once it sees the
     * controller closed.
     */
    void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            // A resume whose timer is set has already thawed the container and only waits out
            // the resume delay; cancelling that timer must not leave its waiters behind.
            boolean resumeDelayPending = state == State.RESUMING && timer != null;
            cancelTimer();
            if (resumeDelayPending) {
                finishResume();
            } else if (state == State.PAUSED) {
                state = State.RESUMING;
                RuntimeException failure = withoutLock(() -> freezer.unpause(containerId));
                if (failure != null) {
                    LOG.warnv(failure, "Could not resume auto-paused RDS container {0} before releasing it",
                            containerId);
                    state = State.PAUSED;
                } else {
                    state = State.RUNNING;
                }
            }
            settled.signalAll();
        } finally {
            unlockAndDispatch();
        }
    }

    /**
     * Runs the idle check now rather than when the idle timer fires, for a caller that cannot wait
     * SecondsUntilAutoPause. Returns whether the container paused.
     */
    boolean pauseIfIdle() {
        long token;
        lock.lock();
        try {
            if (!idleTimerArmed(timerToken)) {
                return false;
            }
            token = timerToken;
        } finally {
            unlockAndDispatch();
        }
        idleTimerFired(token);
        return state() == State.PAUSED;
    }

    private void release() {
        lock.lock();
        try {
            if (leases > 0) {
                leases--;
            }
            armIdleTimer();
        } finally {
            unlockAndDispatch();
        }
    }

    private void idleTimerFired(long token) {
        AutoPauseListener current;
        lock.lock();
        try {
            if (!idleTimerArmed(token)) {
                return;
            }
            current = listener;
        } finally {
            unlockAndDispatch();
        }
        // Asked without the lock: the answer comes from the RDS service, which may be busy.
        boolean allowed = mayPause(current);
        lock.lock();
        try {
            if (!idleTimerArmed(token)) {
                return;
            }
            if (!allowed) {
                armIdleTimer();
                return;
            }
            cancelTimer();
            state = State.PAUSING;
            pauseCanceled = false;
            signal(AutoPauseListener.Event.PAUSE_INITIATED);
            pause();
        } finally {
            unlockAndDispatch();
        }
    }

    private boolean idleTimerArmed(long token) {
        return !closed && state == State.RUNNING && leases == 0 && secondsUntilAutoPause != null
                && timer != null && token == timerToken;
    }

    /** Freezes the container. Entered and left holding the lock, in PAUSING. */
    private void pause() {
        RuntimeException pauseFailure = withoutLock(() -> freezer.pause(containerId));
        if (pauseFailure == null && !pauseCanceled && !closed) {
            state = State.PAUSED;
            signal(AutoPauseListener.Event.PAUSED);
            armPausedReport();
            settled.signalAll();
            LOG.infov("Auto-paused idle RDS container {0} (SecondsUntilAutoPause {1})",
                    containerId, String.valueOf(secondsUntilAutoPause));
            return;
        }
        if (pauseFailure != null) {
            LOG.warnv(pauseFailure, "Could not auto-pause RDS container {0}; it keeps running", containerId);
        } else {
            // A client arrived, the interval changed or the container is being released while it
            // was freezing: thaw it again rather than let anyone reach it frozen.
            RuntimeException thawFailure = withoutLock(() -> freezer.unpause(containerId));
            if (thawFailure != null) {
                LOG.warnv(thawFailure, "Could not undo the auto-pause of RDS container {0}", containerId);
                state = State.PAUSED;
                armPausedReport();
                settled.signalAll();
                return;
            }
        }
        state = State.RUNNING;
        signal(AutoPauseListener.Event.PAUSE_CANCELED);
        settled.signalAll();
        armIdleTimer();
    }

    /**
     * Thaws the container. Entered and left holding the lock, in PAUSED. Waiters see RESUMING
     * until the resume delay has passed.
     */
    private void resume() {
        cancelTimer();
        state = State.RESUMING;
        signal(AutoPauseListener.Event.RESUME_INITIATED);
        RuntimeException failure = withoutLock(() -> freezer.unpause(containerId));
        if (failure != null) {
            state = State.PAUSED;
            armPausedReport();
            settled.signalAll();
            throw new IllegalStateException(
                    "Could not resume auto-paused RDS container " + containerId, failure);
        }
        if (closed || resumeDelay.isZero()) {
            finishResume();
        } else {
            startTimer(this::resumeDelayElapsed, resumeDelay);
        }
    }

    private void resumeDelayElapsed(long token) {
        lock.lock();
        try {
            if (state == State.RESUMING && token == timerToken) {
                finishResume();
            }
        } finally {
            unlockAndDispatch();
        }
    }

    private void finishResume() {
        cancelTimer();
        state = State.RUNNING;
        signal(AutoPauseListener.Event.RESUMED);
        settled.signalAll();
        armIdleTimer();
        LOG.infov("Resumed auto-paused RDS container {0}", containerId);
    }

    private void resumeInBackground(long token) {
        lock.lock();
        try {
            if (!closed && state == State.PAUSED && token == timerToken) {
                resume();
            }
        } catch (IllegalStateException e) {
            LOG.warnv(e, "Could not resume auto-paused RDS container {0}", containerId);
        } finally {
            unlockAndDispatch();
        }
    }

    private void armIdleTimer() {
        if (closed || state != State.RUNNING || leases > 0 || secondsUntilAutoPause == null) {
            return;
        }
        startTimer(this::idleTimerFired, Duration.ofSeconds(secondsUntilAutoPause));
    }

    private void armPausedReport() {
        if (!closed) {
            startTimer(this::pausedReportDue, PAUSED_REPORT_INTERVAL);
        }
    }

    private void pausedReportDue(long token) {
        lock.lock();
        try {
            if (!closed && state == State.PAUSED && token == timerToken) {
                signal(AutoPauseListener.Event.STILL_PAUSED);
                armPausedReport();
            }
        } finally {
            unlockAndDispatch();
        }
    }

    private void startTimer(LongConsumer task, Duration delay) {
        cancelTimer();
        long token = timerToken;
        timer = scheduler.schedule(() -> task.accept(token), delay);
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        timerToken++;
    }

    /** Runs a Docker call with the lock released, and returns its failure instead of throwing it. */
    private RuntimeException withoutLock(Runnable dockerCall) {
        if (lock.getHoldCount() != 1) {
            throw new IllegalStateException("Auto-pause lock must be held exactly once around a Docker call");
        }
        unlockAndDispatch();
        try {
            dockerCall.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        } finally {
            lock.lock();
        }
    }

    private boolean mayPause(AutoPauseListener current) {
        try {
            return current.mayPause();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not tell whether RDS container {0} may auto-pause; it keeps running",
                    containerId);
            return false;
        }
    }

    /** Queues an event for the listener, stamped later than the previous one so they keep their order. */
    private void signal(AutoPauseListener.Event event) {
        Instant now = clock.instant();
        Instant at = now.isAfter(lastSignal) ? now : lastSignal.plusMillis(1);
        lastSignal = at;
        pendingSignals.add(new Signal(listener, event, at));
    }

    /** Releases the lock, then hands the events queued under it to the notifier, in order. */
    private void unlockAndDispatch() {
        List<Signal> ready = pendingSignals.isEmpty() ? List.of() : List.copyOf(pendingSignals);
        pendingSignals.clear();
        lock.unlock();
        for (Signal signal : ready) {
            try {
                notifier.execute(() -> deliver(signal));
            } catch (RejectedExecutionException e) {
                LOG.debugv("Dropped RDS auto-pause event {0} for container {1}: {2}",
                        signal.event(), containerId, e.getMessage());
            }
        }
    }

    private void deliver(Signal signal) {
        try {
            signal.listener().onAutoPause(signal.event(), signal.at());
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not report RDS auto-pause event {0} for container {1}",
                    signal.event(), containerId);
        }
    }

    private record Signal(AutoPauseListener listener, AutoPauseListener.Event event, Instant at) {
    }

    private final class ControllerLease implements RdsBackendGate.Lease {

        private final AtomicBoolean released = new AtomicBoolean();

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release();
            }
        }
    }
}
