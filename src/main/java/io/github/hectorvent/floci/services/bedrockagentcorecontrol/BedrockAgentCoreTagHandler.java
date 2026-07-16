package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Tagging for AgentCore resources, dispatched from the shared {@code /tags/{resourceArn}}
 * route. AgentCore uses the default AWS shape (a {@code "tags"} map, {@code "tagKeys"}
 * query parameter, POST), so only {@link #serviceKey()} and the three operations are
 * overridden.
 */
@ApplicationScoped
public class BedrockAgentCoreTagHandler implements TagHandler {

    private final BedrockAgentCoreControlService service;

    @Inject
    public BedrockAgentCoreTagHandler(BedrockAgentCoreControlService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "bedrock-agentcore";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.getTagsByArn(region, arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagByArn(region, arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagByArn(region, arn, tagKeys);
    }
}
