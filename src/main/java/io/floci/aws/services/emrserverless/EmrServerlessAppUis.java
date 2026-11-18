package io.floci.aws.services.emrserverless;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AppUiHost;
import io.floci.aws.core.common.AppUiRoute;
import io.floci.aws.core.common.AppUiRoutes;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.emrserverless.model.JobRun;
import io.floci.aws.services.emrserverless.model.Session;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The application UIs a {@code GetDashboardForJobRun} or {@code GetResourceDashboard} link opens. "For jobs in a
 * running state, the application UI is a live user interface such as the Spark or Tez web UI", and "While a session is
 * active, the URL opens the live Apache Spark UI". In the docker runner mode, where Floci runs a Spark run's driver, or
 * a session's Spark Connect server, in a container, a link given before it has ended opens that driver's Spark UI for
 * real, on a host of its own under {@link #UI_HOST} ({@link AppUiHost}), which Floci's application UI proxy sends on
 * to the driver's container while it runs, on a network where that container has an address of its own. The secret in
 * the host is the link's authority, as no request to the UI carries AWS credentials, and is valid for
 * {@link EmrServerlessDashboards#VALIDITY}, as "The URL is valid for one hour after you generate it". Any other link, a
 * Hive run's, that of a run whose logs are not in managed storage, one given once the run or session has ended, or any
 * in the immediate runner mode, opens a page of Floci's own about it ({@link EmrServerlessDashboards}).
 */
@ApplicationScoped
public class EmrServerlessAppUis implements AppUiRoutes {

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
    private static final Set<String> SESSION_ENDING = Set.of(EmrServerlessSessionService.STATE_TERMINATING,
            EmrServerlessSessionService.STATE_TERMINATED, EmrServerlessSessionService.STATE_FAILED);

    private final EmrServerlessDashboards dashboards;
    private final EmrServerlessJobRunService jobRuns;
    private final EmrServerlessSessionService sessions;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final String baseUrl;
    // The links to drivers' UIs this process has given, by their secret, until they expire.
    private final Map<String, EmrServerlessDashboards.Link> grants = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Inject
    public EmrServerlessAppUis(EmrServerlessDashboards dashboards, EmrServerlessJobRunService jobRuns,
                               EmrServerlessSessionService sessions, RegionResolver regionResolver,
                               EmulatorConfig config, Clock clock) {
        this(dashboards, jobRuns, sessions, regionResolver, config.effectiveBaseUrl(), clock);
    }

    EmrServerlessAppUis(EmrServerlessDashboards dashboards, EmrServerlessJobRunService jobRuns,
                        EmrServerlessSessionService sessions, RegionResolver regionResolver, String baseUrl,
                        Clock clock) {
        this.dashboards = dashboards;
        this.jobRuns = jobRuns;
        this.sessions = sessions;
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * The link GetDashboardForJobRun gives for a run's attempt, which must be there: its driver's live Spark UI, for a
     * Spark run in the docker runner mode whose attempt has not ended and whose logs are in managed storage; else
     * Floci's page about the attempt.
     */
    public String jobRunUrl(String applicationId, String jobRunId, int attempt) {
        EmrServerlessJobRunService.DashboardView view = jobRuns.dashboard(applicationId, jobRunId, attempt);
        JobRun run = view.run();
        String accountId = regionResolver.getAccountId();
        String region = regionResolver.getRegion();
        boolean live = jobRuns.servesDriverUis() && run.getJobDriver() != null
                && run.getJobDriver().getSparkSubmit() != null && !EmrServerlessJobRunService.isTerminal(run)
                && view.logs().inManagedStorage();
        if (!live) {
            return dashboards.url(accountId, region, applicationId, jobRunId, attempt);
        }
        return driverUiUrl(new EmrServerlessDashboards.Link(accountId, region, applicationId, jobRunId, null, attempt,
                expiry()));
    }

    /**
     * The link GetResourceDashboard gives for a session: its Spark Connect server's live Spark UI, in the docker runner
     * mode while the session has not ended or begun to; else Floci's page about the session.
     */
    public String sessionUrl(String applicationId, Session session) {
        String accountId = regionResolver.getAccountId();
        String region = regionResolver.getRegion();
        if (!jobRuns.servesDriverUis() || SESSION_ENDING.contains(session.getState())) {
            return dashboards.sessionUrl(accountId, region, applicationId, session.getSessionId());
        }
        return driverUiUrl(new EmrServerlessDashboards.Link(accountId, region, applicationId, null,
                session.getSessionId(), 0, expiry()));
    }

    private String driverUiUrl(EmrServerlessDashboards.Link link) {
        long now = clock.instant().getEpochSecond();
        grants.values().removeIf(grant -> now >= grant.expires());
        String secret = AppUiHost.newSecret(random);
        grants.put(secret, link);
        return AppUiHost.url(baseUrl, secret, SparkExecutors.DRIVER_UI_PORT, UI_HOST, "/");
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
     * attempt, or the session, has it running; null where the host is not a driver's UI's, {@code
     * <secret>-<port>.}{@value #UI_HOST}{@code .<Floci's host>}.
     */
    @Override
    public AppUiRoute route(String hostHeader, String scheme) {
        AppUiHost host = AppUiHost.parse(hostHeader, UI_HOST);
        if (host == null) {
            return null;
        }
        EmrServerlessDashboards.Link link = grants.get(host.secret());
        if (link == null || clock.instant().getEpochSecond() >= link.expires()) {
            return AppUiRoute.refused(403, EmrServerlessDashboardController.INVALID);
        }
        if (host.port() != SparkExecutors.DRIVER_UI_PORT) {
            return AppUiRoute.refused(404, NO_SUCH_INTERFACE);
        }
        try {
            return RequestScopes.callAs(link.accountId(), link.region(),
                    () -> link.jobRunId() != null ? runRoute(link) : sessionRoute(link));
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
}
