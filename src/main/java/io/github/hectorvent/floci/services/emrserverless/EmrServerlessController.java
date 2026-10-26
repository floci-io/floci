package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.ApplicationSummary;
import io.github.hectorvent.floci.services.emrserverless.model.CancelJobRunResponse;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationResponse;
import io.github.hectorvent.floci.services.emrserverless.model.DeleteApplicationResponse;
import io.github.hectorvent.floci.services.emrserverless.model.GetApplicationResponse;
import io.github.hectorvent.floci.services.emrserverless.model.GetJobRunResponse;
import io.github.hectorvent.floci.services.emrserverless.model.JobRun;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunAttemptSummary;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunSummary;
import io.github.hectorvent.floci.services.emrserverless.model.ListApplicationsRequest;
import io.github.hectorvent.floci.services.emrserverless.model.ListApplicationsResponse;
import io.github.hectorvent.floci.services.emrserverless.model.ListJobRunAttemptsResponse;
import io.github.hectorvent.floci.services.emrserverless.model.ListJobRunsResponse;
import io.github.hectorvent.floci.services.emrserverless.model.StartApplicationResponse;
import io.github.hectorvent.floci.services.emrserverless.model.StartJobRunRequest;
import io.github.hectorvent.floci.services.emrserverless.model.StartJobRunResponse;
import io.github.hectorvent.floci.services.emrserverless.model.StopApplicationResponse;
import io.github.hectorvent.floci.services.emrserverless.model.UpdateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.UpdateApplicationResponse;
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

    @Inject
    public EmrServerlessController(EmrServerlessService service, EmrServerlessJobRunService jobRuns) {
        this.service = service;
        this.jobRuns = jobRuns;
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
        Application app = service.updateApplication(applicationId, request);
        UpdateApplicationResponse response = new UpdateApplicationResponse();
        response.setApplicationId(app.getApplicationId());
        response.setArn(app.getArn());
        response.setName(app.getName());
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
        service.stopApplication(applicationId);
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
                                             @PathParam("jobRunId") String jobRunId) {
        JobRun run = jobRuns.cancelJobRun(applicationId, jobRunId);
        CancelJobRunResponse response = new CancelJobRunResponse();
        response.setApplicationId(run.getApplicationId());
        response.setJobRunId(run.getJobRunId());
        return response;
    }
}
