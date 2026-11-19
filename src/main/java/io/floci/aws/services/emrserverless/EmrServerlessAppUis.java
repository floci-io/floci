package io.floci.aws.services.emrserverless;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AppUiHost;
import io.floci.aws.core.common.AppUiRoute;
import io.floci.aws.core.common.AppUiRoutes;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.emrserverless.model.JobRun;
import io.floci.aws.services.emrserverless.model.Session;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The application UIs a {@code GetDashboardForJobRun} or {@code GetResourceDashboard} link opens. "For jobs in a
 * running state, the application UI is a live user interface such as the Spark or Tez web UI. For completed jobs, the
 * application UI is a persistent application user interface such as the Spark History Server", and a session's
 * likewise. In the docker runner mode, where Floci runs a Spark run's driver, or a session's Spark Connect server, in a
 * container, a link given before it has ended opens that driver's Spark UI for real, and one given after, for the 30
 * days its event logs are kept ({@link EmrServerlessHistories}), a Spark History Server reading them; each on a host of
 * its own under {@link #UI_HOST} ({@link AppUiHost}), which Floci's application UI proxy sends on to the container, on
 * a network where that container has an address of its own. The secret in the host is the link's authority, as no
 * request to the UI carries AWS credentials, and is valid for {@link EmrServerlessDashboards#VALIDITY}, as "The URL is
 * valid for one hour after you generate it". Any other link, a Hive run's, that of a run whose logs are not in managed
 * storage, one given while a session is ending or once a history has gone, or any in the immediate runner mode, opens a
 * page of Floci's own about it ({@link EmrServerlessDashboards}). It also keeps the histories: the container serving
 * one stops once no link to it is valid, and a history goes once its 30 days have passed or its run or session has
 * gone, which it checks as Floci starts and every hour; a reset removes every history, a shutdown the containers
 * serving them.
 */
@ApplicationScoped
public class EmrServerlessAppUis implements AppUiRoutes, Resettable, ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(EmrServerlessAppUis.class);

    /** The label under Floci's host name a driver's UI's own host is in. */
    static final String UI_HOST = "emr-serverless-ui";
    // No source gives AWS's wording for these; they are Floci's.
    static final String NO_SUCH_INTERFACE = "This link opens none of the driver's interfaces on that port.";
    static final String NOT_RUNNING = "The driver is not running yet. Try again in a moment.";
    static final String NOT_ANSWERING = "The driver's Spark UI is not answering yet: it starts with the driver's Spark,"
            + " unless the run turns it off (spark.ui.enabled). Try again in a moment.";
    static final String RUN_ENDED = "Attempt %d of job run %s is no longer running, and its live Spark UI is there only"
            + " while it runs.";
    static final String SESSION_ENDED = "Session %s has ended, and its live Spark UI is there only while it is active.";
    static final String HISTORY_NOT_READY = "The Spark History Server of the kept event logs is starting. Try again in"
            + " a moment.";
    static final String HISTORY_GONE = "The history of %s is no longer kept: it is kept for 30 days after it ends.";
    private static final Set<String> SESSION_ENDING = Set.of(EmrServerlessSessionService.STATE_TERMINATING,
            EmrServerlessSessionService.STATE_TERMINATED, EmrServerlessSessionService.STATE_FAILED);
    private static final int HISTORY_PORT = EmrServerlessSparkRunner.SPARK_HISTORY_WEB_PORT;
    // How long a history server's address is used before it is asked for again, rather than at each of its pages'
    // requests: a container gone since may have left its port to another.
    private static final Duration ENDPOINT_RECHECK = Duration.ofSeconds(10);
    // How long after a history link has expired the container serving it is checked, and how often the histories are.
    private static final Duration REAP_MARGIN = Duration.ofMinutes(1);
    private static final Duration SWEEP_INTERVAL = Duration.ofHours(1);

    private final EmrServerlessDashboards dashboards;
    private final EmrServerlessJobRunService jobRuns;
    private final EmrServerlessSessionService sessions;
    private final EmrServerlessHistories histories;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final String baseUrl;
    // The links to drivers' UIs and to kept histories this process has given, by their secret, until they expire.
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    // Where a history link's server was found, and when, by the link's secret.
    private final Map<String, Found> found = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    // Checks, as history links expire and every hour, the containers and histories that may go; made on first use.
    private ScheduledExecutorService reaper;

    @Inject
    public EmrServerlessAppUis(EmrServerlessDashboards dashboards, EmrServerlessJobRunService jobRuns,
                               EmrServerlessSessionService sessions, EmrServerlessHistories histories,
                               RegionResolver regionResolver, EmulatorConfig config, Clock clock) {
        this(dashboards, jobRuns, sessions, histories, regionResolver, config.effectiveBaseUrl(), clock);
    }

    EmrServerlessAppUis(EmrServerlessDashboards dashboards, EmrServerlessJobRunService jobRuns,
                        EmrServerlessSessionService sessions, EmrServerlessHistories histories,
                        RegionResolver regionResolver, String baseUrl, Clock clock) {
        this.dashboards = dashboards;
        this.jobRuns = jobRuns;
        this.sessions = sessions;
        this.histories = histories;
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** A link given: what it names, and whether it opens the kept history rather than the live UI. */
    private record Grant(EmrServerlessDashboards.Link link, boolean history) {

        String runId() {
            return link.jobRunId() != null ? link.jobRunId() : link.sessionId();
        }
    }

    // Where Floci found the server of a history, and when.
    private record Found(ContainerLifecycleManager.EndpointInfo endpoint, Instant at) {
    }

    /**
     * The link GetDashboardForJobRun gives for a run's attempt, which must be there, for a Spark run in the docker
     * runner mode whose logs are in managed storage: its driver's live Spark UI while the attempt has not ended, then
     * its kept history's Spark History Server; else Floci's page about the attempt.
     */
    public String jobRunUrl(String applicationId, String jobRunId, int attempt) {
        EmrServerlessJobRunService.DashboardView view = jobRuns.dashboard(applicationId, jobRunId, attempt);
        JobRun run = view.run();
        EmrServerlessDashboards.Link link = new EmrServerlessDashboards.Link(regionResolver.getAccountId(),
                regionResolver.getRegion(), applicationId, jobRunId, null, attempt, expiry());
        if (jobRuns.servesDriverUis() && run.getJobDriver() != null && run.getJobDriver().getSparkSubmit() != null
                && view.logs().inManagedStorage()) {
            if (!EmrServerlessJobRunService.isTerminal(run)) {
                return url(new Grant(link, false));
            }
            if (historyKept(jobRunId, run.getEndedAt())) {
                return url(new Grant(link, true));
            }
        }
        return dashboards.url(link.accountId(), link.region(), applicationId, jobRunId, attempt);
    }

    /**
     * The link GetResourceDashboard gives for a session, in the docker runner mode: its Spark Connect server's live
     * Spark UI while it has not ended or begun to, then its kept history's Spark History Server; else Floci's page
     * about the session.
     */
    public String sessionUrl(String applicationId, Session session) {
        EmrServerlessDashboards.Link link = new EmrServerlessDashboards.Link(regionResolver.getAccountId(),
                regionResolver.getRegion(), applicationId, null, session.getSessionId(), 0, expiry());
        if (jobRuns.servesDriverUis()) {
            if (!SESSION_ENDING.contains(session.getState())) {
                return url(new Grant(link, false));
            }
            if (!EmrServerlessSessionService.STATE_TERMINATING.equals(session.getState())
                    && historyKept(session.getSessionId(), session.getEndedAt())) {
                return url(new Grant(link, true));
            }
        }
        return dashboards.sessionUrl(link.accountId(), link.region(), applicationId, session.getSessionId());
    }

    // Within its 30 days, and kept; where Docker cannot tell, the link is given, its host asking to try again until
    // Docker can.
    private boolean historyKept(String runId, Instant endedAt) {
        if (endedAt == null || !clock.instant().isBefore(endedAt.plus(EmrServerlessHistories.KEPT))) {
            return false;
        }
        try {
            return histories.kept(runId);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not tell whether the history of EMR Serverless run {0} is kept: {1}", runId,
                    e.getMessage());
            return true;
        }
    }

    private synchronized String url(Grant grant) {
        long now = clock.instant().getEpochSecond();
        grants.entrySet().removeIf(entry -> {
            boolean expired = now >= entry.getValue().link().expires();
            if (expired) {
                found.remove(entry.getKey());
            }
            return expired;
        });
        String secret = AppUiHost.newSecret(random);
        grants.put(secret, grant);
        if (grant.history()) {
            reapLater(grant);
        }
        return AppUiHost.url(baseUrl, secret, grant.history() ? HISTORY_PORT : SparkExecutors.DRIVER_UI_PORT,
                UI_HOST, "/");
    }

    private long expiry() {
        return clock.instant().plus(EmrServerlessDashboards.VALIDITY).getEpochSecond();
    }

    @Override
    public String hostLabel() {
        return UI_HOST;
    }

    @Override
    public String service() {
        return EmrServerlessJobRunService.SERVICE;
    }

    /**
     * The route of a request whose {@code Host} is {@code hostHeader}: to the driver's Spark UI while the run's
     * attempt, or the session, has it running, or to its kept history's Spark History Server; null where the host is
     * not a driver's UI's, {@code <secret>-<port>.}{@value #UI_HOST}{@code .<Floci's host>}.
     */
    @Override
    public AppUiRoute route(String hostHeader, String scheme) {
        AppUiHost host = AppUiHost.parse(hostHeader, UI_HOST);
        if (host == null) {
            return null;
        }
        Grant grant = grants.get(host.secret());
        if (grant == null || clock.instant().getEpochSecond() >= grant.link().expires()) {
            return AppUiRoute.refused(403, EmrServerlessDashboardController.INVALID);
        }
        if (host.port() != (grant.history() ? HISTORY_PORT : SparkExecutors.DRIVER_UI_PORT)) {
            return AppUiRoute.refused(404, NO_SUCH_INTERFACE);
        }
        EmrServerlessDashboards.Link link = grant.link();
        try {
            return RequestScopes.callAs(link.accountId(), link.region(), () -> grant.history()
                    ? historyRoute(host.secret(), grant)
                    : link.jobRunId() != null ? runRoute(link) : sessionRoute(link));
        } catch (AwsException e) {
            // The run, its application or the session has gone.
            return AppUiRoute.refused(404, e.getMessage());
        }
    }

    private AppUiRoute runRoute(EmrServerlessDashboards.Link link) {
        JobRun run = jobRuns.dashboard(link.applicationId(), link.jobRunId(), link.attempt()).run();
        if (EmrServerlessJobRunService.isTerminal(run)) {
            return AppUiRoute.refused(404, String.format(RUN_ENDED, link.attempt(), link.jobRunId()));
        }
        return driverUi(link.jobRunId()).orElseGet(() -> AppUiRoute.refused(503, NOT_RUNNING));
    }

    // A session that is ending may still have its server running: its UI goes with the server.
    private AppUiRoute sessionRoute(EmrServerlessDashboards.Link link) {
        Session session = sessions.getSession(link.applicationId(), link.sessionId());
        boolean ended = !EmrServerlessSessionService.STATE_TERMINATING.equals(session.getState())
                && SESSION_ENDING.contains(session.getState());
        Optional<AppUiRoute> driver = ended ? Optional.empty() : driverUi(link.sessionId());
        if (driver.isPresent()) {
            return driver.get();
        }
        return SESSION_ENDING.contains(session.getState())
                ? AppUiRoute.refused(404, String.format(SESSION_ENDED, link.sessionId()))
                : AppUiRoute.refused(503, NOT_RUNNING);
    }

    private Optional<AppUiRoute> driverUi(String runId) {
        SparkJobRunner runner = jobRuns.containerRunner();
        Optional<ContainerLifecycleManager.EndpointInfo> endpoint = runner != null ? runner.driverUi(runId)
                : Optional.empty();
        // Its container runs before its Spark has started its UI.
        return endpoint.map(found -> AppUiRoute.to(found.host(), found.port(), 503, NOT_ANSWERING));
    }

    // To the server of a kept history, within its 30 days, which starts where it is not running.
    private AppUiRoute historyRoute(String secret, Grant grant) {
        EmrServerlessDashboards.Link link = grant.link();
        Instant endedAt;
        String releaseLabel;
        if (link.jobRunId() != null) {
            JobRun run = jobRuns.dashboard(link.applicationId(), link.jobRunId(), link.attempt()).run();
            endedAt = run.getEndedAt();
            releaseLabel = run.getReleaseLabel();
        } else {
            Session session = sessions.getSession(link.applicationId(), link.sessionId());
            endedAt = session.getEndedAt();
            releaseLabel = session.getReleaseLabel();
        }
        Instant now = clock.instant();
        String what = (link.jobRunId() != null ? "job run " : "session ") + grant.runId();
        if (endedAt == null || !now.isBefore(endedAt.plus(EmrServerlessHistories.KEPT))) {
            return AppUiRoute.refused(404, String.format(HISTORY_GONE, what));
        }
        Found server = found.get(secret);
        if (server == null || !now.isBefore(server.at().plus(ENDPOINT_RECHECK))) {
            boolean kept;
            try {
                kept = histories.kept(grant.runId());
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not tell whether the history of EMR Serverless run {0} is kept: {1}",
                        grant.runId(), e.getMessage());
                return AppUiRoute.refused(503, HISTORY_NOT_READY);
            }
            if (!kept) {
                return AppUiRoute.refused(404, String.format(HISTORY_GONE, what));
            }
            Optional<ContainerLifecycleManager.EndpointInfo> endpoint = histories.server(grant.runId(),
                    link.accountId(), link.region(), jobRuns.historyImage(releaseLabel), jobRuns.dockerNetwork());
            if (endpoint.isEmpty()) {
                found.remove(secret);
                return AppUiRoute.refused(503, HISTORY_NOT_READY);
            }
            server = new Found(endpoint.get(), now);
            found.put(secret, server);
        }
        Found routed = server;
        return new AppUiRoute(server.endpoint().host(), server.endpoint().port(), Map.of(),
                () -> found.remove(secret, routed), 0, null, 503, HISTORY_NOT_READY);
    }

    // Checks, once a history link has expired, whether the container serving its history may stop.
    private synchronized void reapLater(Grant grant) {
        long delay = Duration.between(clock.instant(), Instant.ofEpochSecond(grant.link().expires()))
                .plus(REAP_MARGIN).toMillis();
        reaper().schedule(() -> reap(grant.runId()), delay, TimeUnit.MILLISECONDS);
    }

    private synchronized ScheduledExecutorService reaper() {
        if (reaper == null) {
            reaper = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("emr-serverless-history").factory());
        }
        return reaper;
    }

    /**
     * Stops the container serving a run's or session's kept history once no link to it is valid, and removes the
     * histories that have gone; under this bean's lock, as a link is given, so one given while this looks keeps its
     * container.
     */
    synchronized void reap(String runId) {
        long now = clock.instant().getEpochSecond();
        boolean linked = grants.values().stream().anyMatch(grant -> grant.history() && grant.runId().equals(runId)
                && now < grant.link().expires());
        if (!linked) {
            histories.stop(runId);
        }
        removeExpiredHistories();
    }

    /** Removes the kept histories whose 30 days have passed, or whose run or session has gone. */
    void removeExpiredHistories() {
        try {
            Instant cutoff = clock.instant().minus(EmrServerlessHistories.KEPT);
            Set<String> kept = new HashSet<>(jobRuns.historiesKept(cutoff));
            kept.addAll(sessions.historiesKept(cutoff));
            histories.removeAllBut(kept::contains);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the expired histories of EMR Serverless runs: {0}", e.getMessage());
        }
    }

    /**
     * As Floci starts, in the docker runner mode: the containers an earlier process left serving histories go, and the
     * histories that have gone, then every hour.
     */
    void onStart(@Observes StartupEvent ignored) {
        if (!jobRuns.servesDriverUis()) {
            return;
        }
        reaper().execute(() -> {
            histories.removeLeftoverServers();
            removeExpiredHistories();
        });
        reaper().scheduleAtFixedRate(this::removeExpiredHistories, SWEEP_INTERVAL.toMinutes(),
                SWEEP_INTERVAL.toMinutes(), TimeUnit.MINUTES);
    }

    @Override
    public void stopManagedContainers() {
        histories.stopServers();
    }

    /** A reset forgets every link and removes every kept history. */
    @Override
    public void clear() {
        grants.clear();
        found.clear();
        if (jobRuns.servesDriverUis()) {
            histories.removeAllBut(runId -> false);
        }
    }

    @PreDestroy
    synchronized void close() {
        if (reaper != null) {
            reaper.shutdownNow();
        }
    }
}
