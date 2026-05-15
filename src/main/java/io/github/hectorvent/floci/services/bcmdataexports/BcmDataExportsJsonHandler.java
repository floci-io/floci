package io.github.hectorvent.floci.services.bcmdataexports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.bcmdataexports.model.DataQuery;
import io.github.hectorvent.floci.services.bcmdataexports.model.DestinationConfiguration;
import io.github.hectorvent.floci.services.bcmdataexports.model.Export;
import io.github.hectorvent.floci.services.bcmdataexports.model.ExportExecution;
import io.github.hectorvent.floci.services.bcmdataexports.model.RefreshCadence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for AWS BCM Data Exports operations.
 * Dispatches {@code X-Amz-Target: AWSBillingAndCostManagementDataExports.*}
 * actions to {@link BcmDataExportsService}.
 */
@ApplicationScoped
public class BcmDataExportsJsonHandler {

    private static final Logger LOG = Logger.getLogger(BcmDataExportsJsonHandler.class);

    private final BcmDataExportsService service;
    private final ObjectMapper objectMapper;
    private final io.github.hectorvent.floci.services.cur.CurEmissionScheduler scheduler;

    @Inject
    public BcmDataExportsJsonHandler(BcmDataExportsService service,
                                     ObjectMapper objectMapper,
                                     io.github.hectorvent.floci.services.cur.CurEmissionScheduler scheduler) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("BcmDataExports action: {0}", action);
        return switch (action) {
            case "CreateExport" -> handleCreate(request, region);
            case "GetExport" -> handleGet(request);
            case "ListExports" -> handleList();
            case "UpdateExport" -> handleUpdate(request);
            case "DeleteExport" -> handleDelete(request);
            case "ListExecutions" -> handleListExecutions(request);
            case "GetExecution" -> handleGetExecution(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSBillingAndCostManagementDataExports." + action))
                    .build();
        };
    }

    private Response handleCreate(JsonNode request, String region) {
        Export incoming = parseExport(request.path("Export"));
        Map<String, String> tags = parseResourceTags(request.path("ResourceTags"));
        Export created = service.createExport(incoming, tags, region);
        try {
            scheduler.emitForExportSync(created, region, "USER");
        } catch (RuntimeException e) {
            LOG.warnv(e, "Synchronous emission failed for {0}; export still created.", created.getName());
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ExportArn", created.getExportArn());
        return Response.ok(response).build();
    }

    private Response handleGet(JsonNode request) {
        String arn = stringOrNull(request, "ExportArn");
        Export export = service.getExport(arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Export", serializeExport(export));
        return Response.ok(response).build();
    }

    private Response handleList() {
        List<Export> exports = service.listExports();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Exports");
        for (Export export : exports) {
            arr.add(serializeExportReference(export));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdate(JsonNode request) {
        String arn = stringOrNull(request, "ExportArn");
        Export incoming = parseExport(request.path("Export"));
        Export updated = service.updateExport(arn, incoming);
        try {
            scheduler.emitForExportSync(updated, null, "USER");
        } catch (RuntimeException e) {
            LOG.warnv(e, "Synchronous emission failed for {0}; export still updated.", updated.getExportArn());
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ExportArn", updated.getExportArn());
        return Response.ok(response).build();
    }

    private Response handleDelete(JsonNode request) {
        String arn = stringOrNull(request, "ExportArn");
        service.deleteExport(arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ExportArn", arn == null ? "" : arn);
        return Response.ok(response).build();
    }

    private Response handleListExecutions(JsonNode request) {
        String arn = stringOrNull(request, "ExportArn");
        List<ExportExecution> executions = service.listExecutions(arn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Executions");
        for (ExportExecution exec : executions) {
            arr.add(serializeExecution(exec));
        }
        return Response.ok(response).build();
    }

    private Response handleGetExecution(JsonNode request) {
        String arn = stringOrNull(request, "ExportArn");
        String executionId = stringOrNull(request, "ExecutionId");
        ExportExecution exec = service.getExecution(arn, executionId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ExportArn", arn);
        response.set("Execution", serializeExecution(exec));
        return Response.ok(response).build();
    }

    private Export parseExport(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new AwsException("ValidationException", "Export is required.", 400);
        }
        Export e = new Export();
        e.setName(stringOrNull(node, "Name"));
        e.setDescription(stringOrNull(node, "Description"));
        e.setDataQuery(parseDataQuery(node.path("DataQuery")));
        e.setDestinationConfigurations(parseDestination(node.path("DestinationConfigurations")));
        e.setRefreshCadence(parseRefreshCadence(node.path("RefreshCadence")));
        return e;
    }

    private DataQuery parseDataQuery(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return null;
        }
        DataQuery dq = new DataQuery();
        dq.setQueryStatement(stringOrNull(node, "QueryStatement"));
        JsonNode tableConfigs = node.path("TableConfigurations");
        if (tableConfigs.isObject()) {
            Map<String, Map<String, String>> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> tables = tableConfigs.fields();
            while (tables.hasNext()) {
                Map.Entry<String, JsonNode> table = tables.next();
                Map<String, String> props = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> propIter = table.getValue().fields();
                while (propIter.hasNext()) {
                    Map.Entry<String, JsonNode> prop = propIter.next();
                    props.put(prop.getKey(), prop.getValue().asText());
                }
                result.put(table.getKey(), props);
            }
            dq.setTableConfigurations(result);
        }
        return dq;
    }

    private DestinationConfiguration parseDestination(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return null;
        }
        DestinationConfiguration dest = new DestinationConfiguration();
        JsonNode s3Node = node.path("S3Destination");
        if (s3Node.isObject() && !s3Node.isEmpty()) {
            DestinationConfiguration.S3Destination s3 = new DestinationConfiguration.S3Destination();
            s3.setS3Bucket(stringOrNull(s3Node, "S3Bucket"));
            s3.setS3Prefix(stringOrNull(s3Node, "S3Prefix"));
            s3.setS3Region(stringOrNull(s3Node, "S3Region"));
            JsonNode outNode = s3Node.path("S3OutputConfigurations");
            if (outNode.isObject() && !outNode.isEmpty()) {
                DestinationConfiguration.S3OutputConfigurations out = new DestinationConfiguration.S3OutputConfigurations();
                out.setCompression(stringOrNull(outNode, "Compression"));
                out.setFormat(stringOrNull(outNode, "Format"));
                out.setOutputType(stringOrNull(outNode, "OutputType"));
                out.setOverwrite(stringOrNull(outNode, "Overwrite"));
                s3.setS3OutputConfigurations(out);
            }
            dest.setS3Destination(s3);
        }
        return dest;
    }

    private RefreshCadence parseRefreshCadence(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return null;
        }
        RefreshCadence cadence = new RefreshCadence();
        cadence.setFrequency(stringOrNull(node, "Frequency"));
        return cadence;
    }

    private Map<String, String> parseResourceTags(JsonNode node) {
        Map<String, String> out = new HashMap<>();
        if (node.isArray()) {
            for (JsonNode entry : node) {
                String key = stringOrNull(entry, "Key");
                String value = stringOrNull(entry, "Value");
                if (key != null) {
                    out.put(key, value == null ? "" : value);
                }
            }
        }
        return out;
    }

    private ObjectNode serializeExport(Export e) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ExportArn", e.getExportArn());
        out.put("Name", e.getName());
        if (e.getDescription() != null) {
            out.put("Description", e.getDescription());
        }
        if (e.getDataQuery() != null) {
            ObjectNode dq = out.putObject("DataQuery");
            dq.put("QueryStatement", e.getDataQuery().getQueryStatement());
            if (e.getDataQuery().getTableConfigurations() != null
                    && !e.getDataQuery().getTableConfigurations().isEmpty()) {
                ObjectNode tableConfigs = dq.putObject("TableConfigurations");
                for (Map.Entry<String, Map<String, String>> table : e.getDataQuery().getTableConfigurations().entrySet()) {
                    ObjectNode props = tableConfigs.putObject(table.getKey());
                    for (Map.Entry<String, String> prop : table.getValue().entrySet()) {
                        props.put(prop.getKey(), prop.getValue());
                    }
                }
            }
        }
        if (e.getDestinationConfigurations() != null
                && e.getDestinationConfigurations().getS3Destination() != null) {
            ObjectNode dest = out.putObject("DestinationConfigurations");
            ObjectNode s3 = dest.putObject("S3Destination");
            DestinationConfiguration.S3Destination src = e.getDestinationConfigurations().getS3Destination();
            s3.put("S3Bucket", src.getS3Bucket());
            s3.put("S3Prefix", src.getS3Prefix() == null ? "" : src.getS3Prefix());
            s3.put("S3Region", src.getS3Region());
            DestinationConfiguration.S3OutputConfigurations outCfg = src.getS3OutputConfigurations();
            if (outCfg != null) {
                ObjectNode outNode = s3.putObject("S3OutputConfigurations");
                if (outCfg.getCompression() != null) outNode.put("Compression", outCfg.getCompression());
                if (outCfg.getFormat() != null) outNode.put("Format", outCfg.getFormat());
                if (outCfg.getOutputType() != null) outNode.put("OutputType", outCfg.getOutputType());
                if (outCfg.getOverwrite() != null) outNode.put("Overwrite", outCfg.getOverwrite());
            }
        }
        if (e.getRefreshCadence() != null) {
            ObjectNode cadence = out.putObject("RefreshCadence");
            cadence.put("Frequency", e.getRefreshCadence().getFrequency());
        }
        if (e.getExportStatus() != null) {
            ObjectNode status = out.putObject("ExportStatus");
            status.put("StatusCode", e.getExportStatus());
            if (e.getCreatedAt() > 0) {
                status.put("CreatedAt", Instant.ofEpochMilli(e.getCreatedAt()).toString());
            }
            if (e.getLastUpdatedAt() > 0) {
                status.put("LastUpdatedAt", Instant.ofEpochMilli(e.getLastUpdatedAt()).toString());
            }
        }
        return out;
    }

    private ObjectNode serializeExportReference(Export e) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ExportArn", e.getExportArn());
        out.put("ExportName", e.getName());
        if (e.getExportStatus() != null) {
            ObjectNode status = out.putObject("ExportStatus");
            status.put("StatusCode", e.getExportStatus());
        }
        return out;
    }

    private ObjectNode serializeExecution(ExportExecution exec) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ExecutionId", exec.getExecutionId());
        out.put("ExportArn", exec.getExportArn());
        if (exec.getExportStatus() != null) {
            ObjectNode status = out.putObject("ExecutionStatus");
            status.put("StatusCode", exec.getExportStatus());
            if (exec.getCreatedBy() != null) status.put("CreatedBy", exec.getCreatedBy());
            if (exec.getCreatedAt() > 0) status.put("CreatedAt",
                    Instant.ofEpochMilli(exec.getCreatedAt()).toString());
            if (exec.getCompletedAt() > 0) status.put("CompletedAt",
                    Instant.ofEpochMilli(exec.getCompletedAt()).toString());
            if (exec.getStatusReason() != null) status.put("StatusReason", exec.getStatusReason());
        }
        return out;
    }

    private static String stringOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }
}
