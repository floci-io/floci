package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.ExportJob;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;

import static io.github.hectorvent.floci.services.ses.SesV2Json.intMemberOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.putTimestamp;
import static io.github.hectorvent.floci.services.ses.SesV2Json.readOptionBody;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMemberOrAbsent;

/**
 * SES V2 export jobs ({@code /v2/email/export-jobs}, {@code /v2/email/list-export-jobs}).
 *
 * <p>Probe-confirmed against real SES (2026-09-23): a request that names its own {@code S3Url} is
 * refused before the Virtual Deliverability Manager gate is reached, which is why the gate is
 * passed into the service rather than checked first as it is for message insights and metric data.
 * The gate itself answers {@code BadRequestException} 400 here, where those two answer
 * {@code NotFoundException} 404 with the same sentence.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesExportJobController {

    private static final int DEFAULT_PAGE_SIZE = 100;
    /**
     * A page no emulator will ever fill, so a larger {@code PageSize} can be clamped instead of
     * refused. The paginator adds this to the page offset as an int, so {@code Integer.MAX_VALUE}
     * would overflow that sum and report a further page that does not exist.
     */
    private static final int MAX_PAGE_SIZE = 1_000_000;

    private final SesExportJobService exportJobService;
    private final SesAccountService accountService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesExportJobController(SesExportJobService exportJobService,
                                  SesAccountService accountService,
                                  RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.exportJobService = exportJobService;
        this.accountService = accountService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/export-jobs")
    public Response createExportJob(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readOptionBody(objectMapper, body);
        JsonNode destination = request.path("ExportDestination");
        JsonNode dataSource = request.path("ExportDataSource");
        ExportJob job = exportJobService.createExportJob(region, regionResolver.getAccountId(),
                dataSource, destination,
                () -> accountService.requireVdmEnabled(region, "BadRequestException", 400));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("JobId", job.getJobId());
        return Response.ok(result).build();
    }

    @GET
    @Path("/export-jobs/{jobId}")
    public Response getExportJob(@Context HttpHeaders headers, @PathParam("jobId") String jobId) {
        String region = regionResolver.resolveRegion(headers);
        ExportJob job = exportJobService.getExportJob(region, jobId);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("JobId", job.getJobId());
        result.put("ExportSourceType", job.getExportSourceType());
        result.put("JobStatus", job.getJobStatus());

        ObjectNode destination = result.putObject("ExportDestination");
        destination.put("DataFormat", job.getDataFormat());
        exportJobService.presignedUrl(job).ifPresent(url -> destination.put("S3Url", url));
        result.set("ExportDataSource", readOptionBody(objectMapper, job.getDataSource()));

        putTimestamp(result, "CreatedTimestamp", job.getCreatedTimestamp());
        putTimestamp(result, "CompletedTimestamp", job.getCompletedTimestamp());
        if (job.getProcessedRecordsCount() != null) {
            // AWS returns only ProcessedRecordsCount, never ExportedRecordsCount, so neither does
            // Floci even though the model carries both.
            result.putObject("Statistics")
                    .put("ProcessedRecordsCount", job.getProcessedRecordsCount());
        }
        if (job.getErrorMessage() != null) {
            result.putObject("FailureInfo").put("ErrorMessage", job.getErrorMessage());
        }
        return Response.ok(result).build();
    }

    @POST
    @Path("/list-export-jobs")
    public Response listExportJobs(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readOptionBody(objectMapper, body);
        String sourceType = stringMemberOrAbsent(request, "ExportSourceType");
        String jobStatus = stringMemberOrAbsent(request, "JobStatus");
        String nextToken = stringMemberOrAbsent(request, "NextToken");
        // PageSize carries no bound in the model, so a large one is clamped rather than refused;
        // only a value below 1 is an error, and the default applies when it is absent.
        Integer pageSize = intMemberOrAbsent(request, "PageSize");
        if (pageSize != null && pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }

        PaginatedResult<ExportJob> page = Pagination.paginate(
                exportJobService.listExportJobs(region, sourceType, jobStatus),
                SesExportJobController::cursor, pageSize, nextToken, DEFAULT_PAGE_SIZE,
                MAX_PAGE_SIZE, "BadRequestException");

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode jobs = result.putArray("ExportJobs");
        for (ExportJob job : page.items()) {
            ObjectNode node = jobs.addObject();
            node.put("JobId", job.getJobId());
            node.put("ExportSourceType", job.getExportSourceType());
            node.put("JobStatus", job.getJobStatus());
            putTimestamp(node, "CreatedTimestamp", job.getCreatedTimestamp());
            putTimestamp(node, "CompletedTimestamp", job.getCompletedTimestamp());
        }
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return Response.ok(result).build();
    }

    @PUT
    @Path("/export-jobs/{jobId}/cancel")
    public Response cancelExportJob(@Context HttpHeaders headers, @PathParam("jobId") String jobId) {
        String region = regionResolver.resolveRegion(headers);
        exportJobService.cancelExportJob(region, jobId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /**
     * The shared paginator orders by the cursor ascending, and AWS returns the newest job first, so
     * the cursor counts time backwards. The job id breaks ties between jobs created in the same
     * millisecond.
     */
    private static String cursor(ExportJob job) {
        Instant created = job.getCreatedTimestamp();
        long descending = Long.MAX_VALUE - (created == null ? 0L : created.toEpochMilli());
        return String.format("%019d#%s", descending, job.getJobId());
    }
}
