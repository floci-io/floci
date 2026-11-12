package io.floci.aws.services.rekognition;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.function.Predicate;
import java.util.function.Supplier;
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
    private final RekognitionImageValidator imageValidator;
    private final RekognitionCollectionService collectionService;
    @Inject
    public RekognitionJsonHandler(RekognitionService rekognitionService, RekognitionImageValidator imageValidator,
                                  RekognitionCollectionService collectionService) {
        this.rekognitionService = rekognitionService;
        this.imageValidator = imageValidator;
        this.collectionService = collectionService;
    }
    /**
     * Dispatches Rekognition actions received via the AwsJson11Controller.
     * Sync image-analysis and face collections are implemented; custom-model training (Projects/
     * Datasets/ProjectVersions), live video (StreamProcessor), async video jobs
     * (the Start and Get pairs for Celebrity/Content/Face/Label/Person/Segment/Text
     * detection), and Face Liveness sessions are out of scope.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Rekognition action: {0}", action);
        Supplier<Response> operation = switch (action) {
            case "CreateCollection" -> () -> Response.ok(collectionService.createCollection(request, region)).build();
            case "DeleteCollection" -> () -> Response.ok(collectionService.deleteCollection(request, region)).build();
            case "DescribeCollection" -> () -> Response.ok(collectionService.describeCollection(request, region)).build();
            case "ListCollections" -> () -> Response.ok(collectionService.listCollections(request, region)).build();
            case "IndexFaces" -> () -> Response.ok(collectionService.indexFaces(request, region,
                    validImage(action, request, region))).build();
            case "ListFaces" -> () -> Response.ok(collectionService.listFaces(request, region)).build();
            case "DeleteFaces" -> () -> Response.ok(collectionService.deleteFaces(request, region)).build();
            case "SearchFaces" -> () -> Response.ok(collectionService.searchFaces(request, region)).build();
            case "SearchFacesByImage" -> () -> Response.ok(collectionService.searchFacesByImage(request, region,
                    validImage(action, request, region))).build();
            case "DetectLabels" -> () -> rekognitionService.detectLabels(validImage(action, request, region));
            case "DetectFaces" -> () -> rekognitionService.detectFaces(validImage(action, request, region));
            case "DetectText" -> () -> rekognitionService.detectText(validImage(action, request, region));
            case "CompareFaces" -> () -> {
                String sourceKey = requireImage(request, "SourceImage");
                requireImage(request, "TargetImage");
                imageValidator.validate(action, request, region, "SourceImage", "TargetImage");
                return rekognitionService.compareFaces(sourceKey);
            };
            case "DetectModerationLabels" -> () -> rekognitionService.detectModerationLabels(validImage(action, request, region));
            default -> null;
        };
        if (operation == null) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: RekognitionService." + action))
                    .build();
        }
        RekognitionRequestShapes.check(action, request);
        return operation.get();
    }
    /** The image's mock-response key, once the image has the shape and content Rekognition accepts. */
    private String validImage(String action, JsonNode request, String region) {
        String mockKey = requireImage(request, "Image");
        imageValidator.validate(action, request, region, "Image");
        return mockKey;
    }

    /**
     * The Image shape is required and must carry Bytes or S3Object, matching AWS's own required-member
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
     * (valid base64, S3Object.Bucket/Name presence: S3Object has no required members
     * in the modeled shape) is left to {@link RekognitionImageValidator}, which checks the
     * image itself the way Rekognition does.
     */
    private void requireCorrectTypeIfPresent(JsonNode image, String member, Predicate<JsonNode> isCorrectType, String field) {
        JsonNode value = image.get(member);
        if (value != null && !value.isNull() && !isCorrectType.test(value)) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + field + "." + member + "' has the wrong type.", 400);
        }
    }
}
