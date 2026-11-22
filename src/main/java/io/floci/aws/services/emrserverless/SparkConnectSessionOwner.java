package io.floci.aws.services.emrserverless;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A service's sessions as their servers' owner ({@link SessionServers.Owner}): the states a session goes through
 * as its server starts, answers, takes calls and stops, which EMR Serverless's sessions and EMR's share. It is
 * SUBMITTED until its server's container runs, STARTING until the server answers, then IDLE, its idle timeout counting
 * from then; BUSY while a call runs on its server and IDLE again once none does; and once its server stops, TERMINATED,
 * or FAILED where that failed it. Each step is taken under the service's lock on the session as stored; {@code R} is
 * what the service stores, {@link #session} the session in it.
 */
public abstract class SparkConnectSessionOwner<R> implements SessionServers.Owner {

    private static final Logger LOG = Logger.getLogger(SparkConnectSessionOwner.class);

    public static final String SUBMITTED = "SUBMITTED";
    public static final String STARTING = "STARTING";
    public static final String IDLE = "IDLE";
    public static final String BUSY = "BUSY";
    public static final String TERMINATING = "TERMINATING";
    public static final String TERMINATED = "TERMINATED";
    public static final String FAILED = "FAILED";
    private static final Set<String> ENDED = Set.of(TERMINATED, FAILED);
    // No source gives AWS's wording for these; they are Floci's.
    public static final String SERVER_EXITED = "The session's Spark Connect server exited with code %d.";
    public static final String SERVER_NOT_STARTED = "The session's Spark Connect server could not start: %s";
    public static final String SERVER_SILENT = "The session's Spark Connect server did not answer within "
            + SessionServers.START_LIMIT.toMinutes() + " minutes: %s";
    public static final String SERVER_ENDED = "The session's Spark Connect server ended with no exit code.";

    /** Runs {@code body} under the service's lock. */
    protected abstract <T> T locked(Supplier<T> body);

    /** The stored session of that id. */
    protected abstract Optional<R> find(String sessionId);

    /** The session in what the service stores. */
    protected abstract SparkConnectSession session(R stored);

    protected abstract void save(R stored);

    protected abstract Instant now();

    /** Ends the session as {@code state}, TERMINATED or FAILED, for {@code reason} (null for none), now. */
    protected abstract void end(R stored, String state, String reason);

    /** What a session whose server stopped as {@code outcome} says, not failing it, ends TERMINATED for. */
    protected abstract String endReason(R stored, SparkJobRunner.Outcome outcome);

    /** Checks the session again at its next deadline, or cancels its check where it has none. */
    protected abstract void checkLater(R stored);

    /** Sets on the session what its server's first answer gives it, before it is saved IDLE; nothing by default. */
    protected void ready(R stored) {
    }

    @Override
    public void running(String sessionId) {
        inState(sessionId, SUBMITTED, stored -> {
            session(stored).setState(STARTING);
            session(stored).setUpdatedAt(now());
            save(stored);
        });
    }

    @Override
    public void answering(String sessionId) {
        inState(sessionId, STARTING, stored -> {
            Instant now = now();
            SparkConnectSession session = session(stored);
            session.setState(IDLE);
            session.setStartedAt(now);
            session.setIdleSince(now);
            session.setUpdatedAt(now);
            ready(stored);
            save(stored);
            checkLater(stored);
            LOG.infov("Session {0} is ready", sessionId);
        });
    }

    @Override
    public void silent(String sessionId, String why) {
        inState(sessionId, STARTING,
                stored -> end(stored, FAILED, String.format(SERVER_SILENT, why)));
    }

    /**
     * What a session whose server stopped as {@code outcome} says ended with, where that failed it: why it could not
     * start, or its exit code; null where it did not fail, as when it was stopped or ran out of time.
     */
    public static String failure(SparkJobRunner.Outcome outcome) {
        if (outcome.timedOut() || outcome.stopped()) {
            return null;
        }
        if (outcome.failure() != null) {
            return String.format(SERVER_NOT_STARTED, outcome.failure());
        }
        return outcome.exitCode() != null ? String.format(SERVER_EXITED, outcome.exitCode()) : SERVER_ENDED;
    }

    @Override
    public void stopped(String sessionId, SparkJobRunner.Outcome outcome) {
        locked(() -> {
            find(sessionId).filter(stored -> !ENDED.contains(session(stored).getState())).ifPresent(stored -> {
                String failure = failure(outcome);
                if (TERMINATING.equals(session(stored).getState()) || failure == null) {
                    end(stored, TERMINATED, endReason(stored, outcome));
                } else {
                    end(stored, FAILED, failure);
                }
            });
            return null;
        });
    }

    @Override
    public boolean starting(String sessionId) {
        return locked(() -> find(sessionId).map(stored -> STARTING.equals(session(stored).getState()))
                .orElse(false));
    }

    @Override
    public boolean going(String sessionId) {
        return locked(() -> find(sessionId).map(stored -> !ENDED.contains(session(stored).getState())
                && !TERMINATING.equals(session(stored).getState())).orElse(false));
    }

    // A call has started on an idle session: it is BUSY, and its idle timeout does not run.
    @Override
    public void busy(String sessionId) {
        inState(sessionId, IDLE, stored -> {
            session(stored).setState(BUSY);
            session(stored).setIdleSince(null);
            session(stored).setUpdatedAt(now());
            save(stored);
            checkLater(stored);
        });
    }

    // Its last call has ended: the session is IDLE again from now.
    @Override
    public void idle(String sessionId) {
        inState(sessionId, BUSY, stored -> {
            Instant now = now();
            session(stored).setState(IDLE);
            session(stored).setIdleSince(now);
            session(stored).setUpdatedAt(now);
            save(stored);
            checkLater(stored);
        });
    }

    // Changes the session, under the service's lock, where it is in state.
    private void inState(String sessionId, String state, Consumer<R> change) {
        locked(() -> {
            find(sessionId).filter(stored -> state.equals(session(stored).getState())).ifPresent(change);
            return null;
        });
    }
}
