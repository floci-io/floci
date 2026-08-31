package io.github.hectorvent.floci.services.bedrock;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrock.model.CreateModelInvocationJobRequest;
import io.github.hectorvent.floci.services.bedrock.model.CreateModelInvocationJobResponse;
import io.github.hectorvent.floci.services.bedrock.model.ListModelInvocationJobsResponse;
import io.github.hectorvent.floci.services.bedrock.model.ModelInvocationJob;
import io.github.hectorvent.floci.services.bedrock.model.ModelInvocationJobStatus;
import io.github.hectorvent.floci.services.bedrock.model.ModelInvocationJobSummary;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockController {

    private static final Logger LOG = Logger.getLogger(BedrockController.class);

    private final BedrockService service;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockController(BedrockService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    @POST
    @Blocking
    @Path("/model-invocation-job")
    public Response createModelInvocationJob(@Context HttpHeaders headers, CreateModelInvocationJobRequest request) {
        String region = regionResolver.resolveRegion(headers);
        CreateModelInvocationJobResponse response = service.createModelInvocationJob(request, region);
        return Response.status(201).entity(response).build();
    }

    @GET
    @Path("/model-invocation-job/{jobIdentifier:.+}")
    public Response getModelInvocationJob(@Context HttpHeaders headers, @PathParam("jobIdentifier") String jobIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        ModelInvocationJob job = service.getModelInvocationJob(jobIdentifier, region);
        return Response.ok(job).build();
    }

    @POST
    @Path("/model-invocation-job/{jobIdentifier:.+}/stop")
    public Response stopModelInvocationJob(@Context HttpHeaders headers, @PathParam("jobIdentifier") String jobIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        service.stopModelInvocationJob(jobIdentifier, region);
        return Response.ok("{}").build();
    }

    @GET
    @Path("/model-invocation-jobs")
    public Response listModelInvocationJobs(
            @Context HttpHeaders headers,
            @QueryParam("statusEquals") String statusEquals,
            @QueryParam("nameContains") String nameContains,
            @QueryParam("submitTimeAfter") String submitTimeAfter,
            @QueryParam("submitTimeBefore") String submitTimeBefore,
            @QueryParam("sortBy") String sortBy,
            @QueryParam("sortOrder") String sortOrder,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        ModelInvocationJobStatus status = statusEquals != null ? ModelInvocationJobStatus.fromValue(statusEquals) : null;
        Instant after = parseInstant(submitTimeAfter, "submitTimeAfter");
        Instant before = parseInstant(submitTimeBefore, "submitTimeBefore");

        PaginatedResult<ModelInvocationJobSummary> result = service.listModelInvocationJobs(
                status, nameContains, after, before, sortBy, sortOrder, maxResults, nextToken, region);
        return Response.ok(new ListModelInvocationJobsResponse(result.items(), result.nextToken())).build();
    }

    private Instant parseInstant(String dateStr, String paramName) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(dateStr);
        } catch (DateTimeParseException e) {
            throw new AwsException("ValidationException", "Invalid date format for " + paramName + ": " + dateStr, 400);
        }
    }
}
