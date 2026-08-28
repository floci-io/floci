package io.github.hectorvent.floci.services.comprehend;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
/**
 * JSON 1.1 handler for Amazon Comprehend API operations.
 * Dispatches X-Amz-Target: Comprehend_20171127.* actions to {@link ComprehendService}.
 *
 * @see <a href="https://docs.aws.amazon.com/comprehend/latest/APIReference/Welcome.html">Comprehend API Reference</a>
 */
@ApplicationScoped
public class ComprehendJsonHandler {
    private static final Logger LOG = Logger.getLogger(ComprehendJsonHandler.class);
    private final ComprehendService comprehendService;
    @Inject
    public ComprehendJsonHandler(ComprehendService comprehendService) {
        this.comprehendService = comprehendService;
    }
    /**
     * Dispatches Comprehend actions received via the AwsJson11Controller.
     * Only the sync Detect and ContainsPiiEntities actions are implemented; the
     * async job and model-training surface (DocumentClassifier, EntityRecognizer,
     * Flywheel, Dataset, Endpoint) is out of scope.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Comprehend action: {0}", action);
        return switch (action) {
            case "DetectSentiment" -> comprehendService.detectSentiment(
                    getStringField(request, "Text"), getStringField(request, "LanguageCode"));
            case "DetectKeyPhrases" -> comprehendService.detectKeyPhrases(
                    getStringField(request, "Text"), getStringField(request, "LanguageCode"));
            case "DetectDominantLanguage" -> comprehendService.detectDominantLanguage(
                    getStringField(request, "Text"));
            case "DetectPiiEntities" -> comprehendService.detectPiiEntities(
                    getStringField(request, "Text"), getStringField(request, "LanguageCode"));
            case "ContainsPiiEntities" -> comprehendService.containsPiiEntities(
                    getStringField(request, "Text"), getStringField(request, "LanguageCode"));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Comprehend_20171127." + action))
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
     * specific to each operation, handled downstream in {@link ComprehendService}).
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
}
