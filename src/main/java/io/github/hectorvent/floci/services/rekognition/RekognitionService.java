package io.github.hectorvent.floci.services.rekognition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AiMockConfigLoader;
import io.github.hectorvent.floci.core.common.AwsGeometry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
/**
 * Dummy response builder for Amazon Rekognition. Stateless — every action ignores
 * the actual image content and returns a fixed but AWS-shaped response by default.
 * <p>
 * Content-listing operations (DetectLabels, DetectText) always return one canned
 * entry, matching the TextractService stub precedent. Face/moderation operations
 * (DetectFaces, CompareFaces, DetectModerationLabels) return empty results by
 * default — an honest "nothing detected" response, rather than fabricating
 * biometric attributes or a moderation flag for content that was never analyzed,
 * matching the ComprehendService PII-detection precedent.
 * <p>
 * Callers can override the default stub per exact "Bucket/Name" S3Object key via
 * {@link AiMockConfigLoader} — see {@code docs/services/rekognition.md}
 * "Mock Responses". CompareFaces keys off SourceImage only (TargetImage has no
 * independent key in this scheme).
 * <p>
 * Real detection logic is a planned follow-up; see the tracking issue for scope.
 *
 * @see <a href="https://docs.aws.amazon.com/rekognition/latest/APIReference/Welcome.html">Rekognition API Reference</a>
 */
@ApplicationScoped
public class RekognitionService {
    static final String MODEL_VERSION = "1.0";
    private static final String SERVICE_KEY = "rekognition";
    private final ObjectMapper objectMapper;
    private final AiMockConfigLoader mockConfigLoader;
    @Inject
    public RekognitionService(ObjectMapper objectMapper, AiMockConfigLoader mockConfigLoader) {
        this.objectMapper = objectMapper;
        this.mockConfigLoader = mockConfigLoader;
    }
    /**
     * DetectLabels — always returns a single stub label.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectLabels.html
     */
    public Response detectLabels(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "DetectLabels");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode labels = root.putArray("Labels");
        ObjectNode label = labels.addObject();
        label.put("Name", "Floci");
        label.put("Confidence", 99.9);
        root.put("LabelModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }
    /**
     * DetectFaces — always reports no faces found.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectFaces.html
     */
    public Response detectFaces(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "DetectFaces");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("FaceDetails");
        return Response.ok(root).build();
    }
    /**
     * DetectText — always returns one stub LINE and its child WORD, matching
     * Textract's PAGE/LINE/WORD block-hierarchy convention.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectText.html
     */
    public Response detectText(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "DetectText");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode detections = root.putArray("TextDetections");
        ObjectNode line = detections.addObject();
        line.put("DetectedText", "Floci");
        line.put("Type", "LINE");
        line.put("Id", 0);
        line.put("Confidence", 99.9);
        line.set("Geometry", AwsGeometry.buildGeometry(0.1, 0.1, 0.15, 0.05));
        ObjectNode word = detections.addObject();
        word.put("DetectedText", "Floci");
        word.put("Type", "WORD");
        word.put("Id", 1);
        word.put("ParentId", 0);
        word.put("Confidence", 99.9);
        word.set("Geometry", AwsGeometry.buildGeometry(0.1, 0.1, 0.15, 0.05));
        root.put("TextModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }
    /**
     * CompareFaces — always reports no matches (SourceImageFace is optional and
     * legitimately omitted when no face is found in the source image).
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_CompareFaces.html
     */
    public Response compareFaces(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "CompareFaces");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("FaceMatches");
        root.putArray("UnmatchedFaces");
        return Response.ok(root).build();
    }
    /**
     * DetectModerationLabels — always reports no moderation labels found.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectModerationLabels.html
     */
    public Response detectModerationLabels(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "DetectModerationLabels");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("ModerationLabels");
        root.put("ModerationModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }
}
