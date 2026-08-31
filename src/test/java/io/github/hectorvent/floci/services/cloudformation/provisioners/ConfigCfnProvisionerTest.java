package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.configservice.AwsConfigService;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigCfnProvisionerTest {

    private final AwsConfigService config = mock(AwsConfigService.class);
    private final ConfigCfnProvisioner provisioner = new ConfigCfnProvisioner(config);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void configRuleMapsCustomPolicyAndSetsAwsReferences() {
        ObjectNode props = mapper.createObjectNode()
                .put("ConfigRuleName", "tenant-isolation")
                .put("Description", "requires tenant tags");
        props.set("Scope", mapper.createObjectNode().set("ComplianceResourceTypes",
                mapper.createArrayNode().add("AWS::S3::Bucket").add("AWS::SQS::Queue")));
        ObjectNode source = mapper.createObjectNode().put("Owner", "CUSTOM_POLICY");
        source.set("SourceDetails", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("EventSource", "aws.config")
                .put("MessageType", "ConfigurationItemChangeNotification")));
        source.set("CustomPolicyDetails", mapper.createObjectNode()
                .put("PolicyRuntime", "guard-2.x.x")
                .put("PolicyText", "rule tenant_isolation { true }")
                .put("EnableDebugLogDelivery", false));
        props.set("Source", source);
        ConfigRule stored = new ConfigRule("tenant-isolation", "arn:rule", "config-rule-123",
                null, null, null, null, null, "ACTIVE", null, List.of());
        when(config.putConfigRule(eq("us-east-1"), any())).thenReturn(stored);
        when(config.describeConfigRules("us-east-1", List.of())).thenReturn(List.of());
        StackResource resource = resource();

        provisioner.provision(resource, props, context());

        ArgumentCaptor<ConfigRule> desired = ArgumentCaptor.forClass(ConfigRule.class);
        verify(config).putConfigRule(eq("us-east-1"), desired.capture());
        assertEquals(List.of("AWS::S3::Bucket", "AWS::SQS::Queue"),
                desired.getValue().scope().complianceResourceTypes());
        assertEquals("CUSTOM_POLICY", desired.getValue().source().owner());
        assertEquals("guard-2.x.x", desired.getValue().source().customPolicyDetails().policyRuntime());
        assertEquals("tenant-isolation", resource.getPhysicalId());
        assertEquals("arn:rule", resource.getAttributes().get("Arn"));
        assertEquals("config-rule-123", resource.getAttributes().get("ConfigRuleId"));
    }

    @Test
    void inputParametersGivenAsInlineJsonObjectIsSerializedNotDropped() {
        ObjectNode props = mapper.createObjectNode()
                .put("ConfigRuleName", "tenant-isolation");
        props.set("Source", mapper.createObjectNode().put("Owner", "AWS")
                .put("SourceIdentifier", "S3_BUCKET_VERSIONING_ENABLED"));
        props.set("InputParameters", mapper.createObjectNode().put("minimumRetentionDays", "30"));
        ConfigRule stored = new ConfigRule("tenant-isolation", "arn:rule", "config-rule-123",
                null, null, null, null, null, "ACTIVE", null, List.of());
        when(config.putConfigRule(eq("us-east-1"), any())).thenReturn(stored);
        when(config.describeConfigRules("us-east-1", List.of())).thenReturn(List.of());
        StackResource resource = resource();

        provisioner.provision(resource, props, context());

        ArgumentCaptor<ConfigRule> desired = ArgumentCaptor.forClass(ConfigRule.class);
        verify(config).putConfigRule(eq("us-east-1"), desired.capture());
        assertEquals("{\"minimumRetentionDays\":\"30\"}", desired.getValue().inputParameters());
    }

    @Test
    void renameTracksNewRuleEvenWhenOldRuleDeleteFails() {
        ObjectNode props = mapper.createObjectNode().put("ConfigRuleName", "renamed-rule");
        props.set("Source", mapper.createObjectNode().put("Owner", "AWS")
                .put("SourceIdentifier", "S3_BUCKET_VERSIONING_ENABLED"));
        ConfigRule stored = new ConfigRule("renamed-rule", "arn:renamed", "config-rule-456",
                null, null, null, null, null, "ACTIVE", null, List.of());
        when(config.putConfigRule(eq("us-east-1"), any())).thenReturn(stored);
        when(config.describeConfigRules("us-east-1", List.of()))
                .thenReturn(List.of(new ConfigRule("old-rule", "arn:old", "config-rule-123",
                        null, null, null, null, null, "ACTIVE", null, List.of())));
        org.mockito.Mockito.doThrow(new RuntimeException("rule in use"))
                .when(config).deleteConfigRule("us-east-1", "old-rule");
        StackResource resource = resource();
        resource.setPhysicalId("old-rule");

        provisioner.provision(resource, props, context());

        assertEquals("renamed-rule", resource.getPhysicalId());
        assertEquals("arn:renamed", resource.getAttributes().get("Arn"));
    }

    @Test
    void deleteIsIdempotent() {
        when(config.describeConfigRules("us-east-1", List.of())).thenReturn(List.of());

        provisioner.delete("AWS::Config::ConfigRule", "missing", "us-east-1");

        verify(config).describeConfigRules("us-east-1", List.of());
    }

    private ProvisionContext context() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any(JsonNode.class))).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "111122223333", "stack");
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("TenantIsolation");
        resource.setResourceType("AWS::Config::ConfigRule");
        resource.setAttributes(new HashMap<>());
        return resource;
    }
}
