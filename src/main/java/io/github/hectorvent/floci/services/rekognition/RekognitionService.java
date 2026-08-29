package io.github.hectorvent.floci.services.rekognition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
/**
 * Dummy response builder for Amazon Rekognition. Stateless — every action ignores
 * the actual image content and returns a fixed but AWS-shaped response.
 * <p>
 * Content-listing operations (DetectLabels, DetectText) always return one canned
 * entry, matching the TextractService stub precedent. Face/moderation operations
 * (DetectFaces, CompareFaces, DetectModerationLabels) return empty results by
 * default — an honest "nothing detected" response, rather than fabricating
 * biometric attributes or a moderation flag for content that was never analyzed,
 * matching the ComprehendService PII-detection precedent.
 * <p>
 * Real detection logic is a planned follow-up; see the tracking issue for scope.
 *
 * @see <a href="https://docs.aws.amazon.com/rekognition/latest/APIReference/Welcome.html">Rekognition API Reference</a>
 */
@ApplicationScoped
public class RekognitionService {
    static final String MODEL_VERSION = "1.0";
    private final ObjectMapper objectMapper;
    @Inject
    public RekognitionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    /**
     * DetectLabels — always returns a single stub label.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectLabels.html
     */
    public Response detectLabels() {
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
    public Response detectFaces() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("FaceDetails");
        return Response.ok(root).build();
    }
    /**
     * DetectText — always returns one stub LINE and its child WORD, matching
     * Textract's PAGE/LINE/WORD block-hierarchy convention.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectText.html
     */
    public Response detectText() {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode detections = root.putArray("TextDetections");
        ObjectNode line = detections.addObject();
        line.put("DetectedText", "Floci");
        line.put("Type", "LINE");
        line.put("Id", 0);
        line.put("Confidence", 99.9);
        line.set("Geometry", buildGeometry(0.1, 0.1, 0.15, 0.05));
        ObjectNode word = detections.addObject();
        word.put("DetectedText", "Floci");
        word.put("Type", "WORD");
        word.put("Id", 1);
        word.put("ParentId", 0);
        word.put("Confidence", 99.9);
        word.set("Geometry", buildGeometry(0.1, 0.1, 0.15, 0.05));
        root.put("TextModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }
    /**
     * CompareFaces — always reports no matches (SourceImageFace is optional and
     * legitimately omitted when no face is found in the source image).
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_CompareFaces.html
     */
    public Response compareFaces() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("FaceMatches");
        root.putArray("UnmatchedFaces");
        return Response.ok(root).build();
    }
    /**
     * DetectModerationLabels — always reports no moderation labels found.
     * Response shape: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_DetectModerationLabels.html
     */
    public Response detectModerationLabels() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("ModerationLabels");
        root.put("ModerationModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }
    // Private helpers
    /**
     * Builds a Geometry object with BoundingBox and a 4-point Polygon — the same
     * shape Textract uses (Rekognition's Geometry shape is structurally identical).
     * @see <a href="https://docs.aws.amazon.com/rekognition/latest/APIReference/API_Geometry.html">Geometry</a>
     */
    private ObjectNode buildGeometry(double left, double top, double width, double height) {
        ObjectNode geometry = objectMapper.createObjectNode();
        ObjectNode bbox = geometry.putObject("BoundingBox");
        bbox.put("Width", width);
        bbox.put("Height", height);
        bbox.put("Left", left);
        bbox.put("Top", top);
        ArrayNode polygon = geometry.putArray("Polygon");
        addPoint(polygon, left, top);
        addPoint(polygon, left + width, top);
        addPoint(polygon, left + width, top + height);
        addPoint(polygon, left, top + height);
        return geometry;
    }
    private void addPoint(ArrayNode polygon, double x, double y) {
        ObjectNode point = polygon.addObject();
        point.put("X", x);
        point.put("Y", y);
    }
}
