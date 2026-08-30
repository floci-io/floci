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

    private static final String DISABLED_STAGES_ATTR = "__FlociCodePipelineDisabledStages";

    private void provisionPipeline(StackResource r, JsonNode props, ProvisionContext ctx) {
        ObjectNode declaration = (ObjectNode) lowerKeys(ctx.engine().resolveNode(props));
        JsonNode transitions = declaration.remove("disableInboundStageTransitions");
        JsonNode tags = declaration.remove("tags");
        declaration.remove("restartExecutionOnUpdate");

        String previousPhysicalId = r.getPhysicalId();
        boolean update = previousPhysicalId != null && !previousPhysicalId.isBlank();
        String explicitName = declaration.path("name").asText(null);
        // Name is part of a pipeline's identity -- CodePipeline has no rename operation, so a
        // template Name that differs from the tracked physical ID is a replacement, not an
        // in-place update (UpdatePipeline would otherwise target/overwrite the wrong pipeline).
        boolean rename = update && explicitName != null && !explicitName.isBlank()
                && !explicitName.equals(previousPhysicalId);
        boolean create = !update || rename;
        String name = explicitName != null && !explicitName.isBlank()
                ? explicitName
                : (update ? previousPhysicalId : ctx.generatePhysicalName(r.getLogicalId(), 100, false));
        if (explicitName == null || explicitName.isBlank()) {
            declaration.put("name", name);
        }

        ObjectNode request = mapper.createObjectNode();
        request.set("pipeline", declaration);
        JsonNode response;
        if (create) {
            if (tags != null && tags.isArray() && !tags.isEmpty()) {
                request.set("tags", tags);
            }
            response = codePipelineService.handle("CreatePipeline", request, ctx.region(), ctx.accountId());
        } else {
            response = codePipelineService.handle("UpdatePipeline", request, ctx.region(), ctx.accountId());
        }

        java.util.Set<String> desiredDisabledStages = new java.util.LinkedHashSet<>();
        if (transitions != null && transitions.isArray()) {
            for (JsonNode transition : transitions) {
                desiredDisabledStages.add(transition.path("stageName").asText());
            }
        }
        try {
            // DisableInboundStageTransitions is declarative, but only for the stages CloudFormation
            // itself disabled: a stage disabled externally (e.g. a manual gate) and never listed here
            // must be left alone. Re-enable only stages this resource previously disabled and has now
            // dropped from the list, tracked via DISABLED_STAGES_ATTR across deployments.
            if (!create) {
                String previouslyManaged = r.getAttributes().get(DISABLED_STAGES_ATTR);
                Iterable<String> candidateStages;
                if (previouslyManaged != null) {
                    candidateStages = previouslyManaged.isBlank()
                            ? java.util.List.of() : java.util.Arrays.asList(previouslyManaged.split(","));
                } else {
                    // A resource stored before DISABLED_STAGES_ATTR existed has no record of what it
                    // previously disabled. Fall back to reconciling every declared stage rather than
                    // skipping reconciliation outright, which would leave a removed entry disabled
                    // forever after this one legacy update.
                    java.util.List<String> declaredStages = new java.util.ArrayList<>();
                    if (declaration.path("stages").isArray()) {
                        for (JsonNode stage : declaration.path("stages")) {
                            declaredStages.add(stage.path("name").asText());
                        }
                    }
                    candidateStages = declaredStages;
                }
                for (String stageName : candidateStages) {
                    if (!desiredDisabledStages.contains(stageName)) {
                        codePipelineService.handle("EnableStageTransition", mapper.createObjectNode()
                                        .put("pipelineName", name)
                                        .put("stageName", stageName)
                                        .put("transitionType", "Inbound"),
                                ctx.region(), ctx.accountId());
                    }
                }
            }
            for (String stageName : desiredDisabledStages) {
                JsonNode transition = null;
                for (JsonNode candidate : transitions) {
                    if (stageName.equals(candidate.path("stageName").asText())) {
                        transition = candidate;
                        break;
                    }
                }
                codePipelineService.handle("DisableStageTransition", mapper.createObjectNode()
                                .put("pipelineName", name)
                                .put("stageName", stageName)
                                .put("transitionType", "Inbound")
                                .put("reason", transition.path("reason").asText("Disabled by CloudFormation")),
                        ctx.region(), ctx.accountId());
            }
        } catch (RuntimeException e) {
            if (create) {
                // The pipeline was just created (fresh, or as a rename's replacement) and stage
                // reconciliation then failed. CloudFormation reverts this resource to its previous
                // physicalId on a failed update, so the half-provisioned pipeline must be torn down
                // now, or a corrected retry hits PipelineNameInUseException on the same name.
                try {
                    codePipelineService.handle("DeletePipeline",
                            mapper.createObjectNode().put("name", name), ctx.region(), ctx.accountId());
                } catch (RuntimeException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw e;
        }
        r.getAttributes().put(DISABLED_STAGES_ATTR, String.join(",", desiredDisabledStages));

        if (rename) {
            delete("AWS::CodePipeline::Pipeline", previousPhysicalId, ctx.region());
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
        String previousPhysicalId = r.getPhysicalId();
        // Category/Provider/Version are this resource's identity — CodePipeline has no update
        // operation for a custom action type, so a change to any of them is a replacement:
        // create the new identity, then best-effort delete the old one it superseded.
        if (previousPhysicalId == null || previousPhysicalId.isBlank()
                || !previousPhysicalId.equals(physicalId)) {
            codePipelineService.handle("CreateCustomActionType", request, ctx.region(), ctx.accountId());
            if (previousPhysicalId != null && !previousPhysicalId.isBlank()) {
                delete("AWS::CodePipeline::CustomActionType", previousPhysicalId, ctx.region());
            }
        }
        r.setPhysicalId(physicalId);
    }

    private void provisionWebhook(StackResource r, JsonNode props, ProvisionContext ctx) {
        ObjectNode webhook = (ObjectNode) lowerKeys(ctx.engine().resolveNode(props));
        JsonNode registerNode = webhook.remove("registerWithThirdParty");
        boolean register = registerNode != null && registerNode.asBoolean(false);
        String previousPhysicalId = r.getPhysicalId();
        boolean update = previousPhysicalId != null && !previousPhysicalId.isBlank();
        String name = webhook.path("name").asText(null);
        boolean rename = update && name != null && !name.isBlank() && !name.equals(previousPhysicalId);
        if (name == null || name.isBlank()) {
            name = update ? previousPhysicalId : ctx.generatePhysicalName(r.getLogicalId(), 100, false);
            webhook.put("name", name);
        }
        JsonNode response = codePipelineService.handle("PutWebhook",
                mapper.createObjectNode().set("webhook", webhook), ctx.region(), ctx.accountId());
        if (register) {
            codePipelineService.handle("RegisterWebhookWithThirdParty",
                    mapper.createObjectNode().put("webhookName", name), ctx.region(), ctx.accountId());
        }
        // Name is this resource's identity; PutWebhook upserts under the new name unconditionally,
        // so a rename must also clean up the webhook the stack previously owned or it's orphaned.
        if (rename) {
            delete("AWS::CodePipeline::Webhook", previousPhysicalId, ctx.region());
        }
        r.setPhysicalId(name);
        r.getAttributes().put("Url", response.path("webhook").path("url").asText(""));
    }

    /**
     * Recursively lowercases the first letter of every object key — except inside
     * {@code Configuration} maps, whose keys are opaque provider-defined strings
     * ({@code Owner}, {@code Repo}, {@code ProjectName}, ...) that the action executors
     * match verbatim.
     */
    private JsonNode lowerKeys(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            ObjectNode result = mapper.createObjectNode();
            objectNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if ("Configuration".equals(key)) {
                    result.set("configuration", entry.getValue().deepCopy());
                    return;
                }
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
