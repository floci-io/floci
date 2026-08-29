package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.configservice.AwsConfigService;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleSource;
import io.github.hectorvent.floci.services.configservice.model.CustomPolicyDetails;
import io.github.hectorvent.floci.services.configservice.model.EvaluationModeConfiguration;
import io.github.hectorvent.floci.services.configservice.model.Scope;
import io.github.hectorvent.floci.services.configservice.model.SourceDetail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class ConfigCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ConfigCfnProvisioner.class);
    private static final String CONFIG_RULE = "AWS::Config::ConfigRule";

    private final AwsConfigService configService;

    @Inject
    public ConfigCfnProvisioner(AwsConfigService configService) {
        this.configService = configService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CONFIG_RULE);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        JsonNode resolved = ctx.engine().resolveNode(props);
        String previousName = resource.getPhysicalId();
        String name = text(resolved, "ConfigRuleName");
        if (name == null || name.isBlank()) {
            name = previousName == null
                    ? ctx.generatePhysicalName(resource.getLogicalId(), 128, false)
                    : previousName;
        }
        ConfigRule rule = configService.putConfigRule(ctx.region(), new ConfigRule(
                name, null, null, text(resolved, "Description"), scope(resolved.path("Scope")),
                source(resolved.path("Source")), inputParameters(resolved.path("InputParameters")),
                text(resolved, "MaximumExecutionFrequency"), null, null,
                evaluationModes(resolved.path("EvaluationModes"))));
        resource.setPhysicalId(rule.configRuleName());
        resource.getAttributes().put("Arn", rule.configRuleArn());
        resource.getAttributes().put("ConfigRuleId", rule.configRuleId());
        if (previousName != null && !previousName.equals(name)) {
            try {
                deleteIfPresent(ctx.region(), previousName);
            } catch (RuntimeException e) {
                // old rule may still be referenced elsewhere; new rule is already tracked
                LOG.warnv("Config CFN replacement cleanup of rule {0} tolerated: {1}",
                        previousName, e.getMessage());
            }
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        deleteIfPresent(region, physicalId);
    }

    private void deleteIfPresent(String region, String name) {
        if (name == null) {
            return;
        }
        if (configService.describeConfigRules(region, List.of()).stream()
                .anyMatch(rule -> name.equals(rule.configRuleName()))) {
            configService.deleteConfigRule(region, name);
        }
    }

    private Scope scope(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        return new Scope(strings(node.path("ComplianceResourceTypes")), text(node, "TagKey"),
                text(node, "TagValue"), text(node, "ComplianceResourceId"));
    }

    private ConfigRuleSource source(JsonNode node) {
        List<SourceDetail> details = new ArrayList<>();
        JsonNode detailNodes = node.path("SourceDetails");
        if (detailNodes.isArray()) {
            for (JsonNode detail : detailNodes) {
                details.add(new SourceDetail(text(detail, "EventSource"), text(detail, "MessageType"),
                        text(detail, "MaximumExecutionFrequency")));
            }
        }
        JsonNode policy = node.path("CustomPolicyDetails");
        CustomPolicyDetails customPolicy = policy.isObject()
                ? new CustomPolicyDetails(text(policy, "PolicyRuntime"), text(policy, "PolicyText"),
                        policy.has("EnableDebugLogDelivery")
                                ? policy.get("EnableDebugLogDelivery").asBoolean() : null)
                : null;
        return new ConfigRuleSource(text(node, "Owner"), text(node, "SourceIdentifier"),
                details.isEmpty() ? null : details, customPolicy);
    }

    private List<EvaluationModeConfiguration> evaluationModes(JsonNode nodes) {
        if (!nodes.isArray()) {
            return null;
        }
        List<EvaluationModeConfiguration> modes = new ArrayList<>();
        nodes.forEach(node -> modes.add(new EvaluationModeConfiguration(text(node, "Mode"))));
        return modes;
    }

    private List<String> strings(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        if (nodes.isArray()) {
            nodes.forEach(node -> values.add(node.asText()));
        }
        return values;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String inputParameters(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }
}
