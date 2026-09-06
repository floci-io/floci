package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;

import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the three {@link IotCfnProvisioner} unit tests share: a mocked template engine that behaves
 * like the real one for the shapes these templates use, and the resource and error factories.
 */
final class IotCfnProvisionerTestSupport {

    static final String REGION = "us-east-1";
    static final String ACCOUNT = "000000000000";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IotCfnProvisionerTestSupport() {
    }

    static ProvisionContext ctx() {
        return ctx(null);
    }

    /**
     * Scalars resolve to their text, an intrinsic collapses to a {@code resolved:} marker and
     * everything else is walked in place, so a nested payload keeps its shape and scalar types.
     */
    static ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> resolveIntrinsics(inv.getArgument(0)));
        when(engine.resolveJsonAttribute(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            return node.isTextual() ? node.asText() : node.toString();
        });
        return new ProvisionContext(engine, REGION, ACCOUNT, "my-stack", priorPhysicalId);
    }

    static JsonNode resolveIntrinsics(JsonNode node) {
        if (node == null || !node.isContainerNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(item -> out.add(resolveIntrinsics(item)));
            return out;
        }
        if (node.has("Fn::GetAtt")) {
            JsonNode target = node.get("Fn::GetAtt");
            return TextNode.valueOf("resolved:" + target.get(0).asText() + "." + target.get(1).asText());
        }
        if (node.has("Ref")) {
            return TextNode.valueOf("resolved:" + node.get("Ref").asText());
        }
        ObjectNode out = MAPPER.createObjectNode();
        node.fields().forEachRemaining(field -> out.set(field.getKey(), resolveIntrinsics(field.getValue())));
        return out;
    }

    static StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    static AwsException notFound(String what) {
        return new AwsException("ResourceNotFoundException", what + " not found", 404);
    }
}
