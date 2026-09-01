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
 * @see <a href="https://docs.aws.amazon.com/translate/latest/APIReference/Welcome.html">Translate API Reference</a>
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
     * Dispatches Translate actions received via the AwsJson11Controller.
     * Only the sync TranslateText/TranslateDocument/ListLanguages actions are
     * implemented; custom terminology, parallel data, and the async batch job
     * surface are out of scope.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Translate action: {0}", action);
        return switch (action) {
            case "TranslateText" -> translateService.translateText(
                    getStringField(request, "Text"),
                    getStringField(request, "SourceLanguageCode"),
                    getStringField(request, "TargetLanguageCode"));
            case "TranslateDocument" -> translateService.translateDocument(
                    requireDocumentObject(request),
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
     * Extracts a string-typed field. A present-but-wrong-typed value (e.g. a number
     * or boolean where the modeled shape is a string) is a shape/marshalling
     * mismatch caught at the protocol layer, before any operation-specific business
     * validation runs — matching {@code JsonErrorResponseUtils.createSerializationErrorResponse()}'s
     * treatment of a malformed JSON body. It must not be silently coerced via
     * {@code asText()}, nor conflated with a missing field (a modeled business error
     * specific to each operation, handled downstream in {@link TranslateService}).
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
     * Returns the {@code Document} field as-is when it is a JSON object (or null when
     * absent), for {@link TranslateService} to validate its required members. A
     * present-but-wrong-typed value is a shape mismatch caught here at the protocol
     * layer, same treatment as {@link #getStringField}.
     */
    private JsonNode requireDocumentObject(JsonNode request) {
        JsonNode document = request == null ? null : request.get("Document");
        if (document == null || document.isNull()) {
            return null;
        }
        if (!document.isObject()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for 'Document' is not a structure.", 400);
        }
        return document;
    }
}
