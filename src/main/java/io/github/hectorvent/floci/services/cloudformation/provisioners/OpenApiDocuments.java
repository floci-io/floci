package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationYamlParser;
import io.github.hectorvent.floci.services.s3.S3Service;

import java.nio.charset.StandardCharsets;

/**
 * Resolves the OpenAPI document a CloudFormation API Gateway resource declares through its
 * {@code Body} (inline) or {@code BodyS3Location} (an object in S3). Shared by
 * {@code AWS::ApiGateway::RestApi} and {@code AWS::ApiGatewayV2::Api}, which both accept the same
 * two properties, so the resolution lives here rather than being duplicated per provisioner.
 */
public final class OpenApiDocuments {

    private OpenApiDocuments() {
    }

    /**
     * The declared OpenAPI document as a resolved JSON tree, or {@code null} when the resource
     * declares neither {@code Body} nor {@code BodyS3Location}. A JSON or YAML body in S3 is
     * fetched and parsed; a malformed one raises a {@code ValidationException}.
     */
    public static JsonNode resolve(JsonNode props, CloudFormationTemplateEngine engine,
                                   S3Service s3Service, ObjectMapper objectMapper) {
        if (props == null) {
            return null;
        }
        if (props.hasNonNull("Body")) {
            return engine.resolveNode(props.get("Body"));
        }
        if (!props.hasNonNull("BodyS3Location")) {
            return null;
        }

        JsonNode location = engine.resolveNode(props.get("BodyS3Location"));
        S3Location bodyS3Location = parseLocation(location);

        try {
            byte[] document = s3Service.getObject(bodyS3Location.bucket(), bodyS3Location.key(),
                    bodyS3Location.version()).getData();
            String content = new String(document, StandardCharsets.UTF_8).trim();
            if (content.startsWith("{") || content.startsWith("[")) {
                return objectMapper.readTree(content);
            }
            return new CloudFormationYamlParser(objectMapper).parse(content);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException",
                    "Unable to parse OpenAPI document from s3://" + bodyS3Location.bucket() + "/"
                            + bodyS3Location.key(), 400);
        }
    }

    private static S3Location parseLocation(JsonNode location) {
        if (location != null && location.isTextual()) {
            String uri = location.asText();
            if (uri.startsWith("s3://")) {
                String withoutScheme = uri.substring("s3://".length());
                int slash = withoutScheme.indexOf('/');
                if (slash > 0 && slash < withoutScheme.length() - 1) {
                    return new S3Location(withoutScheme.substring(0, slash),
                            withoutScheme.substring(slash + 1), null);
                }
            }
        } else if (location != null && location.isObject()) {
            String bucket = textOrNull(location, "Bucket");
            String key = textOrNull(location, "Key");
            if (bucket != null && !bucket.isBlank() && key != null && !key.isBlank()) {
                return new S3Location(bucket, key, textOrNull(location, "Version"));
            }
        }
        throw new AwsException("ValidationException",
                "BodyS3Location must resolve to a non-empty S3 location", 400);
    }

    private static String textOrNull(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private record S3Location(String bucket, String key, String version) {
    }
}
