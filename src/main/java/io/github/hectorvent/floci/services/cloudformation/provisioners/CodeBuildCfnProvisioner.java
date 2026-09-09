package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.codebuild.CodeBuildJsonHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * CloudFormation provisioning for CodeBuild: {@code AWS::CodeBuild::Project}.
 *
 * <p>CloudFormation properties are PascalCase while the CodeBuild API is camelCase; the
 * recursive first-letter-lowercase transform maps them, with one irregular field —
 * {@code BuildSpec} is {@code buildspec} (all lowercase) on the wire.</p>
 */
@ApplicationScoped
public class CodeBuildCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CodeBuildCfnProvisioner.class);

    private final CodeBuildJsonHandler codeBuildJsonHandler;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public CodeBuildCfnProvisioner(CodeBuildJsonHandler codeBuildJsonHandler,
                                   RegionResolver regionResolver, ObjectMapper mapper) {
        this.codeBuildJsonHandler = codeBuildJsonHandler;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::CodeBuild::Project");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        ObjectNode request = (ObjectNode) lowerKeys(ctx.engine().resolveNode(props));
        boolean update = r.getPhysicalId() != null && !r.getPhysicalId().isBlank();
        String name = request.path("name").asText(null);
        if (name == null || name.isBlank()) {
            name = update ? r.getPhysicalId() : ctx.generatePhysicalName(r.getLogicalId(), 150, false);
            request.put("name", name);
        }

        JsonNode response = handle(update ? "UpdateProject" : "CreateProject",
                request, ctx.region(), ctx.accountId());

        r.setPhysicalId(name);
        r.getAttributes().put("Arn",
                response.path("project").path("arn").asText(
                        "arn:aws:codebuild:" + ctx.region() + ":" + ctx.accountId() + ":project/" + name));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        try {
            handle("DeleteProject", mapper.createObjectNode().put("name", physicalId),
                    region, regionResolver.getAccountId());
        } catch (AwsException e) {
            LOG.debugv("CodeBuild CFN delete of {0} tolerated: {1}", physicalId, e.getMessage());
        }
    }

    private JsonNode handle(String action, ObjectNode request, String region, String account) {
        try {
            jakarta.ws.rs.core.Response response =
                    codeBuildJsonHandler.handle(action, request, region, account);
            try {
                // The handler returns Response.ok(Map.of(...)) for every JSON action, never a
                // JsonNode directly, so the entity must be converted rather than downcast.
                Object entity = response.getEntity();
                return entity instanceof JsonNode node ? node : mapper.valueToTree(entity);
            } finally {
                response.close();
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalFailure",
                    "CodeBuild " + action + " failed: " + e.getMessage(), 500);
        }
    }

    private JsonNode lowerKeys(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            ObjectNode result = mapper.createObjectNode();
            objectNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                String lowered = "BuildSpec".equals(key)
                        ? "buildspec"
                        : key.isEmpty() ? key : Character.toLowerCase(key.charAt(0)) + key.substring(1);
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
