package io.github.hectorvent.floci.services.kinesisanalytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import io.github.hectorvent.floci.services.kinesisanalytics.model.Snapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            case "CreateApplicationPresignedUrl" -> handleCreateApplicationPresignedUrl(request);
            case "DescribeApplication" -> handleDescribeApplication(request);
            case "ListApplications" -> handleListApplications(request);
            case "StartApplication" -> handleStartApplication(request);
            case "StopApplication" -> handleStopApplication(request);
            case "UpdateApplication" -> handleUpdateApplication(request);
            case "DeleteApplication" -> handleDeleteApplication(request);
            case "TagResource" -> handleTagResource(request);
            case "UntagResource" -> handleUntagResource(request);
            case "ListTagsForResource" -> handleListTagsForResource(request);
            case "CreateApplicationSnapshot" -> handleCreateApplicationSnapshot(request);
            case "DescribeApplicationSnapshot" -> handleDescribeApplicationSnapshot(request);
            case "ListApplicationSnapshots" -> handleListApplicationSnapshots(request);
            case "DeleteApplicationSnapshot" -> handleDeleteApplicationSnapshot(request);
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
        Map<String, Map<String, String>> environmentProperties =
                parsePropertyGroups(appConfig.path("EnvironmentProperties").path("PropertyGroups"));
        JsonNode snapshotsEnabledNode = appConfig.path("ApplicationSnapshotConfiguration").path("SnapshotsEnabled");
        Boolean snapshotsEnabled = snapshotsEnabledNode.isMissingNode() || snapshotsEnabledNode.isNull()
                ? null : snapshotsEnabledNode.asBoolean();

        FlinkApplication app = service.createApplication(applicationName, runtimeEnvironment,
                serviceExecutionRole, applicationDescription, applicationMode,
                codeBucket, codeKey, codeVersion, parallelism, parseTags(request.path("Tags")),
                environmentProperties, snapshotsEnabled);
        return applicationDetailResponse(app);
    }

    private Response handleCreateApplicationPresignedUrl(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        String urlType = request.path("UrlType").asText(null);
        Long sessionExpirationDurationInSeconds = request.hasNonNull("SessionExpirationDurationInSeconds")
                ? request.path("SessionExpirationDurationInSeconds").asLong() : null;
        String url = service.createApplicationPresignedUrl(applicationName, urlType,
                sessionExpirationDurationInSeconds);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AuthorizedUrl", url);
        return Response.ok(response).build();
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
        String serviceExecutionRole = request.path("ServiceExecutionRoleUpdate").asText(null);

        // ApplicationConfigurationUpdate.ApplicationCodeConfigurationUpdate.CodeContentUpdate
        // .S3ContentLocationUpdate: a new JAR location to redeploy in place.
        JsonNode appCfgUpdate = request.path("ApplicationConfigurationUpdate");
        JsonNode s3Update = appCfgUpdate.path("ApplicationCodeConfigurationUpdate")
                .path("CodeContentUpdate").path("S3ContentLocationUpdate");
        String codeBucket = bucketFromArn(s3Update.path("BucketARNUpdate").asText(null));
        String codeKey = s3Update.path("FileKeyUpdate").asText(null);
        String codeVersion = s3Update.path("ObjectVersionUpdate").asText(null);
        JsonNode parallelismUpdate = appCfgUpdate.path("FlinkApplicationConfigurationUpdate")
                .path("ParallelismConfigurationUpdate").path("ParallelismUpdate");
        Integer parallelism = parallelismUpdate.isMissingNode() || parallelismUpdate.isNull()
                ? null : parallelismUpdate.asInt();
        JsonNode snapshotsEnabledUpdate = appCfgUpdate.path("ApplicationSnapshotConfigurationUpdate")
                .path("SnapshotsEnabledUpdate");
        Boolean snapshotsEnabled = snapshotsEnabledUpdate.isMissingNode() || snapshotsEnabledUpdate.isNull()
                ? null : snapshotsEnabledUpdate.asBoolean();

        return applicationDetailResponse(service.updateApplication(applicationName, currentVersionId,
                serviceExecutionRole, codeBucket, codeKey, codeVersion, parallelism, snapshotsEnabled));
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

    private Response handleCreateApplicationSnapshot(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        String snapshotName = request.path("SnapshotName").asText(null);
        service.createApplicationSnapshot(applicationName, snapshotName);
        // AWS CreateApplicationSnapshot returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeApplicationSnapshot(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        String snapshotName = request.path("SnapshotName").asText(null);
        Snapshot snapshot = service.describeApplicationSnapshot(applicationName, snapshotName);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SnapshotDetails", snapshotDetailNode(snapshot));
        return Response.ok(response).build();
    }

    private Response handleListApplicationSnapshots(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("SnapshotSummaries");
        for (Snapshot snapshot : service.listApplicationSnapshots(applicationName)) {
            summaries.add(snapshotDetailNode(snapshot));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteApplicationSnapshot(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        String snapshotName = request.path("SnapshotName").asText(null);
        Instant snapshotCreationTimestamp = request.hasNonNull("SnapshotCreationTimestamp")
                ? Instant.ofEpochMilli(Math.round(request.path("SnapshotCreationTimestamp").asDouble() * 1000))
                : null;
        service.deleteApplicationSnapshot(applicationName, snapshotName, snapshotCreationTimestamp);
        // AWS DeleteApplicationSnapshot returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode snapshotDetailNode(Snapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SnapshotName", snapshot.getSnapshotName());
        node.put("SnapshotStatus", snapshot.getSnapshotStatus().name());
        node.put("ApplicationVersionId", snapshot.getApplicationVersionId());
        if (snapshot.getSnapshotCreationTimestamp() != null) {
            node.put("SnapshotCreationTimestamp", snapshot.getSnapshotCreationTimestamp().toEpochMilli() / 1000.0);
        }
        if (snapshot.getRuntimeEnvironment() != null) {
            node.put("RuntimeEnvironment", snapshot.getRuntimeEnvironment());
        }
        return node;
    }

    private Response handleTagResource(JsonNode request) {
        String resourceArn = request.path("ResourceARN").asText(null);
        service.tagResource(resourceArn, parseTags(request.path("Tags")));
        // AWS TagResource returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request) {
        String resourceArn = request.path("ResourceARN").asText(null);
        List<String> tagKeys = new ArrayList<>();
        request.path("TagKeys").forEach(k -> tagKeys.add(k.asText()));
        service.untagResource(resourceArn, tagKeys);
        // AWS UntagResource returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request) {
        String resourceArn = request.path("ResourceARN").asText(null);
        Map<String, String> tags = service.listTagsForResource(resourceArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsNode(tags));
        return Response.ok(response).build();
    }

    /** {@code Tags} is a list of {@code {Key, Value}} objects on this API, not a string map. */
    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = tag.path("Key").asText(null);
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(null));
                }
            }
        }
        return tags;
    }

    private ArrayNode tagsNode(Map<String, String> tags) {
        ArrayNode arr = objectMapper.createArrayNode();
        tags.forEach((k, v) -> {
            ObjectNode tag = arr.addObject();
            tag.put("Key", k);
            if (v != null) {
                tag.put("Value", v);
            }
        });
        return arr;
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

        if (!app.getEnvironmentProperties().isEmpty()) {
            ArrayNode groups = config.putObject("EnvironmentPropertyDescriptions")
                    .putArray("PropertyGroupDescriptions");
            app.getEnvironmentProperties().forEach((groupId, properties) -> {
                ObjectNode group = groups.addObject();
                group.put("PropertyGroupId", groupId);
                ObjectNode map = group.putObject("PropertyMap");
                properties.forEach(map::put);
            });
        }

        config.putObject("ApplicationSnapshotConfigurationDescription")
                .put("SnapshotsEnabled", app.isSnapshotsEnabled());

        return config;
    }

    /** {@code ApplicationConfiguration.EnvironmentProperties.PropertyGroups}: a list of
     *  {@code {PropertyGroupId, PropertyMap}} objects, keyed by PropertyGroupId internally since
     *  that's how a Flink app looks a group up via {@code KinesisAnalyticsRuntime.getApplicationProperties()}. */
    private Map<String, Map<String, String>> parsePropertyGroups(JsonNode propertyGroupsNode) {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        if (propertyGroupsNode != null && propertyGroupsNode.isArray()) {
            for (JsonNode group : propertyGroupsNode) {
                String groupId = group.path("PropertyGroupId").asText(null);
                if (groupId == null) {
                    continue;
                }
                Map<String, String> properties = new LinkedHashMap<>();
                group.path("PropertyMap").fields().forEachRemaining(
                        entry -> properties.put(entry.getKey(), entry.getValue().asText(null)));
                groups.put(groupId, properties);
            }
        }
        return groups;
    }
}
