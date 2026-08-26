package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Tagging for AgentCore resources, dispatched from the shared {@code /tags/{resourceArn}}
 * route. All AgentCore resources share the ARN service segment {@code bedrock-agentcore},
 * so this handler further dispatches by the resource type in the ARN. AWS supports tagging
 * for runtimes, gateways, and memories.
 */
@ApplicationScoped
public class BedrockAgentCoreTagHandler implements TagHandler {

    private final BedrockAgentCoreControlService runtimeService;
    private final BedrockAgentCoreGatewayService gatewayService;
    private final BedrockAgentCoreMemoryService memoryService;
    private final EmulatorConfig config;

    @Inject
    public BedrockAgentCoreTagHandler(BedrockAgentCoreControlService runtimeService,
                                      BedrockAgentCoreGatewayService gatewayService,
                                      BedrockAgentCoreMemoryService memoryService,
                                      EmulatorConfig config) {
        this.runtimeService = runtimeService;
        this.gatewayService = gatewayService;
        this.memoryService = memoryService;
        this.config = config;
    }

    @Override
    public String serviceKey() {
        return "bedrock-agentcore";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return switch (kind(region, arn)) {
            case GATEWAY -> gatewayService.getTagsByArn(region, arn);
            case MEMORY -> memoryService.getTagsByArn(region, arn);
            case RUNTIME -> runtimeService.getTagsByArn(region, arn);
        };
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        switch (kind(region, arn)) {
            case GATEWAY -> gatewayService.tagByArn(region, arn, tags);
            case MEMORY -> memoryService.tagByArn(region, arn, tags);
            case RUNTIME -> runtimeService.tagByArn(region, arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        switch (kind(region, arn)) {
            case GATEWAY -> gatewayService.untagByArn(region, arn, tagKeys);
            case MEMORY -> memoryService.untagByArn(region, arn, tagKeys);
            case RUNTIME -> runtimeService.untagByArn(region, arn, tagKeys);
        }
    }

    private enum ResourceKind { RUNTIME, GATEWAY, MEMORY }

    private ResourceKind kind(String region, String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":");
        if (parts.length < 6) {
            throw new AwsException("ValidationException",
                    "Tagging is not supported for this AgentCore resource: " + arn, 400);
        }
        requireLocalIdentity(region, arn, parts);
        if (parts[5].startsWith("gateway/")) {
            return ResourceKind.GATEWAY;
        }
        if (parts[5].startsWith("agent/")) {
            return ResourceKind.RUNTIME;
        }
        if (parts[5].startsWith("memory/")) {
            return ResourceKind.MEMORY;
        }
        // Not a runtime, gateway, or memory → not a taggable AgentCore resource.
        throw new AwsException("ValidationException",
                "Tagging is not supported for this AgentCore resource: " + arn, 400);
    }

    /**
     * All three services resolve a resource from the ARN's id suffix alone, so without this a
     * caller could reach a local runtime, gateway, or memory through an ARN naming another
     * account or region and read or change its tags. Reported as not found rather than a
     * validation error, so the response does not confirm what exists behind the foreign identity.
     */
    private void requireLocalIdentity(String region, String arn, String[] parts) {
        if (!region.equals(parts[3]) || !config.defaultAccountId().equals(parts[4])) {
            throw new AwsException("ResourceNotFoundException",
                    "AgentCore resource not found: " + arn, 404);
        }
    }
}
