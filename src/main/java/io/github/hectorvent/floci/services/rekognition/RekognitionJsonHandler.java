package io.github.hectorvent.floci.services.rekognition;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import java.util.function.Predicate;
/**
 * JSON 1.1 handler for Amazon Rekognition API operations.
 * Dispatches X-Amz-Target: RekognitionService.* actions to {@link RekognitionService}.
 *
 * @see <a href="https://docs.aws.amazon.com/rekognition/latest/APIReference/Welcome.html">Rekognition API Reference</a>
 */
@ApplicationScoped
public class RekognitionJsonHandler {
    private static final Logger LOG = Logger.getLogger(RekognitionJsonHandler.class);
    private final RekognitionService rekognitionService;
    @Inject
    public RekognitionJsonHandler(RekognitionService rekognitionService) {
        this.rekognitionService = rekognitionService;
    }
    /**
     * Dispatches Rekognition actions received via the AwsJson11Controller.
     * Only sync image-analysis actions are implemented; face-collection persistence
     * (CreateCollection/IndexFaces/SearchFaces), custom-model training (Projects/
     * Datasets/ProjectVersions), live video (StreamProcessor), async video jobs
     * (the Start and Get pairs for Celebrity/Content/Face/Label/Person/Segment/Text
     * detection), and Face Liveness sessions are out of scope.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Rekognition action: {0}", action);
        return switch (action) {
            case "DetectLabels" -> rekognitionService.detectLabels(requireImage(request, "Image"));
            case "DetectFaces" -> rekognitionService.detectFaces(requireImage(request, "Image"));
            case "DetectText" -> rekognitionService.detectText(requireImage(request, "Image"));
            case "CompareFaces" -> {
                String sourceKey = requireImage(request, "SourceImage");
                requireImage(request, "TargetImage");
                yield rekognitionService.compareFaces(sourceKey);
            }
            case "DetectModerationLabels" -> rekognitionService.detectModerationLabels(requireImage(request, "Image"));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: RekognitionService." + action))
                    .build();
        };
    }
    /**
     * The Image shape (real content ignored — the response is a fixed stub) is still
     * required and must carry Bytes or S3Object, matching AWS's own required-member
     * and one-of-Bytes-or-S3Object modeling for Image/SourceImage/TargetImage. Returns
     * an optional "Bucket/Name" mock-response lookup key when S3Object is present (null
     * for a Bytes-backed image, which has no such key — mock lookup then simply doesn't
     * apply, same as always).
     */
    private String requireImage(JsonNode request, String field) {
        JsonNode image = request == null ? null : request.get(field);
        if (image == null || image.isNull()) {
            throw new AwsException("InvalidParameterException", field + " is a required field.", 400);
        }
        if (!image.isObject()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + field + "' is not a structure.", 400);
        }
        requireCorrectTypeIfPresent(image, "Bytes", JsonNode::isTextual, field);
        requireCorrectTypeIfPresent(image, "S3Object", JsonNode::isObject, field);
        if (!image.hasNonNull("Bytes") && !image.hasNonNull("S3Object")) {
            throw new AwsException("InvalidParameterException",
                    field + " must specify Bytes or S3Object.", 400);
        }
        return extractS3Key(image.get("S3Object"));
    }
    private String extractS3Key(JsonNode s3Object) {
        if (s3Object == null || !s3Object.isObject()) {
            return null;
        }
        JsonNode bucket = s3Object.get("Bucket");
        JsonNode name = s3Object.get("Name");
        if (bucket == null || !bucket.isTextual() || name == null || !name.isTextual()) {
            return null;
        }
        return bucket.asText() + "/" + name.asText();
    }
    /**
     * Bytes (a blob shape, base64-encoded string on the wire) and S3Object (a structure)
     * must each carry their modeled JSON type when present. Content-level correctness
     * (valid base64, S3Object.Bucket/Name presence — S3Object has no required members
     * in the modeled shape) is not validated: this stub never reads image content, and
     * enforcing more than the wire shape declares would invent stricter validation than
     * AWS's own model.
     */
    private void requireCorrectTypeIfPresent(JsonNode image, String member, Predicate<JsonNode> isCorrectType, String field) {
        JsonNode value = image.get(member);
        if (value != null && !value.isNull() && !isCorrectType.test(value)) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + field + "." + member + "' has the wrong type.", 400);
        }
    }
}
