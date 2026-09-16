package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Routes AWS Marketplace Deployment's shared /tags/{resourceArn} endpoints. */
@ApplicationScoped
public class MarketplaceDeploymentTagHandler implements TagHandler {

    private final MarketplaceDeploymentService service;
    private final ObjectMapper mapper;

    @Inject
    public MarketplaceDeploymentTagHandler(MarketplaceDeploymentService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public String serviceKey() {
        return "aws-marketplace";
    }

    @Override
    public boolean strictTagValidation() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        JsonNode tags = service.listTagsForResource(arn, region).path("tags");
        Map<String, String> result = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return result;
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        ObjectNode request = mapper.createObjectNode();
        ObjectNode tagNode = request.putObject("tags");
        tags.forEach(tagNode::put);
        service.tagResource(arn, request, region);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagResource(arn, tagKeys, region);
    }
}
