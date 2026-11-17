package io.floci.aws.services.emrserverless;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Html;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.emrserverless.model.JobRun;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * The page a {@code GetDashboardForJobRun} link opens ({@link EmrServerlessDashboards}). EMR Serverless opens the run's
 * live Spark or Tez UI while it runs and its Spark History Server or persistent Tez UI once it has ended; Floci keeps
 * neither, so the page shows the attempt as GetJobRun gives it and where its driver's logs go, as it was admitted. A
 * link that is not one of this process's, or whose hour has passed, opens a refusal instead, and so does one whose run
 * has gone. Like the rest of Floci the page takes no AWS credentials: the link is all it asks for.
 */
@Path(EmrServerlessDashboards.PATH)
public class EmrServerlessDashboardController {

    // No source gives AWS's wording for these; they are Floci's.
    static final String INVALID = "This dashboard link is not valid, or its hour has passed. Call"
            + " GetDashboardForJobRun for a new one.";
    static final String NO_UI = "Floci keeps no Spark UI, Tez UI or Spark History Server for a job run, so this page"
            + " stands in for the application UI EMR Serverless opens. The driver's output is in its logs.";

    private final EmrServerlessDashboards dashboards;
    private final EmrServerlessJobRunService jobRuns;

    @Inject
    public EmrServerlessDashboardController(EmrServerlessDashboards dashboards, EmrServerlessJobRunService jobRuns) {
        this.dashboards = dashboards;
        this.jobRuns = jobRuns;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response dashboard(@QueryParam("account") String accountId, @QueryParam("region") String region,
                              @QueryParam("application") String applicationId,
                              @QueryParam("jobRun") String jobRunId, @QueryParam("attempt") String attempt,
                              @QueryParam("expires") String expires,
                              @QueryParam("signature") String signature) {
        EmrServerlessDashboards.Link link = dashboards.verified(accountId, region, applicationId, jobRunId, attempt,
                expires, signature);
        if (link == null) {
            return page(403, "Job run dashboard", List.of("<p>" + Html.escape(INVALID) + "</p>"));
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
        row(lines, "Application", run.getApplicationId());
        row(lines, "Job run", run.getJobRunId());
        row(lines, "Name", run.getName());
        row(lines, "Attempt", run.getAttempt() != null ? String.valueOf(run.getAttempt()) : null);
        row(lines, "State", run.getState());
        row(lines, "State details", run.getStateDetails());
        row(lines, "Release label", run.getReleaseLabel());
        row(lines, "Created", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        row(lines, "Updated", run.getUpdatedAt() != null ? run.getUpdatedAt().toString() : null);
        row(lines, "Driver logs in S3", view.logs().s3Folder());
        row(lines, "Driver logs in CloudWatch Logs", view.logs().logGroup());
        row(lines, "Driver stdout stream", view.logs().stdoutStream());
        row(lines, "Driver stderr stream", view.logs().stderrStream());
        lines.add("</table>");
        lines.add("<p>" + Html.escape(NO_UI) + "</p>");
        return lines;
    }

    // A row of the table, left out when the run has no such value.
    private static void row(List<String> lines, String name, String value) {
        if (value != null && !value.isEmpty()) {
            lines.add("<tr><th>" + Html.escape(name) + "</th><td>" + Html.escape(value) + "</td></tr>");
        }
    }

    private static Response page(int status, String title, List<String> body) {
        String html = "<!doctype html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + Html.escape(title)
                + "</title></head>\n<body>\n<h1>" + Html.escape(title) + "</h1>\n"
                + String.join("\n", body) + "\n</body></html>\n";
        return Response.status(status).type(MediaType.TEXT_HTML).entity(html).build();
    }
}
