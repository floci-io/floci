package io.github.hectorvent.floci.services.translate;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
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
                    getNestedStringField(request, "Document", "Content"),
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
