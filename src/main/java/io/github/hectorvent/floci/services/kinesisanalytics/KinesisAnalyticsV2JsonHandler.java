package io.github.hectorvent.floci.services.kinesisanalytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;

/**
 * Dispatches Kinesis Analytics V2 (Managed Service for Apache Flink) actions for the
 * {@code application/x-amz-json-1.1} protocol, routed here by {@code AwsJson11Controller}
 * on the {@code KinesisAnalytics_20180523.} target prefix.
 */
@ApplicationScoped
public class KinesisAnalyticsV2JsonHandler {

    private final KinesisAnalyticsV2Service service;
    private final ObjectMapper objectMapper;

    @Inject
    public KinesisAnalyticsV2JsonHandler(KinesisAnalyticsV2Service service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateApplication" -> handleCreateApplication(request);
            case "DescribeApplication" -> handleDescribeApplication(request);
            case "ListApplications" -> handleListApplications(request);
            case "StartApplication" -> handleStartApplication(request);
            case "StopApplication" -> handleStopApplication(request);
            case "UpdateApplication" -> handleUpdateApplication(request);
            case "DeleteApplication" -> handleDeleteApplication(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation",
                            "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        String runtimeEnvironment = request.path("RuntimeEnvironment").asText(null);
        String serviceExecutionRole = request.path("ServiceExecutionRole").asText(null);
        String applicationDescription = request.path("ApplicationDescription").asText(null);
        String applicationMode = request.path("ApplicationMode").asText(null);

        // Application code (the Flink JAR in S3) + parallelism, from ApplicationConfiguration.
        JsonNode appConfig = request.path("ApplicationConfiguration");
        JsonNode s3 = appConfig.path("ApplicationCodeConfiguration").path("CodeContent")
                .path("S3ContentLocation");
        String codeBucket = bucketFromArn(s3.path("BucketARN").asText(null));
        String codeKey = s3.path("FileKey").asText(null);
        String codeVersion = s3.path("ObjectVersion").asText(null);
        int parallelism = appConfig.path("FlinkApplicationConfiguration")
                .path("ParallelismConfiguration").path("Parallelism").asInt(1);

        FlinkApplication app = service.createApplication(applicationName, runtimeEnvironment,
                serviceExecutionRole, applicationDescription, applicationMode,
                codeBucket, codeKey, codeVersion, parallelism);
        return applicationDetailResponse(app);
    }

    /** Extracts the bucket name from an S3 bucket ARN ({@code arn:aws:s3:::bucket}). */
    private static String bucketFromArn(String bucketArn) {
        if (bucketArn == null) {
            return null;
        }
        String prefix = "arn:aws:s3:::";
        return bucketArn.startsWith(prefix) ? bucketArn.substring(prefix.length()) : bucketArn;
    }

    private Response handleDescribeApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        return applicationDetailResponse(service.describeApplication(applicationName));
    }

    private Response handleListApplications(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("ApplicationSummaries");
        for (FlinkApplication app : service.listApplications()) {
            ObjectNode summary = summaries.addObject();
            summary.put("ApplicationName", app.getApplicationName());
            summary.put("ApplicationARN", app.getApplicationArn());
            summary.put("ApplicationStatus", app.getApplicationStatus().name());
            summary.put("ApplicationVersionId", app.getApplicationVersionId());
            summary.put("RuntimeEnvironment", app.getRuntimeEnvironment());
            if (app.getApplicationMode() != null) {
                summary.put("ApplicationMode", app.getApplicationMode());
            }
        }
        return Response.ok(response).build();
    }

    private Response handleStartApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        service.startApplication(applicationName);
        // AWS StartApplication returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleStopApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        service.stopApplication(applicationName);
        // AWS StopApplication returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUpdateApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        // CurrentApplicationVersionId gates the update (optimistic concurrency); the service rejects a
        // stale or missing value.
        Long currentVersionId = request.hasNonNull("CurrentApplicationVersionId")
                ? request.path("CurrentApplicationVersionId").asLong()
                : null;
        // ServiceExecutionRoleUpdate is the only field this emulator applies; other configuration
        // updates are accepted (version bump) but not otherwise modelled.
        String serviceExecutionRole = request.path("ServiceExecutionRoleUpdate").asText(null);
        return applicationDetailResponse(
                service.updateApplication(applicationName, currentVersionId, serviceExecutionRole));
    }

    private Response handleDeleteApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        // CreateTimestamp is epoch seconds on the wire (possibly fractional); the service validates it
        // against the stored value.
        Instant createTimestamp = request.hasNonNull("CreateTimestamp")
                ? Instant.ofEpochMilli(Math.round(request.path("CreateTimestamp").asDouble() * 1000))
                : null;
        service.deleteApplication(applicationName, createTimestamp);
        // AWS DeleteApplication returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response applicationDetailResponse(FlinkApplication app) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ApplicationDetail", applicationDetailNode(app));
        return Response.ok(response).build();
    }

    private ObjectNode applicationDetailNode(FlinkApplication app) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("ApplicationARN", app.getApplicationArn());
        detail.put("ApplicationName", app.getApplicationName());
        if (app.getApplicationDescription() != null) {
            detail.put("ApplicationDescription", app.getApplicationDescription());
        }
        detail.put("RuntimeEnvironment", app.getRuntimeEnvironment());
        if (app.getServiceExecutionRole() != null) {
            detail.put("ServiceExecutionRole", app.getServiceExecutionRole());
        }
        detail.put("ApplicationStatus", app.getApplicationStatus().name());
        detail.put("ApplicationVersionId", app.getApplicationVersionId());
        if (app.getApplicationMode() != null) {
            detail.put("ApplicationMode", app.getApplicationMode());
        }
        if (app.getCreateTimestamp() != null) {
            detail.put("CreateTimestamp", app.getCreateTimestamp().toEpochMilli() / 1000.0);
        }
        if (app.getLastUpdateTimestamp() != null) {
            detail.put("LastUpdateTimestamp", app.getLastUpdateTimestamp().toEpochMilli() / 1000.0);
        }
        if (app.hasCode()) {
            detail.set("ApplicationConfigurationDescription", applicationConfigurationNode(app));
        }
        return detail;
    }

    private ObjectNode applicationConfigurationNode(FlinkApplication app) {
        ObjectNode config = objectMapper.createObjectNode();

        ObjectNode codeDesc = config.putObject("ApplicationCodeConfigurationDescription");
        codeDesc.put("CodeContentType", "ZIPFILE");
        ObjectNode s3Desc = codeDesc.putObject("CodeContentDescription")
                .putObject("S3ApplicationCodeLocationDescription");
        s3Desc.put("BucketARN", "arn:aws:s3:::" + app.getCodeS3Bucket());
        s3Desc.put("FileKey", app.getCodeS3Key());
        if (app.getCodeS3ObjectVersion() != null) {
            s3Desc.put("ObjectVersion", app.getCodeS3ObjectVersion());
        }

        config.putObject("FlinkApplicationConfigurationDescription")
                .putObject("ParallelismConfigurationDescription")
                .put("Parallelism", app.getParallelism())
                .put("CurrentParallelism", app.getParallelism());

        return config;
    }
}
