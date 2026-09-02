package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Provisions {@code AWS::Pipes::Pipe}. */
@ApplicationScoped
public class PipesCfnProvisioner implements CfnResourceProvisioner {

    private static final int PIPE_NAME_MAX_LENGTH = 64;

    private final PipesService pipesService;

    public PipesCfnProvisioner(PipesService pipesService) {
        this.pipesService = pipesService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Pipes::Pipe");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), PIPE_NAME_MAX_LENGTH, false);
        }

        String source = ctx.resolveOptional(props, "Source");
        String target = ctx.resolveOptional(props, "Target");
        String roleArn = ctx.resolveOptional(props, "RoleArn");
        String description = ctx.resolveOptional(props, "Description");
        String enrichment = ctx.resolveOptional(props, "Enrichment");

        String stateStr = ctx.resolveOptional(props, "DesiredState");
        DesiredState desiredState = "STOPPED".equals(stateStr) ? DesiredState.STOPPED : DesiredState.RUNNING;

        JsonNode sourceParameters = resolvedObject(props, "SourceParameters", ctx);
        JsonNode targetParameters = resolvedObject(props, "TargetParameters", ctx);
        JsonNode enrichmentParameters = resolvedObject(props, "EnrichmentParameters", ctx);

        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);

        var pipe = pipesService.createPipe(name, source, target, roleArn, description, desiredState,
                enrichment, sourceParameters, targetParameters, enrichmentParameters, tags, ctx.region());

        r.setPhysicalId(name);
        r.getAttributes().put("Arn", pipe.getArn());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        pipesService.deletePipe(physicalId, region);
    }

    private JsonNode resolvedObject(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return ctx.engine().resolveNode(props.get(name));
    }

    /** See {@code KmsCfnProvisioner#parseCfnTags} for why this is copied rather than shared. */
    private Map<String, String> parseCfnTags(JsonNode tagsNode, ProvisionContext ctx) {
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = ctx.engine().resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
