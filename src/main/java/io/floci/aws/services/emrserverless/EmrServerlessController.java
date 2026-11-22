package io.floci.aws.services.emrserverless;

import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.SparkConnectEndpoint;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.ApplicationSummary;
import io.floci.aws.services.emrserverless.model.CancelJobRunResponse;
import io.floci.aws.services.emrserverless.model.CreateApplicationRequest;
import io.floci.aws.services.emrserverless.model.CreateApplicationResponse;
import io.floci.aws.services.emrserverless.model.DeleteApplicationResponse;
import io.floci.aws.services.emrserverless.model.GetApplicationResponse;
import io.floci.aws.services.emrserverless.model.GetDashboardForJobRunResponse;
import io.floci.aws.services.emrserverless.model.GetJobRunResponse;
import io.floci.aws.services.emrserverless.model.GetResourceDashboardResponse;
import io.floci.aws.services.emrserverless.model.GetSessionEndpointResponse;
import io.floci.aws.services.emrserverless.model.GetSessionResponse;
import io.floci.aws.services.emrserverless.model.JobRun;
import io.floci.aws.services.emrserverless.model.JobRunAttemptSummary;
import io.floci.aws.services.emrserverless.model.JobRunSummary;
import io.floci.aws.services.emrserverless.model.ListApplicationsRequest;
import io.floci.aws.services.emrserverless.model.ListApplicationsResponse;
import io.floci.aws.services.emrserverless.model.ListJobRunAttemptsResponse;
import io.floci.aws.services.emrserverless.model.ListJobRunsResponse;
import io.floci.aws.services.emrserverless.model.ListSessionsResponse;
import io.floci.aws.services.emrserverless.model.Session;
import io.floci.aws.services.emrserverless.model.SessionSummary;
import io.floci.aws.services.emrserverless.model.StartApplicationResponse;
import io.floci.aws.services.emrserverless.model.StartJobRunRequest;
import io.floci.aws.services.emrserverless.model.StartJobRunResponse;
import io.floci.aws.services.emrserverless.model.StartSessionRequest;
import io.floci.aws.services.emrserverless.model.StartSessionResponse;
import io.floci.aws.services.emrserverless.model.StopApplicationResponse;
import io.floci.aws.services.emrserverless.model.TerminateSessionResponse;
import io.floci.aws.services.emrserverless.model.UpdateApplicationRequest;
import io.floci.aws.services.emrserverless.model.UpdateApplicationResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/_emrserverless/applications")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EmrServerlessController {

    private final EmrServerlessService service;
    private final EmrServerlessJobRunService jobRuns;
    private final EmrServerlessSessionService sessions;
    private final EmrServerlessAppUis appUis;

    @Inject
    public EmrServerlessController(EmrServerlessService service, EmrServerlessJobRunService jobRuns,
                                   EmrServerlessSessionService sessions, EmrServerlessAppUis appUis) {
        this.service = service;
        this.jobRuns = jobRuns;
        this.sessions = sessions;
        this.appUis = appUis;
    }

    @POST
    public CreateApplicationResponse createApplication(CreateApplicationRequest request) {
        Application app = service.createApplication(request);
        CreateApplicationResponse response = new CreateApplicationResponse();
        response.setApplicationId(app.getApplicationId());
        response.setArn(app.getArn());
        response.setName(app.getName());
        return response;
    }

    @GET
    public ListApplicationsResponse listApplications(
            @QueryParam("states") List<String> states,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        
        ListApplicationsRequest req = new ListApplicationsRequest();
        req.setStates(states);
        req.setMaxResults(Pagination.parseMaxResults(maxResults, "ValidationException"));
        req.setNextToken(nextToken);
        
        PaginatedResult<ApplicationSummary> page = service.listApplications(req);
        
        ListApplicationsResponse response = new ListApplicationsResponse();
        response.setApplications(page.items());
        response.setNextToken(page.nextToken());
        return response;
    }

    @GET
    @Path("/{applicationId}")
    public GetApplicationResponse getApplication(@PathParam("applicationId") String applicationId) {
        Application app = service.getApplication(applicationId);
        GetApplicationResponse response = new GetApplicationResponse();
        response.setApplication(app);
        return response;
    }

    @PATCH
    @Path("/{applicationId}")
    public UpdateApplicationResponse updateApplication(@PathParam("applicationId") String applicationId,
                                                     UpdateApplicationRequest request) {
        UpdateApplicationResponse response = new UpdateApplicationResponse();
        response.setApplication(jobRuns.updateApplication(applicationId, request));
        return response;
    }

    @DELETE
    @Path("/{applicationId}")
    public DeleteApplicationResponse deleteApplication(@PathParam("applicationId") String applicationId) {
        service.deleteApplication(applicationId);
        DeleteApplicationResponse response = new DeleteApplicationResponse();
        response.setApplicationId(applicationId);
        return response;
    }

    @POST
    @Path("/{applicationId}/start")
    public StartApplicationResponse startApplication(@PathParam("applicationId") String applicationId) {
        service.startApplication(applicationId);
        StartApplicationResponse response = new StartApplicationResponse();
        return response;
    }

    @POST
    @Path("/{applicationId}/stop")
    public StopApplicationResponse stopApplication(@PathParam("applicationId") String applicationId) {
        sessions.stopApplication(applicationId);
        StopApplicationResponse response = new StopApplicationResponse();
        return response;
    }

    @POST
    @Path("/{applicationId}/jobruns")
    public StartJobRunResponse startJobRun(@PathParam("applicationId") String applicationId,
                                           StartJobRunRequest request) {
        JobRun run = jobRuns.startJobRun(applicationId, request);
        StartJobRunResponse response = new StartJobRunResponse();
        response.setApplicationId(run.getApplicationId());
        response.setJobRunId(run.getJobRunId());
        response.setArn(run.getArn());
        return response;
    }

    @GET
    @Path("/{applicationId}/jobruns")
    public ListJobRunsResponse listJobRuns(@PathParam("applicationId") String applicationId,
                                           @QueryParam("states") List<String> states,
                                           @QueryParam("createdAtAfter") String createdAtAfter,
                                           @QueryParam("createdAtBefore") String createdAtBefore,
                                           @QueryParam("mode") String mode,
                                           @QueryParam("maxResults") String maxResults,
                                           @QueryParam("nextToken") String nextToken) {
        PaginatedResult<JobRunSummary> page = jobRuns.listJobRuns(applicationId, states,
                EmrServerlessJobRunService.parseTimestamp(createdAtAfter, "createdAtAfter"),
                EmrServerlessJobRunService.parseTimestamp(createdAtBefore, "createdAtBefore"),
                mode, Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken);
        ListJobRunsResponse response = new ListJobRunsResponse();
        response.setJobRuns(page.items());
        response.setNextToken(page.nextToken());
        return response;
    }

    @GET
    @Path("/{applicationId}/jobruns/{jobRunId}")
    public GetJobRunResponse getJobRun(@PathParam("applicationId") String applicationId,
                                       @PathParam("jobRunId") String jobRunId,
                                       @QueryParam("attempt") String attempt) {
        GetJobRunResponse response = new GetJobRunResponse();
        response.setJobRun(jobRuns.getJobRun(applicationId, jobRunId,
                EmrServerlessJobRunService.parseAttempt(attempt)));
        return response;
    }

    /**
     * The link to a run's application UI, for the attempt asked for or else the latest, the run and attempt checked as
     * GetJobRun checks them ({@link EmrServerlessAppUis}). {@code accessSystemProfileLogs} is for jobs on Lake
     * Formation, which Floci runs none of, so it changes nothing.
     */
    @GET
    @Path("/{applicationId}/jobruns/{jobRunId}/dashboard")
    public GetDashboardForJobRunResponse getDashboardForJobRun(@PathParam("applicationId") String applicationId,
                                                               @PathParam("jobRunId") String jobRunId,
                                                               @QueryParam("attempt") String attempt) {
        JobRun run = jobRuns.getJobRun(applicationId, jobRunId, EmrServerlessJobRunService.parseAttempt(attempt));
        GetDashboardForJobRunResponse response = new GetDashboardForJobRunResponse();
        response.setUrl(appUis.jobRunUrl(applicationId, jobRunId, run.getAttempt() != null ? run.getAttempt() : 1));
        return response;
    }

    @GET
    @Path("/{applicationId}/jobruns/{jobRunId}/attempts")
    public ListJobRunAttemptsResponse listJobRunAttempts(@PathParam("applicationId") String applicationId,
                                                         @PathParam("jobRunId") String jobRunId,
                                                         @QueryParam("maxResults") String maxResults,
                                                         @QueryParam("nextToken") String nextToken) {
        PaginatedResult<JobRunAttemptSummary> page = jobRuns.listJobRunAttempts(applicationId, jobRunId,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken);
        ListJobRunAttemptsResponse response = new ListJobRunAttemptsResponse();
        response.setJobRunAttempts(page.items());
        response.setNextToken(page.nextToken());
        return response;
    }

    @DELETE
    @Path("/{applicationId}/jobruns/{jobRunId}")
    public CancelJobRunResponse cancelJobRun(@PathParam("applicationId") String applicationId,
                                             @PathParam("jobRunId") String jobRunId,
                                             @QueryParam("shutdownGracePeriodInSeconds") String gracePeriod) {
        JobRun run = jobRuns.cancelJobRun(applicationId, jobRunId,
                EmrServerlessJobRunService.queryInteger(gracePeriod, "shutdownGracePeriodInSeconds"));
        CancelJobRunResponse response = new CancelJobRunResponse();
        response.setApplicationId(run.getApplicationId());
        response.setJobRunId(run.getJobRunId());
        return response;
    }

    @POST
    @Path("/{applicationId}/sessions")
    public StartSessionResponse startSession(@PathParam("applicationId") String applicationId,
                                             StartSessionRequest request) {
        Session session = sessions.startSession(applicationId, request);
        StartSessionResponse response = new StartSessionResponse();
        response.setApplicationId(session.getApplicationId());
        response.setSessionId(session.getSessionId());
        response.setArn(session.getArn());
        return response;
    }

    @GET
    @Path("/{applicationId}/sessions")
    public ListSessionsResponse listSessions(@PathParam("applicationId") String applicationId,
                                             @QueryParam("states") List<String> states,
                                             @QueryParam("createdAtAfter") String createdAtAfter,
                                             @QueryParam("createdAtBefore") String createdAtBefore,
                                             @QueryParam("maxResults") String maxResults,
                                             @QueryParam("nextToken") String nextToken) {
        PaginatedResult<SessionSummary> page = sessions.listSessions(applicationId, states,
                EmrServerlessJobRunService.parseTimestamp(createdAtAfter, "createdAtAfter"),
                EmrServerlessJobRunService.parseTimestamp(createdAtBefore, "createdAtBefore"),
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken);
        ListSessionsResponse response = new ListSessionsResponse();
        response.setSessions(page.items());
        response.setNextToken(page.nextToken());
        return response;
    }

    @GET
    @Path("/{applicationId}/sessions/{sessionId}")
    public GetSessionResponse getSession(@PathParam("applicationId") String applicationId,
                                         @PathParam("sessionId") String sessionId) {
        GetSessionResponse response = new GetSessionResponse();
        response.setSession(sessions.getSession(applicationId, sessionId));
        return response;
    }

    @DELETE
    @Path("/{applicationId}/sessions/{sessionId}")
    public TerminateSessionResponse terminateSession(@PathParam("applicationId") String applicationId,
                                                     @PathParam("sessionId") String sessionId) {
        Session session = sessions.terminateSession(applicationId, sessionId);
        TerminateSessionResponse response = new TerminateSessionResponse();
        response.setApplicationId(session.getApplicationId());
        response.setSessionId(session.getSessionId());
        return response;
    }

    @GET
    @Path("/{applicationId}/sessions/{sessionId}/endpoint")
    public GetSessionEndpointResponse getSessionEndpoint(@PathParam("applicationId") String applicationId,
                                                         @PathParam("sessionId") String sessionId) {
        SparkConnectEndpoint endpoint = sessions.sessionEndpoint(applicationId, sessionId);
        GetSessionEndpointResponse response = new GetSessionEndpointResponse();
        response.setApplicationId(applicationId);
        response.setSessionId(sessionId);
        response.setEndpoint(endpoint.url());
        response.setAuthToken(endpoint.authToken());
        response.setAuthTokenExpiresAt(endpoint.authTokenExpiresAt());
        return response;
    }

    /**
     * The link to a session's application UI ({@link EmrServerlessAppUis}). {@code accessSystemProfileLogs} is for
     * sessions on Lake Formation, which Floci runs none of, so it changes nothing.
     */
    @GET
    @Path("/{applicationId}/dashboard")
    public GetResourceDashboardResponse getResourceDashboard(@PathParam("applicationId") String applicationId,
                                                             @QueryParam("resourceId") String resourceId,
                                                             @QueryParam("resourceType") String resourceType) {
        Session session = sessions.dashboardResource(applicationId, resourceId, resourceType);
        GetResourceDashboardResponse response = new GetResourceDashboardResponse();
        response.setUrl(appUis.sessionUrl(applicationId, session));
        return response;
    }
}
