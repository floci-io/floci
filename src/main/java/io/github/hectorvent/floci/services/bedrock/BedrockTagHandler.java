package io.github.hectorvent.floci.services.bedrock;

import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.TaggingApiOnly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Lets Resource Groups Tagging API writes reach a guardrail's own tags (#6235).
 *
 * <p>A separate {@link TaggingApiOnly} bean because Bedrock tags through its own
 * {@code TagResource} action: a default-qualified handler would also serve guardrails on
 * {@code /tags/{arn}}, which AWS does not define.
 */
@ApplicationScoped
@TaggingApiOnly
public class BedrockTagHandler implements TagHandler {

    private final BedrockService bedrockService;

    @Inject
    public BedrockTagHandler(BedrockService bedrockService) {
        this.bedrockService = bedrockService;
    }

    @Override
    public String serviceKey() {
        return "bedrock";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return bedrockService.listTags(arn, region);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        bedrockService.tagResource(arn, tags, region);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        bedrockService.untagResource(arn, tagKeys, region);
    }
}
