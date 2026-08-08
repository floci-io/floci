package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.codepipeline.CodePipelineService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * CloudFormation provisioning for CodePipeline: {@code AWS::CodePipeline::Pipeline},
 * {@code AWS::CodePipeline::CustomActionType}, and {@code AWS::CodePipeline::Webhook}.
 *
 * <p>CloudFormation properties are PascalCase while the CodePipeline API is camelCase;
 * a recursive first-letter-lowercase transform is lossless for this resource schema, so
 * the resolved properties become the API request directly.</p>
 */
@ApplicationScoped
public class CodePipelineCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CodePipelineCfnProvisioner.class);

    private final CodePipelineService codePipelineService;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public CodePipelineCfnProvisioner(CodePipelineService codePipelineService,
                                      RegionResolver regionResolver, ObjectMapper mapper) {
        this.codePipelineService = codePipelineService;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::CodePipeline::Pipeline",
                "AWS::CodePipeline::CustomActionType",
                "AWS::CodePipeline::Webhook");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::CodePipeline::Pipeline" -> provisionPipeline(r, props, ctx);
            case "AWS::CodePipeline::CustomActionType" -> provisionCustomActionType(r, props, ctx);
            case "AWS::CodePipeline::Webhook" -> provisionWebhook(r, props, ctx);
            default -> throw new IllegalStateException(
                    "CodePipelineCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        String account = regionResolver.getAccountId();
        try {
            switch (resourceType) {
                case "AWS::CodePipeline::Pipeline" -> codePipelineService.handle("DeletePipeline",
                        mapper.createObjectNode().put("name", physicalId), region, account);
                case "AWS::CodePipeline::CustomActionType" -> {
                    String[] parts = physicalId.split("\\|");
                    codePipelineService.handle("DeleteCustomActionType", mapper.createObjectNode()
                                    .put("category", parts[0])
                                    .put("provider", parts.length > 2 ? parts[2] : "")
                                    .put("version", parts.length > 3 ? parts[3] : "1"),
                            region, account);
                }
                case "AWS::CodePipeline::Webhook" -> codePipelineService.handle("DeleteWebhook",
                        mapper.createObjectNode().put("name", physicalId), region, account);
                default -> { }
            }
        } catch (AwsException e) {
            LOG.debugv("CodePipeline CFN delete of {0} {1} tolerated: {2}",
                    resourceType, physicalId, e.getMessage());
        }
    }

    private void provisionPipeline(StackResource r, JsonNode props, ProvisionContext ctx) {
        ObjectNode declaration = (ObjectNode) lowerKeys(ctx.engine().resolveNode(props));
        JsonNode transitions = declaration.remove("disableInboundStageTransitions");
        JsonNode tags = declaration.remove("tags");
        declaration.remove("restartExecutionOnUpdate");

        boolean update = r.getPhysicalId() != null && !r.getPhysicalId().isBlank();
        String name = declaration.path("name").asText(null);
        if (name == null || name.isBlank()) {
            name = update ? r.getPhysicalId() : ctx.generatePhysicalName(r.getLogicalId(), 100, false);
            declaration.put("name", name);
        }

        ObjectNode request = mapper.createObjectNode();
        request.set("pipeline", declaration);
        JsonNode response;
        if (update) {
            response = codePipelineService.handle("UpdatePipeline", request, ctx.region(), ctx.accountId());
        } else {
            if (tags != null && tags.isArray() && !tags.isEmpty()) {
                request.set("tags", tags);
            }
            response = codePipelineService.handle("CreatePipeline", request, ctx.region(), ctx.accountId());
        }

        if (transitions != null && transitions.isArray()) {
            for (JsonNode transition : transitions) {
                codePipelineService.handle("DisableStageTransition", mapper.createObjectNode()
                                .put("pipelineName", name)
                                .put("stageName", transition.path("stageName").asText())
                                .put("transitionType", "Inbound")
                                .put("reason", transition.path("reason").asText("Disabled by CloudFormation")),
                        ctx.region(), ctx.accountId());
            }
        }

        r.setPhysicalId(name);
        r.getAttributes().put("Version",
                String.valueOf(response.path("pipeline").path("version").asInt(1)));
    }

    private void provisionCustomActionType(StackResource r, JsonNode props, ProvisionContext ctx) {
        ObjectNode request = (ObjectNode) lowerKeys(ctx.engine().resolveNode(props));
        request.remove("tags");
        String physicalId = request.path("category").asText() + "|Custom|"
                + request.path("provider").asText() + "|" + request.path("version").asText("1");
        if (r.getPhysicalId() == null || r.getPhysicalId().isBlank()) {
            codePipelineService.handle("CreateCustomActionType", request, ctx.region(), ctx.accountId());
        }
        r.setPhysicalId(physicalId);
    }

    private void provisionWebhook(StackResource r, JsonNode props, ProvisionContext ctx) {
        ObjectNode webhook = (ObjectNode) lowerKeys(ctx.engine().resolveNode(props));
        boolean register = webhook.remove("registerWithThirdParty") != null
                && props.path("RegisterWithThirdParty").asBoolean(false);
        String name = webhook.path("name").asText(null);
        if (name == null || name.isBlank()) {
            name = r.getPhysicalId() != null && !r.getPhysicalId().isBlank()
                    ? r.getPhysicalId() : ctx.generatePhysicalName(r.getLogicalId(), 100, false);
            webhook.put("name", name);
        }
        JsonNode response = codePipelineService.handle("PutWebhook",
                mapper.createObjectNode().set("webhook", webhook), ctx.region(), ctx.accountId());
        if (register) {
            codePipelineService.handle("RegisterWebhookWithThirdParty",
                    mapper.createObjectNode().put("webhookName", name), ctx.region(), ctx.accountId());
        }
        r.setPhysicalId(name);
        r.getAttributes().put("Url", response.path("webhook").path("url").asText(""));
    }

    /** Recursively lowercases the first letter of every object key. */
    private JsonNode lowerKeys(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            ObjectNode result = mapper.createObjectNode();
            objectNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                String lowered = key.isEmpty()
                        ? key : Character.toLowerCase(key.charAt(0)) + key.substring(1);
                result.set(lowered, lowerKeys(entry.getValue()));
            });
            return result;
        }
        if (node instanceof ArrayNode arrayNode) {
            ArrayNode result = mapper.createArrayNode();
            arrayNode.forEach(entry -> result.add(lowerKeys(entry)));
            return result;
        }
        return node;
    }
}
