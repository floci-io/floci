package io.floci.aws.services.translate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Base64;
/**
 * JSON 1.1 handler for Amazon Translate API operations.
 * Dispatches X-Amz-Target: AWSShineFrontendService_20170701.* actions to {@link TranslateService}.
 *
 * @see <a href="https://docs.aws.amazon.com/translate/latest/APIReference/welcome.html">Translate API Reference</a>
 */
@ApplicationScoped
public class TranslateJsonHandler {
    private static final Logger LOG = Logger.getLogger(TranslateJsonHandler.class);
    private final TranslateService translateService;
    @Inject
    public TranslateJsonHandler(TranslateService translateService) {
        this.translateService = translateService;
    }
    /**
     * Dispatches Translate actions received via the AwsJson11Controller. Only the three
     * self-contained sync actions are implemented; the terminology, parallel-data, async
     * batch-job, and tagging surface (all of which need persistent state) is out of scope.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Translate action: {0}", action);
        return switch (action) {
            case "TranslateText" -> translateService.translateText(
                    getStringField(request, "Text"),
                    getStringField(request, "SourceLanguageCode"),
                    getStringField(request, "TargetLanguageCode"));
            case "TranslateDocument" -> translateService.translateDocument(
                    getNestedBlobField(request, "Document", "Content"),
                    getNestedStringField(request, "Document", "ContentType"),
                    getStringField(request, "SourceLanguageCode"),
                    getStringField(request, "TargetLanguageCode"));
            case "ListLanguages" -> translateService.listLanguages(
                    getStringField(request, "DisplayLanguageCode"));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSShineFrontendService_20170701." + action))
                    .build();
        };
    }
    /**
     * Dispatches Translate actions received over smithy-rpc-v2-cbor. Requests go through
     * {@link #handle}, which already accepts a CBOR byte string for blob members; on the
     * way back, the blob {@code TranslatedDocument.Content} (base64 text in JSON 1.1) is
     * turned into raw bytes so the CBOR serializer emits it as a byte string.
     */
    public Response handleCbor(String action, JsonNode request, String region) {
        Response response = handle(action, request, region);
        if ("TranslateDocument".equals(action) && response.getEntity() instanceof ObjectNode root) {
            JsonNode document = root.get("TranslatedDocument");
            if (document instanceof ObjectNode documentNode && documentNode.path("Content").isTextual()) {
                byte[] bytes = Base64.getDecoder().decode(documentNode.get("Content").textValue());
                documentNode.set("Content", BinaryNode.valueOf(bytes));
            }
        }
        return response;
    }

    /**
     * Extracts a string-typed field. A present-but-wrong-typed value is a shape/marshalling
     * mismatch caught at the protocol layer before operation-specific validation, matching
     * {@link ComprehendJsonHandler}. It must not be silently coerced, nor conflated with a
     * missing field (a modeled business error handled in {@link TranslateService}).
     */
    private String getStringField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + field + "' is not a string.", 400);
        }
        return value.asText();
    }
    /**
     * Extracts a blob field nested one level under {@code parent} (e.g. Document.Content) as
     * base64 text. JSON 1.1 carries a blob as a base64 string; the CBOR protocols carry it as
     * a byte string, which decodes to a binary node and is re-encoded here, so the service
     * sees the same base64 form either way (as {@code KinesisJsonHandler} does for Data).
     */
    private String getNestedBlobField(JsonNode node, String parent, String field) {
        JsonNode parentNode = node == null ? null : node.get(parent);
        if (parentNode != null && parentNode.isObject()) {
            JsonNode value = parentNode.get(field);
            if (value instanceof BinaryNode binary) {
                return Base64.getEncoder().encodeToString(binary.binaryValue());
            }
        }
        return getNestedStringField(node, parent, field);
    }

    /** {@link #getStringField} for a field nested one level under {@code parent} (e.g. Document.Content). */
    private String getNestedStringField(JsonNode node, String parent, String field) {
        JsonNode parentNode = node == null ? null : node.get(parent);
        if (parentNode == null || parentNode.isNull()) {
            return null;
        }
        if (!parentNode.isObject()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + parent + "' is not a structure.", 400);
        }
        return getStringField(parentNode, field);
    }
}
