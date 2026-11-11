package io.floci.aws.services.xray;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.xray.model.SamplingRule;
import io.floci.aws.services.xray.model.Trace;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * X-Ray's REST JSON routes, mounted under {@link XrayRouteFilter#INTERNAL_PREFIX}.
 *
 * <p>Each method is one AWS operation and is named for it, which is what the docs action table is
 * generated from. The paths are the ones AWS declares: {@code POST /TraceSegments} for
 * {@code PutTraceSegments} and {@code POST /Traces} for {@code BatchGetTraces}.
 */
@Path(XrayRouteFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class XrayController {

    private final XrayService xrayService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public XrayController(XrayService xrayService, RegionResolver regionResolver,
                          ObjectMapper objectMapper) {
        this.xrayService = xrayService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/TraceSegments")
    public Response putTraceSegments(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        List<String> documents = new ArrayList<>();
        for (JsonNode document : request.path("TraceSegmentDocuments")) {
            documents.add(document.isTextual() ? document.asText() : document.toString());
        }
        List<XrayService.UnprocessedSegment> unprocessed =
                xrayService.putTraceSegments(documents, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode rejected = response.putArray("UnprocessedTraceSegments");
        for (XrayService.UnprocessedSegment segment : unprocessed) {
            ObjectNode entry = rejected.addObject();
            entry.put("Id", segment.id());
            entry.put("ErrorCode", segment.errorCode());
            entry.put("Message", segment.message());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/Traces")
    public Response batchGetTraces(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        List<String> traceIds = new ArrayList<>();
        for (JsonNode id : request.path("TraceIds")) {
            traceIds.add(id.asText());
        }
        XrayService.BatchGetResult result = xrayService.batchGetTraces(traceIds, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode traces = response.putArray("Traces");
        for (Trace trace : result.traces()) {
            ObjectNode entry = traces.addObject();
            entry.put("Id", trace.getId());
            entry.put("Duration", trace.getEndTime() - trace.getStartTime());
            ArrayNode segments = entry.putArray("Segments");
            for (String document : trace.getDocuments()) {
                ObjectNode segment = segments.addObject();
                segment.put("Id", segmentId(document));
                segment.put("Document", document);
            }
        }
        ArrayNode unprocessed = response.putArray("UnprocessedTraceIds");
        for (String id : result.unprocessedIds()) {
            unprocessed.add(id);
        }
        return Response.ok(response).build();
    }


    @POST
    @Path("/TraceSummaries")
    public Response getTraceSummaries(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        double startTime = requiredNumber(request, "StartTime");
        double endTime = requiredNumber(request, "EndTime");
        XrayService.SummaryPage page = xrayService.traceSummaries(startTime, endTime,
                request.path("FilterExpression").asText(null), null,
                request.path("NextToken").asText(null), region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("TraceSummaries");
        for (XrayService.TraceSummary summary : page.items()) {
            summaries.add(summaryNode(summary));
        }
        response.put("ApproximateTime", page.approximateTime());
        response.put("TracesProcessedCount", page.processedCount());
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/ServiceGraph")
    public Response getServiceGraph(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        double startTime = requiredNumber(request, "StartTime");
        double endTime = requiredNumber(request, "EndTime");

        ObjectNode response = objectMapper.createObjectNode();
        response.put("StartTime", startTime);
        response.put("EndTime", endTime);
        ArrayNode services = response.putArray("Services");
        for (XrayGraph.Node node : xrayService.serviceGraph(startTime, endTime, region)) {
            services.add(graphNode(node));
        }
        response.put("ContainsOldGroupVersions", false);
        return Response.ok(response).build();
    }

    @POST
    @Path("/TraceGraph")
    public Response getTraceGraph(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        List<String> traceIds = new ArrayList<>();
        for (JsonNode id : request.path("TraceIds")) {
            traceIds.add(id.asText());
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode services = response.putArray("Services");
        for (XrayGraph.Node node : xrayService.traceGraph(traceIds, region)) {
            services.add(graphNode(node));
        }
        return Response.ok(response).build();
    }

    private ObjectNode summaryNode(XrayService.TraceSummary summary) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("Id", summary.id());
        entry.put("StartTime", summary.startTime());
        entry.put("Duration", summary.duration());
        entry.put("ResponseTime", summary.duration());
        entry.put("HasError", summary.hasError());
        entry.put("HasFault", summary.hasFault());
        entry.put("HasThrottle", summary.hasThrottle());
        if (summary.httpMethod() != null || summary.httpUrl() != null
                || summary.httpStatus() != null) {
            ObjectNode http = entry.putObject("Http");
            if (summary.httpMethod() != null) {
                http.put("HttpMethod", summary.httpMethod());
            }
            if (summary.httpUrl() != null) {
                http.put("HttpURL", summary.httpUrl());
            }
            if (summary.httpStatus() != null) {
                http.put("HttpStatus", summary.httpStatus());
            }
        }
        if (summary.entryPoint() != null) {
            entry.putObject("EntryPoint").put("Name", summary.entryPoint());
        }
        ArrayNode serviceIds = entry.putArray("ServiceIds");
        for (String service : summary.services()) {
            serviceIds.addObject().put("Name", service);
        }
        if (!summary.annotations().isEmpty()) {
            ObjectNode annotations = entry.putObject("Annotations");
            summary.annotations().forEach((key, value) -> {
                ObjectNode annotationValue = annotations.putArray(key).addObject()
                        .putObject("AnnotationValue");
                // AnnotationValue names the same three types the model does, so a number stays a
                // number: a client reading annotations by type gets what the segment carried.
                if (value instanceof Number number) {
                    annotationValue.put("NumberValue", number.doubleValue());
                } else if (value instanceof Boolean flag) {
                    annotationValue.put("BooleanValue", flag);
                } else {
                    annotationValue.put("StringValue", String.valueOf(value));
                }
            });
        }
        return entry;
    }

    private ObjectNode graphNode(XrayGraph.Node node) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("ReferenceId", node.referenceId());
        entry.put("Name", node.name());
        if (node.type() != null) {
            entry.put("Type", node.type());
        }
        entry.putArray("Names").add(node.name());
        entry.put("State", node.state());
        entry.put("StartTime", node.startTime());
        entry.put("EndTime", node.endTime());
        ObjectNode statistics = entry.putObject("SummaryStatistics");
        statistics.put("OkCount", node.okCount());
        ObjectNode errors = statistics.putObject("ErrorStatistics");
        errors.put("ThrottleCount", node.throttleCount());
        // OtherCount is the errors that are not throttles; TotalCount is every 4xx, throttles
        // included, or a throttled call would vanish from the error total.
        errors.put("OtherCount", node.errorCount());
        errors.put("TotalCount", node.errorCount() + node.throttleCount());
        ObjectNode faults = statistics.putObject("FaultStatistics");
        faults.put("OtherCount", node.faultCount());
        faults.put("TotalCount", node.faultCount());
        statistics.put("TotalCount", node.totalCount());
        statistics.put("TotalResponseTime", node.totalResponseTime());
        ArrayNode edges = entry.putArray("Edges");
        for (XrayGraph.Edge edge : node.edges()) {
            ObjectNode edgeNode = edges.addObject();
            edgeNode.put("ReferenceId", edge.referenceId());
            edgeNode.put("StartTime", edge.startTime());
            edgeNode.put("EndTime", edge.endTime());
            ObjectNode edgeStatistics = edgeNode.putObject("SummaryStatistics");
            edgeStatistics.put("OkCount", edge.okCount());
            ObjectNode edgeErrors = edgeStatistics.putObject("ErrorStatistics");
            edgeErrors.put("ThrottleCount", edge.throttleCount());
            edgeErrors.put("OtherCount", edge.errorCount());
            edgeErrors.put("TotalCount", edge.errorCount() + edge.throttleCount());
            ObjectNode edgeFaults = edgeStatistics.putObject("FaultStatistics");
            edgeFaults.put("OtherCount", edge.faultCount());
            edgeFaults.put("TotalCount", edge.faultCount());
            edgeStatistics.put("TotalCount", edge.totalCount());
            edgeStatistics.put("TotalResponseTime", edge.totalResponseTime());
            histogram(edgeNode.putArray("ResponseTimeHistogram"), edge.responseTimeHistogram());
        }
        histogram(entry.putArray("DurationHistogram"), node.durationHistogram());
        return entry;
    }

    private static void histogram(ArrayNode target, List<XrayGraph.HistogramEntry> entries) {
        for (XrayGraph.HistogramEntry entry : entries) {
            ObjectNode bucket = target.addObject();
            bucket.put("Value", entry.value());
            bucket.put("Count", entry.count());
        }
    }

    private static double requiredNumber(JsonNode request, String member) {
        JsonNode value = request.path(member);
        if (!value.isNumber()) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
        return value.asDouble();
    }

    @POST
    @Path("/GetSamplingRules")
    public Response getSamplingRules(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode records = response.putArray("SamplingRuleRecords");
        for (SamplingRule rule : xrayService.samplingRules(region)) {
            records.add(samplingRuleRecord(rule));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/CreateSamplingRule")
    public Response createSamplingRule(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        SamplingRule rule = xrayService.createSamplingRule(readTree(body), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SamplingRuleRecord", samplingRuleRecord(rule));
        return Response.ok(response).build();
    }

    @POST
    @Path("/UpdateSamplingRule")
    public Response updateSamplingRule(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        SamplingRule rule = xrayService.updateSamplingRule(readTree(body), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SamplingRuleRecord", samplingRuleRecord(rule));
        return Response.ok(response).build();
    }

    @POST
    @Path("/DeleteSamplingRule")
    public Response deleteSamplingRule(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        SamplingRule rule = xrayService.deleteSamplingRule(readTree(body), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SamplingRuleRecord", samplingRuleRecord(rule));
        return Response.ok(response).build();
    }

    @POST
    @Path("/SamplingTargets")
    public Response getSamplingTargets(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        XrayService.Targets targets = xrayService.samplingTargets(readTree(body), region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode documents = response.putArray("SamplingTargetDocuments");
        for (XrayService.Target target : targets.documents()) {
            ObjectNode document = documents.addObject();
            document.put("RuleName", target.ruleName());
            document.put("FixedRate", target.fixedRate());
            document.put("ReservoirQuota", target.reservoirQuota());
            document.put("ReservoirQuotaTTL", target.reservoirQuotaTtl());
            document.put("Interval", target.interval());
        }
        response.put("LastRuleModification", targets.lastRuleModification());
        ArrayNode unprocessed = response.putArray("UnprocessedStatistics");
        for (XrayService.UnprocessedTarget target : targets.unprocessed()) {
            ObjectNode entry = unprocessed.addObject();
            entry.put("RuleName", target.ruleName());
            entry.put("ErrorCode", target.errorCode());
            entry.put("Message", target.message());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/TelemetryRecords")
    public Response putTelemetryRecords(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        xrayService.putTelemetryRecords(readTree(body), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode samplingRuleRecord(SamplingRule rule) {
        ObjectNode record = objectMapper.createObjectNode();
        ObjectNode ruleNode = record.putObject("SamplingRule");
        ruleNode.put("RuleName", rule.getRuleName());
        ruleNode.put("RuleARN", rule.getRuleArn());
        ruleNode.put("ResourceARN", rule.getResourceArn());
        ruleNode.put("Priority", rule.getPriority());
        ruleNode.put("FixedRate", rule.getFixedRate());
        ruleNode.put("ReservoirSize", rule.getReservoirSize());
        ruleNode.put("ServiceName", rule.getServiceName());
        ruleNode.put("ServiceType", rule.getServiceType());
        ruleNode.put("Host", rule.getHost());
        ruleNode.put("HTTPMethod", rule.getHttpMethod());
        ruleNode.put("URLPath", rule.getUrlPath());
        ruleNode.put("Version", rule.getVersion());
        if (!rule.getAttributes().isEmpty()) {
            ObjectNode attributes = ruleNode.putObject("Attributes");
            rule.getAttributes().forEach(attributes::put);
        }
        record.put("CreatedAt", rule.getCreatedAt());
        record.put("ModifiedAt", rule.getModifiedAt());
        return record;
    }

    /** The segment id a stored document carries, which the segment entry reports beside it. */
    private String segmentId(String document) {
        try {
            return objectMapper.readTree(document).path("id").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
