package io.floci.aws.services.bedrockagentcorecontrol;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Tagging for AgentCore resources, dispatched from the shared {@code /tags/{resourceArn}}
 * route. All AgentCore resources share the ARN service segment {@code bedrock-agentcore},
 * so this handler further dispatches by the resource type in the ARN. AWS supports tagging
 * for runtimes, runtime endpoints, gateways, memories and harnesses.
 */
@ApplicationScoped
public class BedrockAgentCoreTagHandler implements TagHandler {

    private final BedrockAgentCoreControlService runtimeService;
    private final BedrockAgentCoreGatewayService gatewayService;
    private final BedrockAgentCoreMemoryService memoryService;
    private final BedrockAgentCoreHarnessRegistry harnessRegistry;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockAgentCoreTagHandler(BedrockAgentCoreControlService runtimeService,
                                      BedrockAgentCoreGatewayService gatewayService,
                                      BedrockAgentCoreMemoryService memoryService,
                                      BedrockAgentCoreHarnessRegistry harnessRegistry,
                                      RegionResolver regionResolver) {
        this.runtimeService = runtimeService;
        this.gatewayService = gatewayService;
        this.memoryService = memoryService;
        this.harnessRegistry = harnessRegistry;
        this.regionResolver = regionResolver;
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
            case ENDPOINT -> runtimeService.getEndpointTagsByArn(region, arn);
            case HARNESS -> harnessRegistry.getTagsByArn(region, arn);
        };
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        switch (kind(region, arn)) {
            case GATEWAY -> gatewayService.tagByArn(region, arn, tags);
            case MEMORY -> memoryService.tagByArn(region, arn, tags);
            case RUNTIME -> runtimeService.tagByArn(region, arn, tags);
            case ENDPOINT -> runtimeService.tagEndpointByArn(region, arn, tags);
            case HARNESS -> harnessRegistry.tagByArn(region, arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        switch (kind(region, arn)) {
            case GATEWAY -> gatewayService.untagByArn(region, arn, tagKeys);
            case MEMORY -> memoryService.untagByArn(region, arn, tagKeys);
            case RUNTIME -> runtimeService.untagByArn(region, arn, tagKeys);
            case ENDPOINT -> runtimeService.untagEndpointByArn(region, arn, tagKeys);
            case HARNESS -> harnessRegistry.untagByArn(region, arn, tagKeys);
        }
    }

    private enum ResourceKind { RUNTIME, ENDPOINT, GATEWAY, MEMORY, HARNESS }

    private ResourceKind kind(String region, String arn) {
        String resource = BedrockAgentCoreArns.resource(arn);
        if (resource == null) {
            throw new AwsException("ValidationException",
                    "Tagging is not supported for this AgentCore resource: " + arn, 400);
        }
        requireLocalIdentity(region, arn, AwsArnUtils.parse(arn));
        if (resource.startsWith("gateway/")) {
            return ResourceKind.GATEWAY;
        }
        if (resource.startsWith("agentEndpoint/")) {
            return ResourceKind.ENDPOINT;
        }
        if (resource.startsWith("agent/")) {
            return ResourceKind.RUNTIME;
        }
        if (resource.startsWith("runtime/")) {
            return resource.contains("/runtime-endpoint/") ? ResourceKind.ENDPOINT : ResourceKind.RUNTIME;
        }
        if (resource.startsWith("memory/")) {
            return ResourceKind.MEMORY;
        }
        if (resource.startsWith("harness/")) {
            return ResourceKind.HARNESS;
        }
        // Not a runtime, gateway, memory or harness → not a taggable AgentCore resource.
        throw new AwsException("ValidationException",
                "Tagging is not supported for this AgentCore resource: " + arn, 400);
    }

    /**
     * The runtime, gateway, memory and harness services resolve a resource from the ARN's id
     * suffix alone, so without this a caller could reach a local runtime, runtime endpoint,
     * gateway, memory or harness through an ARN naming another account, region or partition and
     * read or change its tags. Reported as not found rather than a
     * validation error, so the response does not confirm what exists behind the foreign identity.
     */
    private void requireLocalIdentity(String region, String arn, AwsArnUtils.Arn parsed) {
        if (!regionResolver.partitionForRegion(region).equals(parsed.partition())
                || !region.equals(parsed.region()) || !regionResolver.getAccountId().equals(parsed.accountId())) {
            throw new AwsException("ResourceNotFoundException",
                    "AgentCore resource not found: " + arn, 404);
        }
    }
}
