package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.S3Service;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/**
 * Expands {@code Fn::Transform} nodes whose {@code Name} is {@code AWS::Include}: splices the
 * YAML/JSON snippet the node's {@code Location} points at into the enclosing mapping, in place of
 * the {@code Fn::Transform} key, before any other intrinsic resolves, mirroring
 * {@link SamTransformProcessor}, which runs on the same parsed template ahead of provisioning.
 *
 * <p>{@code Location} accepts only an {@code s3://} URI. The template CloudFormation itself
 * receives always carries one: {@code aws cloudformation package} rewrites every local path before
 * a stack ever sees it, so a local path never reaches this processor in practice, and floci does
 * not read one on the caller's behalf (the CloudFormation package performs no filesystem access,
 * and a containerised floci sees none of the caller's paths regardless).
 */
class AwsIncludeProcessor {

    static final String AWS_INCLUDE = "AWS::Include";
    private static final String FN_TRANSFORM = "Fn::Transform";
    private static final String S3_SCHEME = "s3://";

    private final ObjectMapper objectMapper;
    private final S3Service s3Service;

    AwsIncludeProcessor(ObjectMapper objectMapper, S3Service s3Service) {
        this.objectMapper = objectMapper;
        this.s3Service = s3Service;
    }

    /**
     * Returns a copy of {@code template} with every {@code Fn::Transform}/{@code AWS::Include}
     * node replaced by the keys of the snippet it names, merged into the node's enclosing mapping
     * alongside any keys already written there.
     *
     * <p>Reference identity is part of this method's contract, not an incidental optimization: a
     * template carrying no {@code AWS::Include} is returned as the exact same {@code JsonNode}
     * instance passed in, unmodified, on every {@code executeTemplate} and every
     * {@code DescribeChangeSet} - the overwhelming majority of templates. Callers rely on that:
     * they compare the reference this method returns against the one they passed in to tell a real
     * merge apart from a no-op one, and a copy here even for a no-op merge would make every caller
     * treat an untouched template as changed.
     */
    JsonNode mergeIncludes(JsonNode template) {
        if (!containsAwsInclude(template)) {
            return template;
        }
        JsonNode merged = template.deepCopy();
        merge(merged);
        return merged;
    }

    private void merge(JsonNode node) {
        if (node.isObject()) {
            mergeObject((ObjectNode) node);
        } else if (node.isArray()) {
            node.forEach(this::merge);
        }
    }

    private void mergeObject(ObjectNode obj) {
        JsonNode transform = obj.get(FN_TRANSFORM);
        if (transform != null && AWS_INCLUDE.equals(transform.path("Name").asText(""))) {
            spliceInclude(obj, transform);
        }
        // Walk every remaining field, including the snippet's own keys just merged in above:
        // spliceInclude already rejects a snippet carrying its own AWS::Include, so revisiting
        // them here cannot re-trigger a merge - it only reaches unrelated Fn::Transform nodes
        // elsewhere in the template.
        Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
        while (fields.hasNext()) {
            merge(fields.next().getValue());
        }
    }

    private void spliceInclude(ObjectNode obj, JsonNode transform) {
        String location = describeLocation(transform);
        S3Location s3Location = parseS3Location(location);
        JsonNode snippet = fetchSnippet(s3Location, location);

        if (!snippet.isObject()) {
            throw new AwsException("ValidationError",
                    "AWS::Include snippet at " + location + " must be a YAML/JSON mapping", 400);
        }
        if (containsAwsInclude(snippet)) {
            throw new AwsException("ValidationError",
                    "AWS::Include snippet at " + location + " may not itself use AWS::Include", 400);
        }

        obj.remove(FN_TRANSFORM);
        obj.setAll((ObjectNode) snippet);
    }

    /**
     * Renders {@code transform.Parameters.Location} for an error message. A well-formed
     * {@code Location} is a plain string, but a node such as {@code {Ref: InputValue}}, the form
     * AWS's own {@code Fn::Transform} documentation uses in its {@code AWS::Include} example, is a
     * mapping. {@code Ref} resolution is not implemented here, so that mapping is serialized
     * instead, and the rejection message names it. A {@code Parameters} that is not an object
     * (missing, explicitly {@code null}, a scalar, or a list) names the whole {@code Fn::Transform}
     * node instead, so the message never presents an unrelated value as the rejected
     * {@code Location}. A {@code Location} missing from an object {@code Parameters} names the
     * enclosing {@code Parameters} mapping. A {@code Location} present but blank, including
     * whitespace only, is rendered as its quoted string, not elided, so the message never leaves an
     * empty tail.
     */
    private String describeLocation(JsonNode transform) {
        JsonNode parameters = transform.path("Parameters");
        if (!parameters.isObject()) {
            return transform.toString();
        }
        JsonNode locationNode = parameters.path("Location");
        if (locationNode.isTextual() && !locationNode.asText().isBlank()) {
            return locationNode.asText();
        }
        JsonNode described = locationNode.isMissingNode() || locationNode.isNull() ? parameters : locationNode;
        return described.toString();
    }

    /**
     * Returns whether {@code node} contains an unexpanded {@code Fn::Transform} whose {@code Name}
     * is {@code AWS::Include}, at any depth. A foreign macro under a different {@code Name} does not
     * count: AWS documents that a snippet may not itself use {@code AWS::Include}, not that it may
     * carry no other transform. Package-private: also used by {@code CloudFormationService} to
     * report {@code AWS::Include} in {@code GetTemplateSummary}'s {@code DeclaredTransforms} and to
     * skip {@code validateConditionDependencies} for a template the merge has not run against yet.
     */
    boolean containsAwsInclude(JsonNode node) {
        if (node.isObject()) {
            JsonNode transform = node.get(FN_TRANSFORM);
            if (transform != null && AWS_INCLUDE.equals(transform.path("Name").asText(""))) {
                return true;
            }
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                if (containsAwsInclude(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonNode fetchSnippet(S3Location location, String rawLocation) {
        try {
            byte[] data = s3Service.getObject(location.bucket(), location.key()).getData();
            return new CloudFormationYamlParser(objectMapper).parse(new String(data, StandardCharsets.UTF_8));
        } catch (AwsException e) {
            throw new AwsException(e.getErrorCode(),
                    "Unable to read AWS::Include snippet at " + rawLocation + ": " + e.getMessage(),
                    e.getHttpStatus());
        } catch (Exception e) {
            throw new AwsException("ValidationError",
                    "Unable to read AWS::Include snippet at " + rawLocation + ": " + e.getMessage(), 400);
        }
    }

    private record S3Location(String bucket, String key) {}

    private S3Location parseS3Location(String location) {
        if (location.startsWith(S3_SCHEME)) {
            String withoutScheme = location.substring(S3_SCHEME.length());
            int slash = withoutScheme.indexOf('/');
            if (slash > 0 && slash < withoutScheme.length() - 1) {
                return new S3Location(withoutScheme.substring(0, slash), withoutScheme.substring(slash + 1));
            }
        }
        throw new AwsException("ValidationError",
                "Fn::Transform AWS::Include Location must be an s3://bucket/key URI, got " + location, 400);
    }
}
