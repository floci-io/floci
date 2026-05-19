package io.github.hectorvent.floci.services.configservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleEvaluationStatus;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleSource;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorder;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorderStatus;
import io.github.hectorvent.floci.services.configservice.model.ConformancePack;
import io.github.hectorvent.floci.services.configservice.model.ConformancePackStatusDetail;
import io.github.hectorvent.floci.services.configservice.model.DeliveryChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
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
            case "DescribeConfigRuleEvaluationStatus" -> describeConfigRuleEvaluationStatus(request, region);
            case "StartConfigRulesEvaluation" -> startConfigRulesEvaluation(request, region);
            case "PutConformancePack" -> putConformancePack(request, region);
            case "DeleteConformancePack" -> deleteConformancePack(request, region);
            case "DescribeConformancePacks" -> describeConformancePacks(request, region);
            case "DescribeConformancePackStatus" -> describeConformancePackStatus(request, region);
            case "PutConfigurationRecorder" -> putConfigurationRecorder(request, region);
            case "DescribeConfigurationRecorders" -> describeConfigurationRecorders(request, region);
            case "StartConfigurationRecorder" -> startConfigurationRecorder(request, region);
            case "StopConfigurationRecorder" -> stopConfigurationRecorder(request, region);
            case "DescribeConfigurationRecorderStatus" -> describeConfigurationRecorderStatus(request, region);
            case "PutDeliveryChannel" -> putDeliveryChannel(request, region);
            case "DescribeDeliveryChannels" -> describeDeliveryChannels(request, region);
            case "TagResource" -> tagResource(request);
            case "UntagResource" -> untagResource(request);
            case "ListTagsForResource" -> listTagsForResource(request);
            default -> throw new io.github.hectorvent.floci.core.common.AwsException(
                    "InvalidAction", "Could not find operation " + action, 400);
        };
    }

    // --- Config Rules ---

    private Response putConfigRule(JsonNode req, String region) {
        JsonNode ruleNode = req.path("ConfigRule");
        String ruleName = ruleNode.path("ConfigRuleName").asText(null);
        JsonNode sourceNode = ruleNode.path("Source");
        ConfigRuleSource source = new ConfigRuleSource(
                sourceNode.path("Owner").asText(null),
                sourceNode.path("SourceIdentifier").asText(null));
        service.putConfigRule(region, ruleName, source);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response deleteConfigRule(JsonNode req, String region) {
        String ruleName = req.path("ConfigRuleName").asText(null);
        service.deleteConfigRule(region, ruleName);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeConfigRules(JsonNode req, String region) {
        List<String> ruleNames = extractStringList(req, "ConfigRuleNames");
        List<ConfigRule> rules = service.describeConfigRules(region, ruleNames);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConfigRules", mapper.valueToTree(rules));
        return Response.ok(resp).build();
    }

    private Response describeComplianceByConfigRule(JsonNode req, String region) {
        List<String> ruleNames = extractStringList(req, "ConfigRuleNames");
        List<ConfigRule> rules = service.describeConfigRules(region, ruleNames);
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode arr = resp.putArray("ComplianceByConfigRules");
        for (ConfigRule rule : rules) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("ConfigRuleName", rule.configRuleName());
            ObjectNode compliance = mapper.createObjectNode();
            compliance.put("ComplianceType", "INSUFFICIENT_DATA");
            entry.set("Compliance", compliance);
            arr.add(entry);
        }
        return Response.ok(resp).build();
    }

    private Response describeConfigRuleEvaluationStatus(JsonNode req, String region) {
        List<String> ruleNames = extractStringList(req, "ConfigRuleNames");
        List<ConfigRuleEvaluationStatus> statuses = service.describeConfigRuleEvaluationStatus(region, ruleNames);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConfigRulesEvaluationStatus", mapper.valueToTree(statuses));
        return Response.ok(resp).build();
    }

    private Response startConfigRulesEvaluation(JsonNode req, String region) {
        List<String> ruleNames = extractStringList(req, "ConfigRuleNames");
        service.startConfigRulesEvaluation(region, ruleNames);
        return Response.ok(mapper.createObjectNode()).build();
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
        List<String> names = extractStringList(req, "ConformancePackNames");
        List<ConformancePack> packs = service.describeConformancePacks(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConformancePackDetails", mapper.valueToTree(packs));
        return Response.ok(resp).build();
    }

    private Response describeConformancePackStatus(JsonNode req, String region) {
        List<String> names = extractStringList(req, "ConformancePackNames");
        List<ConformancePackStatusDetail> statuses = service.describeConformancePackStatus(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConformancePackStatusDetails", mapper.valueToTree(statuses));
        return Response.ok(resp).build();
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
}
