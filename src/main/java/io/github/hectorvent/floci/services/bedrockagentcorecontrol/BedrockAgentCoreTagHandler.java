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
 * for runtimes and gateways.
 */
@ApplicationScoped
public class BedrockAgentCoreTagHandler implements TagHandler {

    private final BedrockAgentCoreControlService runtimeService;
    private final BedrockAgentCoreGatewayService gatewayService;
    private final EmulatorConfig config;

    @Inject
    public BedrockAgentCoreTagHandler(BedrockAgentCoreControlService runtimeService,
                                      BedrockAgentCoreGatewayService gatewayService,
                                      EmulatorConfig config) {
        this.runtimeService = runtimeService;
        this.gatewayService = gatewayService;
        this.config = config;
    }

    @Override
    public String serviceKey() {
        return "bedrock-agentcore";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return isGateway(region, arn) ? gatewayService.getTagsByArn(region, arn)
                : runtimeService.getTagsByArn(region, arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (isGateway(region, arn)) {
            gatewayService.tagByArn(region, arn, tags);
        } else {
            runtimeService.tagByArn(region, arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (isGateway(region, arn)) {
            gatewayService.untagByArn(region, arn, tagKeys);
        } else {
            runtimeService.untagByArn(region, arn, tagKeys);
        }
    }

    private boolean isGateway(String region, String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":");
        if (parts.length < 6) {
            throw new AwsException("ValidationException",
                    "Tagging is not supported for this AgentCore resource: " + arn, 400);
        }
        requireLocalIdentity(region, arn, parts);
        if (parts[5].startsWith("gateway/")) {
            return true;
        }
        if (parts[5].startsWith("agent/")) {
            return false;
        }
        // Neither runtime nor gateway → not a taggable AgentCore resource.
        throw new AwsException("ValidationException",
                "Tagging is not supported for this AgentCore resource: " + arn, 400);
    }

    /**
     * Both services resolve a resource from the ARN's id suffix alone, so without this a caller
     * could reach a local runtime or gateway through an ARN naming another account or region and
     * read or change its tags. Reported as not found rather than a validation error, so the
     * response does not confirm what exists behind the foreign identity.
     */
    private void requireLocalIdentity(String region, String arn, String[] parts) {
        if (!region.equals(parts[3]) || !config.defaultAccountId().equals(parts[4])) {
            throw new AwsException("ResourceNotFoundException",
                    "AgentCore resource not found: " + arn, 404);
        }
    }
}
