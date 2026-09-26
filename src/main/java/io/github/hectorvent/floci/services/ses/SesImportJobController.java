package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.ImportJob;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

import static io.github.hectorvent.floci.services.ses.SesV2Json.intMemberOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.putTimestamp;
import static io.github.hectorvent.floci.services.ses.SesV2Json.readRequiredStringField;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireObjectOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMemberOrAbsent;

/**
 * SES V2 import job endpoints ({@code /v2/email/import-jobs}). Every operation is a single-domain
 * call on {@link SesImportJobService}; the JSON shape of {@code ImportDestination} /
 * {@code ImportDataSource} is parsed and rendered here.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesImportJobController {

    private static final Logger LOG = Logger.getLogger(SesImportJobController.class);

    private final SesImportJobService importJobService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    // FAIL_ON_TRAILING_TOKENS so "{...} garbage" is rejected instead of creating a job from the
    // leading object, as the JSON protocol controllers do.
    private final ObjectReader strictReader;

    @Inject
    public SesImportJobController(SesImportJobService importJobService, RegionResolver regionResolver,
                                  ObjectMapper objectMapper) {
        this.importJobService = importJobService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.strictReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @POST
    @Path("/import-jobs")
    public Response createImportJob(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = strictReader.readTree(body);
            requireJsonObject(request);
            JsonNode source = requireObjectOrAbsent(request, "ImportDataSource");
            if (!source.isObject()) {
                throw new AwsException("BadRequestException", "ImportDataSource is required.", 400);
            }
            String s3Url = readRequiredStringField(source, "S3Url");
            String dataFormat = readRequiredStringField(source, "DataFormat");
            JsonNode destination = requireObjectOrAbsent(request, "ImportDestination");
            if (!destination.isObject()) {
                throw new AwsException("BadRequestException", "ImportDestination is required.", 400);
            }
            JsonNode suppression = requireObjectOrAbsent(destination, "SuppressionListDestination");
            JsonNode contactList = requireObjectOrAbsent(destination, "ContactListDestination");
            if (suppression.isObject() == contactList.isObject()) {
                throw new AwsException("BadRequestException",
                        "ImportDestination must contain exactly one of SuppressionListDestination "
                                + "or ContactListDestination.", 400);
            }
            String accountId = regionResolver.getAccountId();
            ImportJob job;
            if (suppression.isObject()) {
                String action = readRequiredStringField(suppression, "SuppressionListImportAction");
                job = importJobService.createSuppressionListImportJob(region, accountId, s3Url, dataFormat, action);
            } else {
                String listName = readRequiredStringField(contactList, "ContactListName");
                String action = readRequiredStringField(contactList, "ContactListImportAction");
                job = importJobService.createContactListImportJob(region, accountId, s3Url, dataFormat,
                        listName, action);
            }
            LOG.infov("SES V2 CreateImportJob: {0}", job.getJobId());
            ObjectNode result = objectMapper.createObjectNode();
            result.put("JobId", job.getJobId());
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/import-jobs/{jobId}")
    public Response getImportJob(@Context HttpHeaders headers, @PathParam("jobId") String jobId) {
        String region = regionResolver.resolveRegion(headers);
        ImportJob job = importJobService.getImportJob(region, jobId);
        ObjectNode result = objectMapper.createObjectNode();
        writeSummary(result, job);
        ObjectNode source = result.putObject("ImportDataSource");
        source.put("S3Url", job.getS3Url());
        source.put("DataFormat", job.getDataFormat());
        putTimestamp(result, "CompletedTimestamp", job.getCompletedTimestamp());
        if (job.getErrorMessage() != null) {
            result.putObject("FailureInfo").put("ErrorMessage", job.getErrorMessage());
        }
        return Response.ok(result).build();
    }

    @POST
    @Path("/import-jobs/list")
    public Response listImportJobs(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : strictReader.readTree(body);
            requireJsonObject(request);
            String destinationType = stringMemberOrAbsent(request, "ImportDestinationType");
            Integer pageSize = intMemberOrAbsent(request, "PageSize");
            String nextToken = stringMemberOrAbsent(request, "NextToken");
            List<ImportJob> jobs = importJobService.listImportJobs(region, destinationType, pageSize, nextToken);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode items = result.putArray("ImportJobs");
            for (ImportJob job : jobs) {
                writeSummary(items.addObject(), job);
            }
            // AWS renders NextToken as an explicit null on the last (here: only) page.
            result.putNull("NextToken");
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // The ImportJobSummary members, shared by GetImportJob (which adds the source, completion time
    // and failure info on top).
    private static void writeSummary(ObjectNode node, ImportJob job) {
        node.put("JobId", job.getJobId());
        ObjectNode destination = node.putObject("ImportDestination");
        if (ImportJob.DESTINATION_SUPPRESSION_LIST.equals(job.getDestinationType())) {
            destination.putObject("SuppressionListDestination")
                    .put("SuppressionListImportAction", job.getImportAction());
        } else {
            ObjectNode contactList = destination.putObject("ContactListDestination");
            contactList.put("ContactListName", job.getContactListName());
            contactList.put("ContactListImportAction", job.getImportAction());
        }
        node.put("JobStatus", job.getJobStatus());
        putTimestamp(node, "CreatedTimestamp", job.getCreatedTimestamp());
        node.put("ProcessedRecordsCount", job.getProcessedRecordsCount());
        node.put("FailedRecordsCount", job.getFailedRecordsCount());
    }
}
