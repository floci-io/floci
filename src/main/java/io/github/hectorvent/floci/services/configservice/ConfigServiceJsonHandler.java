package io.github.hectorvent.floci.services.configservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.configservice.model.ConfigEvaluation;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorder;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorderStatus;
import io.github.hectorvent.floci.services.configservice.model.ConformancePack;
import io.github.hectorvent.floci.services.configservice.model.DeliveryChannel;
import io.github.hectorvent.floci.services.configservice.model.RetentionConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ConfigServiceJsonHandler {

    private final AwsConfigService service;
    private final ObjectMapper mapper;

    @Inject
    public ConfigServiceJsonHandler(AwsConfigService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "PutConfigRule" -> putConfigRule(request, region);
            case "DeleteConfigRule" -> deleteConfigRule(request, region);
            case "DescribeConfigRules" -> describeConfigRules(request, region);
            case "DescribeComplianceByConfigRule" -> describeComplianceByConfigRule(request, region);
            case "DescribeComplianceByResource" -> describeComplianceByResource(request, region);
            case "DescribeConfigRuleEvaluationStatus" -> describeConfigRuleEvaluationStatus(request, region);
            case "StartConfigRulesEvaluation" -> startConfigRulesEvaluation(request, region);
            case "PutEvaluations" -> putEvaluations(request, region);
            case "PutExternalEvaluation" -> putExternalEvaluation(request, region);
            case "DeleteEvaluationResults" -> deleteEvaluationResults(request, region);
            case "GetComplianceDetailsByConfigRule" -> getComplianceDetailsByConfigRule(request, region);
            case "GetComplianceDetailsByResource" -> getComplianceDetailsByResource(request, region);
            case "GetComplianceSummaryByConfigRule" -> getComplianceSummaryByConfigRule(region);
            case "GetComplianceSummaryByResourceType" -> getComplianceSummaryByResourceType(request, region);
            case "PutConformancePack" -> putConformancePack(request, region);
            case "DeleteConformancePack" -> deleteConformancePack(request, region);
            case "DescribeConformancePacks" -> describeConformancePacks(request, region);
            case "DescribeConformancePackStatus" -> describeConformancePackStatus(request, region);
            case "PutConfigurationRecorder" -> putConfigurationRecorder(request, region);
            case "DescribeConfigurationRecorders" -> describeConfigurationRecorders(request, region);
            case "DeleteConfigurationRecorder" -> deleteConfigurationRecorder(request, region);
            case "StartConfigurationRecorder" -> startConfigurationRecorder(request, region);
            case "StopConfigurationRecorder" -> stopConfigurationRecorder(request, region);
            case "DescribeConfigurationRecorderStatus" -> describeConfigurationRecorderStatus(request, region);
            case "PutDeliveryChannel" -> putDeliveryChannel(request, region);
            case "DescribeDeliveryChannels" -> describeDeliveryChannels(request, region);
            case "DeleteDeliveryChannel" -> deleteDeliveryChannel(request, region);
            case "PutRetentionConfiguration" -> putRetentionConfiguration(request, region);
            case "DescribeRetentionConfigurations" -> describeRetentionConfigurations(request, region);
            case "DeleteRetentionConfiguration" -> deleteRetentionConfiguration(request, region);
            case "TagResource" -> tagResource(request);
            case "UntagResource" -> untagResource(request);
            case "ListTagsForResource" -> listTagsForResource(request);
            default -> throw new io.github.hectorvent.floci.core.common.AwsException(
                    "InvalidAction", "Could not find operation " + action, 400);
        };
    }

    // --- Config Rules ---

    private Response putConfigRule(JsonNode req, String region) throws Exception {
        JsonNode ruleNode = req.path("ConfigRule");
        ConfigRule rule = ruleNode.isObject() ? mapper.treeToValue(ruleNode, ConfigRule.class) : null;
        service.putConfigRule(region, rule);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response deleteConfigRule(JsonNode req, String region) {
        String ruleName = req.path("ConfigRuleName").asText(null);
        service.deleteConfigRule(region, ruleName);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeConfigRules(JsonNode req, String region) {
        AwsConfigService.Paged<ConfigRule> page = service.describeConfigRulesPaged(region,
                extractStringList(req, "ConfigRuleNames"), extractNextToken(req));
        return Response.ok(pagedResponse("ConfigRules", page)).build();
    }

    private Response describeComplianceByConfigRule(JsonNode req, String region) {
        var page = service.describeComplianceByConfigRule(region,
                extractStringList(req, "ConfigRuleNames"),
                extractStringList(req, "ComplianceTypes"),
                extractNextToken(req));
        return Response.ok(pagedResponse("ComplianceByConfigRules", page)).build();
    }

    private Response describeComplianceByResource(JsonNode req, String region) {
        var page = service.describeComplianceByResource(region,
                req.path("ResourceType").asText(null),
                req.path("ResourceId").asText(null),
                extractStringList(req, "ComplianceTypes"),
                extractLimit(req, "Limit"), extractNextToken(req));
        return Response.ok(pagedResponse("ComplianceByResources", page)).build();
    }

    private Response describeConfigRuleEvaluationStatus(JsonNode req, String region) {
        var page = service.describeConfigRuleEvaluationStatus(region,
                extractStringList(req, "ConfigRuleNames"),
                extractLimit(req, "Limit"), extractNextToken(req));
        return Response.ok(pagedResponse("ConfigRulesEvaluationStatus", page)).build();
    }

    private Response startConfigRulesEvaluation(JsonNode req, String region) {
        List<String> ruleNames = extractStringList(req, "ConfigRuleNames");
        service.startConfigRulesEvaluation(region, ruleNames);
        return Response.ok(mapper.createObjectNode()).build();
    }

    // --- Evaluations ---

    private Response putEvaluations(JsonNode req, String region) throws Exception {
        JsonNode evaluationsNode = req.path("Evaluations");
        List<ConfigEvaluation> evaluations = evaluationsNode.isArray()
                ? Arrays.asList(mapper.treeToValue(evaluationsNode, ConfigEvaluation[].class))
                : List.of();
        service.putEvaluations(region,
                req.path("ResultToken").asText(null),
                evaluations,
                req.path("TestMode").asBoolean(false));
        ObjectNode resp = mapper.createObjectNode();
        resp.putArray("FailedEvaluations");
        return Response.ok(resp).build();
    }

    private Response putExternalEvaluation(JsonNode req, String region) throws Exception {
        JsonNode evaluationNode = req.path("ExternalEvaluation");
        ConfigEvaluation evaluation = evaluationNode.isObject()
                ? mapper.treeToValue(evaluationNode, ConfigEvaluation.class)
                : null;
        service.putExternalEvaluation(region, req.path("ConfigRuleName").asText(null), evaluation);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response deleteEvaluationResults(JsonNode req, String region) {
        service.deleteEvaluationResults(region, req.path("ConfigRuleName").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response getComplianceDetailsByConfigRule(JsonNode req, String region) {
        var page = service.getComplianceDetailsByConfigRule(region,
                req.path("ConfigRuleName").asText(null),
                extractStringList(req, "ComplianceTypes"),
                extractLimit(req, "Limit"), extractNextToken(req));
        return Response.ok(pagedResponse("EvaluationResults", page)).build();
    }

    private Response getComplianceDetailsByResource(JsonNode req, String region) {
        var page = service.getComplianceDetailsByResource(region,
                req.path("ResourceType").asText(null),
                req.path("ResourceId").asText(null),
                extractStringList(req, "ComplianceTypes"),
                extractNextToken(req));
        return Response.ok(pagedResponse("EvaluationResults", page)).build();
    }

    private Response getComplianceSummaryByConfigRule(String region) {
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ComplianceSummary", mapper.valueToTree(service.getComplianceSummaryByConfigRule(region)));
        return Response.ok(resp).build();
    }

    private Response getComplianceSummaryByResourceType(JsonNode req, String region) {
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ComplianceSummariesByResourceType", mapper.valueToTree(
                service.getComplianceSummaryByResourceType(region, extractStringList(req, "ResourceTypes"))));
        return Response.ok(resp).build();
    }

    // --- Configuration Recorder ---

    private Response putConfigurationRecorder(JsonNode req, String region) throws Exception {
        ConfigurationRecorder recorder = mapper.treeToValue(req.path("ConfigurationRecorder"), ConfigurationRecorder.class);
        service.putConfigurationRecorder(region, recorder);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeConfigurationRecorders(JsonNode req, String region) {
        List<String> names = extractStringList(req, "ConfigurationRecorderNames");
        List<ConfigurationRecorder> recorders = service.describeConfigurationRecorders(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConfigurationRecorders", mapper.valueToTree(recorders));
        return Response.ok(resp).build();
    }

    private Response deleteConfigurationRecorder(JsonNode req, String region) {
        service.deleteConfigurationRecorder(region, req.path("ConfigurationRecorderName").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response startConfigurationRecorder(JsonNode req, String region) {
        String name = req.path("ConfigurationRecorderName").asText(null);
        service.startConfigurationRecorder(region, name);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response stopConfigurationRecorder(JsonNode req, String region) {
        String name = req.path("ConfigurationRecorderName").asText(null);
        service.stopConfigurationRecorder(region, name);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeConfigurationRecorderStatus(JsonNode req, String region) {
        List<String> names = extractStringList(req, "ConfigurationRecorderNames");
        List<ConfigurationRecorderStatus> statuses = service.describeConfigurationRecorderStatus(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConfigurationRecordersStatus", mapper.valueToTree(statuses));
        return Response.ok(resp).build();
    }

    // --- Delivery Channel ---

    private Response putDeliveryChannel(JsonNode req, String region) throws Exception {
        DeliveryChannel channel = mapper.treeToValue(req.path("DeliveryChannel"), DeliveryChannel.class);
        service.putDeliveryChannel(region, channel);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeDeliveryChannels(JsonNode req, String region) {
        List<String> names = extractStringList(req, "DeliveryChannelNames");
        List<DeliveryChannel> channels = service.describeDeliveryChannels(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("DeliveryChannels", mapper.valueToTree(channels));
        return Response.ok(resp).build();
    }

    private Response deleteDeliveryChannel(JsonNode req, String region) {
        service.deleteDeliveryChannel(region, req.path("DeliveryChannelName").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    // --- Retention Configuration ---

    private Response putRetentionConfiguration(JsonNode req, String region) {
        Integer days = req.hasNonNull("RetentionPeriodInDays") ? req.path("RetentionPeriodInDays").asInt() : null;
        RetentionConfiguration configuration = service.putRetentionConfiguration(region, days);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("RetentionConfiguration", mapper.valueToTree(configuration));
        return Response.ok(resp).build();
    }

    private Response describeRetentionConfigurations(JsonNode req, String region) {
        List<RetentionConfiguration> configurations = service.describeRetentionConfigurations(region,
                extractStringList(req, "RetentionConfigurationNames"));
        ObjectNode resp = mapper.createObjectNode();
        resp.set("RetentionConfigurations", mapper.valueToTree(configurations));
        return Response.ok(resp).build();
    }

    private Response deleteRetentionConfiguration(JsonNode req, String region) {
        service.deleteRetentionConfiguration(region, req.path("RetentionConfigurationName").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    // --- Conformance Packs ---

    private Response putConformancePack(JsonNode req, String region) {
        String packName = req.path("ConformancePackName").asText(null);
        String templateS3Uri = req.has("TemplateS3Uri") ? req.path("TemplateS3Uri").asText(null) : null;
        String templateBody = req.has("TemplateBody") ? req.path("TemplateBody").asText(null) : null;
        ConformancePack pack = service.putConformancePack(region, packName, templateS3Uri, templateBody);
        ObjectNode resp = mapper.createObjectNode();
        resp.put("ConformancePackArn", pack.conformancePackArn());
        return Response.ok(resp).build();
    }

    private Response deleteConformancePack(JsonNode req, String region) {
        String packName = req.path("ConformancePackName").asText(null);
        service.deleteConformancePack(region, packName);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeConformancePacks(JsonNode req, String region) {
        var page = service.describeConformancePacksPaged(region,
                extractStringList(req, "ConformancePackNames"),
                extractLimit(req, "Limit"), extractNextToken(req));
        return Response.ok(pagedResponse("ConformancePackDetails", page)).build();
    }

    private Response describeConformancePackStatus(JsonNode req, String region) {
        var page = service.describeConformancePackStatus(region,
                extractStringList(req, "ConformancePackNames"),
                extractLimit(req, "Limit"), extractNextToken(req));
        return Response.ok(pagedResponse("ConformancePackStatusDetails", page)).build();
    }

    // --- Tagging ---

    private Response tagResource(JsonNode req) {
        String arn = req.path("ResourceArn").asText(null);
        List<Map<String, String>> tagList = new ArrayList<>();
        req.path("Tags").forEach(t -> tagList.add(Map.of(
                "Key", t.path("Key").asText(),
                "Value", t.path("Value").asText())));
        service.tagResource(arn, tagList);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response untagResource(JsonNode req) {
        String arn = req.path("ResourceArn").asText(null);
        List<String> tagKeys = new ArrayList<>();
        req.path("TagKeys").forEach(k -> tagKeys.add(k.asText()));
        service.untagResource(arn, tagKeys);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response listTagsForResource(JsonNode req) {
        String arn = req.path("ResourceArn").asText(null);
        List<Map<String, String>> tagList = service.listTagsForResource(arn);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("Tags", mapper.valueToTree(tagList));
        return Response.ok(resp).build();
    }

    // --- Helpers ---

    private List<String> extractStringList(JsonNode req, String fieldName) {
        List<String> result = new ArrayList<>();
        if (req.has(fieldName)) {
            req.path(fieldName).forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private Integer extractLimit(JsonNode req) {
        return extractLimit(req, "MaxResults");
    }

    private Integer extractLimit(JsonNode req, String fieldName) {
        return req.hasNonNull(fieldName) ? req.path(fieldName).asInt() : null;
    }

    private String extractNextToken(JsonNode req) {
        return req.hasNonNull("NextToken") ? req.path("NextToken").asText() : null;
    }

    private ObjectNode pagedResponse(String fieldName, AwsConfigService.Paged<?> page) {
        ObjectNode resp = mapper.createObjectNode();
        resp.set(fieldName, mapper.valueToTree(page.items()));
        if (page.nextToken() != null) {
            resp.put("NextToken", page.nextToken());
        }
        return resp;
    }
}
