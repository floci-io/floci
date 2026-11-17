package io.floci.aws.services.emrserverless;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Html;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.emrserverless.model.JobRun;
import io.floci.aws.services.emrserverless.model.Session;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The page a {@code GetDashboardForJobRun} or {@code GetResourceDashboard} link opens
 * ({@link EmrServerlessDashboards}). EMR Serverless opens the run's live Spark or Tez UI while it runs and its Spark
 * History Server or persistent Tez UI once it has ended, and a session's live Spark UI or Spark History Server
 * likewise; Floci keeps neither, so the page shows the attempt as GetJobRun gives it and where its driver's logs go, as
 * it was admitted, or the session as GetSession gives it. A link that is not one of this process's, or whose hour has
 * passed, opens a refusal instead, and so does one whose run or session has gone. Like the rest of Floci the page takes
 * no AWS credentials: the link is all it asks for.
 */
@Path(EmrServerlessDashboards.PATH)
public class EmrServerlessDashboardController {

    // No source gives AWS's wording for these; they are Floci's.
    static final String INVALID = "This dashboard link is not valid, or its hour has passed. Call"
            + " GetDashboardForJobRun or GetResourceDashboard for a new one.";
    static final String NO_UI = "Floci keeps no Spark UI, Tez UI or Spark History Server for a job run, so this page"
            + " stands in for the application UI EMR Serverless opens. The driver's output is in its logs.";
    static final String NO_SESSION_UI = "Floci keeps no Spark UI or Spark History Server for a session, and runs no"
            + " Spark driver for one, so this page stands in for the application UI EMR Serverless opens.";

    private final EmrServerlessDashboards dashboards;
    private final EmrServerlessJobRunService jobRuns;
    private final EmrServerlessSessionService sessions;

    @Inject
    public EmrServerlessDashboardController(EmrServerlessDashboards dashboards, EmrServerlessJobRunService jobRuns,
                                            EmrServerlessSessionService sessions) {
        this.dashboards = dashboards;
        this.jobRuns = jobRuns;
        this.sessions = sessions;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response dashboard(@QueryParam("account") String accountId, @QueryParam("region") String region,
                              @QueryParam("application") String applicationId,
                              @QueryParam("jobRun") String jobRunId, @QueryParam("session") String sessionId,
                              @QueryParam("attempt") String attempt, @QueryParam("expires") String expires,
                              @QueryParam("signature") String signature) {
        EmrServerlessDashboards.Link link = dashboards.verified(accountId, region, applicationId, jobRunId, sessionId,
                attempt, expires, signature);
        if (link == null) {
            return page(403, sessionId != null ? "Session dashboard" : "Job run dashboard",
                    List.of("<p>" + Html.escape(INVALID) + "</p>"));
        }
        if (link.sessionId() != null) {
            Session session;
            try {
                session = RequestScopes.callAs(link.accountId(), link.region(),
                        () -> sessions.getSession(link.applicationId(), link.sessionId()));
            } catch (AwsException e) {
                return page(404, "Session " + link.sessionId(), List.of("<p>" + Html.escape(e.getMessage()) + "</p>"));
            }
            return page(200, "Session " + link.sessionId(), body(session));
        }
        EmrServerlessJobRunService.DashboardView view;
        try {
            view = RequestScopes.callAs(link.accountId(), link.region(),
                    () -> jobRuns.dashboard(link.applicationId(), link.jobRunId(), link.attempt()));
        } catch (AwsException e) {
            return page(404, "Job run " + link.jobRunId(), List.of("<p>" + Html.escape(e.getMessage()) + "</p>"));
        }
        return page(200, "Job run " + link.jobRunId(), body(view));
    }

    private static List<String> body(EmrServerlessJobRunService.DashboardView view) {
        JobRun run = view.run();
        List<String> lines = new ArrayList<>();
        lines.add("<table>");
        Html.row(lines, "Application", run.getApplicationId());
        Html.row(lines, "Job run", run.getJobRunId());
        Html.row(lines, "Name", run.getName());
        Html.row(lines, "Attempt", run.getAttempt() != null ? String.valueOf(run.getAttempt()) : null);
        Html.row(lines, "State", run.getState());
        Html.row(lines, "State details", run.getStateDetails());
        Html.row(lines, "Release label", run.getReleaseLabel());
        Html.row(lines, "Created", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        Html.row(lines, "Updated", run.getUpdatedAt() != null ? run.getUpdatedAt().toString() : null);
        Html.row(lines, "Driver logs in S3", view.logs().s3Folder());
        Html.row(lines, "Driver logs in CloudWatch Logs", view.logs().logGroup());
        Html.row(lines, "Driver stdout stream", view.logs().stdoutStream());
        Html.row(lines, "Driver stderr stream", view.logs().stderrStream());
        lines.add("</table>");
        lines.add("<p>" + Html.escape(NO_UI) + "</p>");
        return lines;
    }

    private static List<String> body(Session session) {
        List<String> lines = new ArrayList<>();
        lines.add("<table>");
        Html.row(lines, "Application", session.getApplicationId());
        Html.row(lines, "Session", session.getSessionId());
        Html.row(lines, "Name", session.getName());
        Html.row(lines, "State", session.getState());
        Html.row(lines, "State details", session.getStateDetails());
        Html.row(lines, "Release label", session.getReleaseLabel());
        Html.row(lines, "Created", text(session.getCreatedAt()));
        Html.row(lines, "Started", text(session.getStartedAt()));
        Html.row(lines, "Idle since", text(session.getIdleSince()));
        Html.row(lines, "Ended", text(session.getEndedAt()));
        lines.add("</table>");
        lines.add("<p>" + Html.escape(NO_SESSION_UI) + "</p>");
        return lines;
    }

    private static String text(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static Response page(int status, String title, List<String> body) {
        return Response.status(status).type(MediaType.TEXT_HTML).entity(Html.page(title, body)).build();
    }
}
