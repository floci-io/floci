package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
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
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "Name"),
                r.getLogicalId(), PIPE_NAME_MAX_LENGTH, false);

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

        // provision is also the update path. createPipe throws ConflictException on an existing
        // name, and stablePhysicalName now keeps that name steady across updates, so a second
        // UpdateStack must reconcile the pipe rather than recreate it. A replacing update derives a
        // different name and still creates, which is why this asks reusesPriorEntity rather than
        // isUpdate.
        Pipe pipe;
        if (ctx.reusesPriorEntity(name)) {
            Pipe existing = pipesService.describePipe(name, ctx.region());
            // Source is a createOnly property. With the name reused there is no replacement to
            // move to, which is the update CloudFormation refuses for a custom-named resource, so
            // it is refused here rather than silently kept on the old source.
            if (source != null && !source.equals(existing.getSource())) {
                throw new AwsException("ValidationError",
                        "Updating Source requires resource replacement, which is not supported.", 400);
            }
            pipe = pipesService.updatePipe(name, target, roleArn, description, desiredState,
                    enrichment, sourceParameters, targetParameters, enrichmentParameters,
                    ctx.region());
            reconcileTags(pipe, tags, ctx.region());
        } else {
            pipe = pipesService.createPipe(name, source, target, roleArn, description, desiredState,
                    enrichment, sourceParameters, targetParameters, enrichmentParameters, tags,
                    ctx.region());
        }

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

    /**
     * UpdatePipe carries no Tags (only CreatePipe does), so on the update path the template's tags
     * are driven to their desired state through TagResource and UntagResource, keyed by the pipe's
     * ARN. A key the template dropped is untagged rather than left over.
     */
    private void reconcileTags(Pipe pipe, Map<String, String> desired, String region) {
        List<String> stale = ProvisionContext.staleTagKeys(
                pipesService.listTags(region, pipe.getArn()), desired);
        if (!stale.isEmpty()) {
            pipesService.untagResource(region, pipe.getArn(), stale);
        }
        if (!desired.isEmpty()) {
            pipesService.tagResource(region, pipe.getArn(), desired);
        }
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
