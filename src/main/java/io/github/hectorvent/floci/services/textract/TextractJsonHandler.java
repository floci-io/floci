package io.github.hectorvent.floci.services.textract;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
/**
 * JSON 1.1 handler for Amazon Textract API operations.
 * Dispatches X-Amz-Target: Textract.* actions to {@link TextractService}.
 *
 * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Operations.html">Textract API Reference</a>
 */
@ApplicationScoped
public class TextractJsonHandler {
    private static final Logger LOG = Logger.getLogger(TextractJsonHandler.class);
    private final TextractService textractService;
    @Inject
    public TextractJsonHandler(TextractService textractService) {
        this.textractService = textractService;
    }
    /**
     * Dispatches Textract actions received via the AwsJson11Controller.
     * The request body's Document content is not decoded — stub ignores it beyond
     * extracting Document.S3Object as an optional mock-response lookup key (see
     * {@link TextractService}); a Bytes-based Document has no such key, so mock
     * lookup is simply skipped and the default stub applies, same as always.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Textract action: {0}", action);
        return switch (action) {
            case "DetectDocumentText"         -> textractService.detectDocumentText(
                    extractS3Key(request, "Document"));
            case "AnalyzeDocument"            -> textractService.analyzeDocument(
                    extractS3Key(request, "Document"));
            case "StartDocumentTextDetection" -> textractService.startDocumentTextDetection();
            case "GetDocumentTextDetection"   -> textractService.getDocumentTextDetection(
                    getStringField(request, "JobId"));
            case "StartDocumentAnalysis"      -> textractService.startDocumentAnalysis();
            case "GetDocumentAnalysis"        -> textractService.getDocumentAnalysis(
                    getStringField(request, "JobId"));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Textract." + action))
                    .build();
        };
    }
    private String getStringField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }
    /**
     * Extracts an optional "Bucket/Name" mock-response lookup key from an S3Object-backed
     * document field. Returns null when the field is absent, not an object, or Bytes-backed
     * (no S3Object) — every caller treats a null key as "mock lookup does not apply".
     */
    private String extractS3Key(JsonNode request, String field) {
        if (request == null) {
            return null;
        }
        JsonNode s3Object = request.path(field).path("S3Object");
        if (!s3Object.isObject()) {
            return null;
        }
        JsonNode bucket = s3Object.get("Bucket");
        JsonNode name = s3Object.get("Name");
        if (bucket == null || !bucket.isTextual() || name == null || !name.isTextual()) {
            return null;
        }
        return bucket.asText() + "/" + name.asText();
    }
}
