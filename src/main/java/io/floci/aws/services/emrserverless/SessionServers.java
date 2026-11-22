package io.floci.aws.services.emrserverless;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.SparkConnectProxy;
import io.floci.aws.core.common.SparkConnectTargets.Route;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The servers a service runs for its sessions in its docker runner mode, which a client reaches through a proxy of
 * Floci's with a token the service gave: EMR Serverless's and EMR's Spark Connect servers, reached through
 * {@link SparkConnectProxy} with a token GetSessionEndpoint gave. Each is a container the runner runs as a job's driver
 * ({@link SparkJobRunner}). It runs each server, asks it whether it answers once its container runs, keeps the tokens
 * given for its session, counts the calls it takes, and keeps one check for its session's next deadline. The service,
 * its {@link Owner}, holds each session and its state, and is told of each step as the session's account and Region,
 * never while this class holds its own lock. Its {@link Kind} names what the servers are in its answers and its log.
 */
public final class SessionServers {

    private static final Logger LOG = Logger.getLogger(SessionServers.class);

    /** How long a server may take to answer once its container runs. */
    public static final Duration START_LIMIT = Duration.ofMinutes(10);
    private static final long PROBE_INTERVAL_MILLIS = 500;

    /**
     * What a service's servers are, in the answers to the calls they cannot take and in Floci's log: what one is
     * called, what each serves, and the operation that gives the tokens that reach them. No source gives AWS's
     * wording for those answers; it is Floci's.
     */
    public record Kind(String server, String holder, String tokenOperation) {

        /** EMR Serverless's and EMR's: a session's Spark Connect server, reached with GetSessionEndpoint's tokens. */
        public static final Kind SPARK_CONNECT = new Kind("Spark Connect server", "Session", "GetSessionEndpoint");

        /** The answer to a call whose token has expired. */
        public String tokenExpired() {
            return "The authentication token has expired. Call " + tokenOperation + " for a new one.";
        }

        /** The answer to a call whose session has no server running. */
        public String noRunningServer(String sessionId) {
            return holder + " " + sessionId + " has no " + server + " running.";
        }
    }

    /** The service whose sessions these are. Each method is called as the session's account and Region. */
    public interface Owner {

        /** The session's server's container runs. */
        void running(String sessionId);

        /** The session's server answers: it takes calls from now. */
        void answering(String sessionId);

        /** The session's server did not answer within {@link #START_LIMIT}, as {@code why} says. */
        void silent(String sessionId, String why);

        /**
         * The session's server has stopped, as {@code outcome} says; its tokens and its check go once this returns.
         * Whether that failed the session is the owner's to say.
         */
        void stopped(String sessionId, SparkJobRunner.Outcome outcome);

        /** Whether the session waits for its server to answer. */
        boolean starting(String sessionId);

        /** Whether the session's server may still run: the session has not ended, nor is ending. */
        boolean going(String sessionId);

        /** Why the session takes no calls now, or null while it does. */
        String refusal(String sessionId);

        /** The session takes a call, where it took none. */
        void busy(String sessionId);

        /** The session's last call in progress has ended. */
        void idle(String sessionId);

        /** The session's server, stopped, is no longer this class's: its entry, tokens and check have gone. */
        default void gone(String sessionId) {
        }
    }

    /**
     * What a server's thread does before its job runs, and after: makes what the job needs first, as a cluster's
     * running storage, and gives back what that took once the job has run or failed to.
     */
    public interface Preparation {

        /** The job as it runs, once what it needs is made; {@code cancelled} says whether to stop making it. */
        SparkJobRunner.Job prepare(SparkJobRunner.Job job, BooleanSupplier cancelled);

        /** Gives back what {@link #prepare} took. */
        default void release() {
        }
    }

    /** Why no server answers at a host and port, or null once one does. */
    @FunctionalInterface
    public interface Probe {
        String unanswered(String host, int port);
    }

    private final Kind kind;
    private final SparkJobRunner runner;
    private final Executor executor;
    private final ScheduledExecutorService checks;
    private final Probe probe;
    private final Clock clock;
    // The sessions whose server a thread of this process runs; guarded by this.
    private final Map<String, Server> servers = new HashMap<>();
    // The authentication tokens the service gave, until they expire or their session ends.
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    // Raised by each reset, so a server's thread from before it tells its owner nothing more; guarded by this.
    private long generation;

    /**
     * Servers of {@code kind} run by {@code runner} (null where none runs, as in the immediate runner mode, where
     * tokens are still kept) on {@code executor}'s threads, their sessions' deadlines checked on {@code checks} (null
     * for none).
     */
    public SessionServers(Kind kind, SparkJobRunner runner, Executor executor, ScheduledExecutorService checks,
                          Probe probe, Clock clock) {
        this.kind = kind;
        this.runner = runner;
        this.executor = executor;
        this.checks = checks;
        this.probe = probe;
        this.clock = clock;
    }

    /** One thread that runs sessions' checks; a check a later one replaces leaves its queue at once. */
    public static ScheduledExecutorService checkThread(String name) {
        ScheduledThreadPoolExecutor thread = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name(name).factory());
        thread.setRemoveOnCancelPolicy(true);
        return thread;
    }

    /** For a Spark Connect server: {@link SparkConnectProxy#unanswered}, waited for at most ten seconds. */
    public static Probe sparkConnectProbe(SparkConnectProxy proxy) {
        return (host, port) -> {
            try {
                return proxy.unanswered(host, port).toCompletionStage().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            } catch (Exception e) {
                LOG.debugv(e, "Probing the Spark Connect server at {0}:{1} failed", host, port);
                return e.toString();
            }
        };
    }

    /** Whether a thread of this process runs the session's server. */
    public synchronized boolean runs(String sessionId) {
        return servers.containsKey(sessionId);
    }

    /** Runs the session's server, {@code job}, on a thread of its own until it stops, telling {@code owner}. */
    public void launch(SparkJobRunner.Job job, Owner owner) {
        launch(job, (given, cancelled) -> given, owner);
    }

    /** {@link #launch(SparkJobRunner.Job, Owner)}, with {@code preparation} run on the thread first. */
    public void launch(SparkJobRunner.Job job, Preparation preparation, Owner owner) {
        long launchedIn;
        synchronized (this) {
            servers.put(job.runId(), new Server(job.accountId(), job.region()));
            launchedIn = generation;
        }
        executor.execute(() -> run(job, preparation, owner, launchedIn));
    }

    /**
     * Stops the session's server, where one runs; its tokens and its check go now, and its owner is told once it has
     * stopped.
     */
    public void stop(String sessionId) {
        boolean running;
        synchronized (this) {
            Server server = servers.get(sessionId);
            running = server != null;
            if (running) {
                // No call starts on it from now, whatever its owner said of its session a moment before.
                server.stopping = true;
                cancelCheck(server);
            }
        }
        dropGrants(sessionId);
        if (running) {
            runner.stop(sessionId);
        }
    }

    /** Keeps a token the service gave for the session, until {@code expiresAt}. */
    public void grant(String token, String sessionId, String accountId, String region, Instant expiresAt) {
        Instant now = clock.instant();
        grants.values().removeIf(grant -> !now.isBefore(grant.expiresAt()));
        grants.put(token, new Grant(accountId, region, sessionId, expiresAt));
    }

    /** Drops every token given for the session. */
    public void dropGrants(String sessionId) {
        grants.values().removeIf(grant -> grant.sessionId().equals(sessionId));
    }

    /**
     * Where a call carrying {@code token} goes: empty for a token none of these sessions has; refused for an expired
     * one, or while {@code owner} says its session takes no calls; else its server, the call counting as one the
     * session takes until it ends.
     */
    public Optional<Route> route(String token, Owner owner) {
        Grant grant = grants.get(token);
        if (grant == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(grant.expiresAt())) {
            grants.remove(token);
            return Optional.of(Route.refused(Route.UNAUTHENTICATED, kind.tokenExpired()));
        }
        return Optional.of(RequestScopes.callAs(grant.accountId(), grant.region(), () -> callStarted(grant, owner)));
    }

    /** Checks the session at {@code at} by {@code check}, run as its account and Region, in place of any it had. */
    public void checkAt(String sessionId, Instant at, Runnable check) {
        if (checks == null) {
            return;
        }
        synchronized (this) {
            Server server = servers.get(sessionId);
            if (server == null) {
                return;
            }
            cancelCheck(server);
            server.check = checks.schedule(() -> RequestScopes.runAs(server.accountId, server.region, check),
                    Math.max(0, Duration.between(clock.instant(), at).toMillis()), TimeUnit.MILLISECONDS);
        }
    }

    /** Cancels the session's check, where it has one. */
    public synchronized void cancelCheck(String sessionId) {
        Server server = servers.get(sessionId);
        if (server != null) {
            cancelCheck(server);
        }
    }

    /** A reset has begun: the servers' threads tell their owner nothing more, and every token goes. */
    public synchronized void reset() {
        generation++;
        servers.values().forEach(SessionServers::cancelCheck);
        servers.clear();
        grants.clear();
    }

    public void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
        if (checks != null) {
            checks.shutdownNow();
        }
    }

    /**
     * A call starts on the session's server, unless its owner refuses it or the server is being stopped, which is read
     * under the lock {@link #stop} marks it under. The count and the owner's busy go together under the server's own
     * notifications lock, so a call's busy cannot reach the owner before the idle of the call that ended before it.
     */
    private Route callStarted(Grant grant, Owner owner) {
        String sessionId = grant.sessionId();
        String refusal = owner.refusal(sessionId);
        if (refusal != null) {
            return Route.refused(Route.UNAVAILABLE, refusal);
        }
        Server server;
        synchronized (this) {
            server = servers.get(sessionId);
            if (server == null) {
                return Route.refused(Route.UNAVAILABLE, kind.noRunningServer(sessionId));
            }
        }
        synchronized (server.notifications) {
            boolean first;
            String host;
            int port;
            long launchedIn;
            synchronized (this) {
                if (server.stopping || server.host == null || servers.get(sessionId) != server) {
                    return Route.refused(Route.UNAVAILABLE, kind.noRunningServer(sessionId));
                }
                first = server.calls++ == 0;
                host = server.host;
                port = server.port;
                launchedIn = generation;
            }
            if (first) {
                owner.busy(sessionId);
            }
            return Route.to(host, port, () -> RequestScopes.runAs(grant.accountId(), grant.region(),
                    () -> callEnded(sessionId, server, owner, launchedIn)));
        }
    }

    // A call has ended: the last one in progress makes its session idle, in order with the calls' busy.
    private void callEnded(String sessionId, Server server, Owner owner, long launchedIn) {
        synchronized (server.notifications) {
            boolean last;
            synchronized (this) {
                if (launchedIn != generation || servers.get(sessionId) != server || server.calls == 0) {
                    return;
                }
                last = --server.calls == 0;
            }
            if (last) {
                owner.idle(sessionId);
            }
        }
    }

    private void run(SparkJobRunner.Job job, Preparation preparation, Owner owner, long launchedIn) {
        SparkJobRunner.Outcome outcome;
        ServerProgress progress = new ServerProgress(job, owner, launchedIn);
        try {
            outcome = runner.run(preparation.prepare(job, progress::cancelled), progress);
        } catch (RuntimeException e) {
            if (progress.cancelled() || Thread.currentThread().isInterrupted()) {
                // An end, a reset or Floci's shutdown cut its start short.
                outcome = SparkJobRunner.Outcome.halted();
            } else {
                LOG.warnv(e, "The {0} of {1} {2} could not run", kind.server(), kind.holder(), job.runId());
                outcome = SparkJobRunner.Outcome.couldNotStart(e.getMessage() != null ? e.getMessage()
                        : e.toString());
            }
        } finally {
            try {
                preparation.release();
            } catch (RuntimeException e) {
                LOG.warnv(e, "What the {0} of {1} {2} took could not all be given back", kind.server(),
                        kind.holder(), job.runId());
            }
        }
        if (!current(launchedIn)) {
            return;
        }
        String sessionId = job.runId();
        SparkJobRunner.Outcome ended = outcome;
        try {
            // The owner first, while the server is still here: a session read in between would otherwise look like one
            // whose server a restart left behind.
            RequestScopes.runAs(job.accountId(), job.region(), () -> owner.stopped(sessionId, ended));
        } catch (RuntimeException e) {
            LOG.warnv(e, "{0} {1} could not be told its {2} stopped", kind.holder(), sessionId, kind.server());
        } finally {
            synchronized (this) {
                if (launchedIn == generation) {
                    Server server = servers.remove(sessionId);
                    if (server != null) {
                        cancelCheck(server);
                    }
                }
            }
            dropGrants(sessionId);
        }
        try {
            RequestScopes.runAs(job.accountId(), job.region(), () -> owner.gone(sessionId));
        } catch (RuntimeException e) {
            LOG.warnv(e, "{0} {1} could not be told its {2} has gone", kind.holder(), sessionId, kind.server());
        }
    }

    // Asks the server whether it answers until it does, its session stops starting, or its time to start is up.
    private void await(SparkJobRunner.Job job, Owner owner, String host, int port, long launchedIn) {
        String sessionId = job.runId();
        long giveUp = System.nanoTime() + START_LIMIT.toNanos();
        while (current(launchedIn)
                && RequestScopes.callAs(job.accountId(), job.region(), () -> owner.starting(sessionId))) {
            String unanswered = probe.unanswered(host, port);
            if (unanswered == null) {
                RequestScopes.runAs(job.accountId(), job.region(), () -> owner.answering(sessionId));
                return;
            }
            if (System.nanoTime() - giveUp >= 0) {
                LOG.warnv("The {0} of {1} {2} did not answer: {3}", kind.server(), kind.holder(), sessionId,
                        unanswered);
                RequestScopes.runAs(job.accountId(), job.region(), () -> owner.silent(sessionId, unanswered));
                stop(sessionId);
                return;
            }
            try {
                Thread.sleep(PROBE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized boolean current(long launchedIn) {
        return launchedIn == generation;
    }

    private synchronized boolean listening(String sessionId, String host, int port, long launchedIn) {
        Server server = servers.get(sessionId);
        if (launchedIn != generation || server == null) {
            return false;
        }
        server.host = host;
        server.port = port;
        return true;
    }

    private static void cancelCheck(Server server) {
        if (server.check != null) {
            server.check.cancel(false);
            server.check = null;
        }
    }

    // A session's running server: its account and Region, where Floci reaches it once its container runs, how many
    // calls it is taking, whether it is being stopped, the check for its session's next deadline, and the lock its
    // calls' busy and idle reach its owner under, in order.
    private static final class Server {
        final String accountId;
        final String region;
        final Object notifications = new Object();
        String host;
        int port;
        int calls;
        boolean stopping;
        ScheduledFuture<?> check;

        Server(String accountId, String region) {
            this.accountId = accountId;
            this.region = region;
        }
    }

    // What an authentication token was given for: a session, in its account and Region, until it expires.
    private record Grant(String accountId, String region, String sessionId, Instant expiresAt) {
    }

    /** What the thread running a session's server tells its owner, and asks it, as that session. */
    private final class ServerProgress implements SparkJobRunner.Progress {

        private final SparkJobRunner.Job job;
        private final Owner owner;
        private final long launchedIn;

        ServerProgress(SparkJobRunner.Job job, Owner owner, long launchedIn) {
            this.job = job;
            this.owner = owner;
            this.launchedIn = launchedIn;
        }

        @Override
        public void started() {
            if (current(launchedIn)) {
                RequestScopes.runAs(job.accountId(), job.region(), () -> owner.running(job.runId()));
            }
        }

        @Override
        public void listening(String host, int port) {
            if (SessionServers.this.listening(job.runId(), host, port, launchedIn)) {
                executor.execute(() -> await(job, owner, host, port, launchedIn));
            }
        }

        @Override
        public void timedOut() {
            // The run's outcome says so, which the owner reads.
        }

        @Override
        public boolean cancelled() {
            return !current(launchedIn)
                    || !RequestScopes.callAs(job.accountId(), job.region(), () -> owner.going(job.runId()));
        }
    }
}
