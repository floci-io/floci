package io.github.hectorvent.floci.services.textract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Dummy response builder for Amazon Textract. Stateless for sync operations.
 * Async operations (Start* and Get*) use an in-memory job store.
 * No real OCR or document analysis is performed: every call returns a fixed
 * stub Block list matching the AWS Textract wire format.
 *
 * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Operations.html">Textract API Reference</a>
 */
@ApplicationScoped
public class TextractService {
    static final String MODEL_VERSION = "1.0";
    private final ObjectMapper objectMapper;
    /** In-memory async job store: jobId to jobType ("TEXT_DETECTION" or "DOCUMENT_ANALYSIS"). */
    private final ConcurrentHashMap<String, String> asyncJobs = new ConcurrentHashMap<>();
    @Inject
    public TextractService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    /**
     * DetectDocumentText — returns a stub PAGE + LINE + WORD block hierarchy.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_DetectDocumentText.html
     */
    public Response detectDocumentText() {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("DetectDocumentTextModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }
    /**
     * AnalyzeDocument — returns the same stub blocks; FeatureTypes are accepted but ignored.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_AnalyzeDocument.html
     */
    public Response analyzeDocument() {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("AnalyzeDocumentModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }
    /**
     * StartDocumentTextDetection — enqueues a fake async job and immediately marks it SUCCEEDED.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_StartDocumentTextDetection.html
     */
    public Response startDocumentTextDetection() {
        String jobId = UUID.randomUUID().toString();
        asyncJobs.put(jobId, "TEXT_DETECTION");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }
    /**
     * GetDocumentTextDetection — returns SUCCEEDED + stub blocks for any known JobId.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_GetDocumentTextDetection.html
     */
    public Response getDocumentTextDetection(String jobId) {
        requireKnownJob(jobId, "TEXT_DETECTION");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", "SUCCEEDED");
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("DetectDocumentTextModelVersion", MODEL_VERSION);
        //after a successful job, we remove it to avoid memory growth
        asyncJobs.remove(jobId);
        return Response.ok(root).build();
    }
    /**
     * StartDocumentAnalysis — enqueues a fake async job and immediately marks it SUCCEEDED.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_StartDocumentAnalysis.html
     */
    public Response startDocumentAnalysis() {
        String jobId = UUID.randomUUID().toString();
        asyncJobs.put(jobId, "DOCUMENT_ANALYSIS");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }
    /**
     * GetDocumentAnalysis — returns SUCCEEDED + stub blocks for any known JobId.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_GetDocumentAnalysis.html
     */
    public Response getDocumentAnalysis(String jobId) {
        requireKnownJob(jobId, "DOCUMENT_ANALYSIS");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", "SUCCEEDED");
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("AnalyzeDocumentModelVersion", MODEL_VERSION);

        //after a successful job, we remove it to avoid memory growth
        asyncJobs.remove(jobId);

        return Response.ok(root).build();
    }
    // Private helpers
    private void requireKnownJob(String jobId, String expectedType) {
        if (jobId == null || jobId.isBlank()) {
            throw new AwsException("ValidationException", "JobId is required.", 400);
        }
        String type = asyncJobs.get(jobId);
        if (type == null) {
            throw new AwsException("InvalidJobIdException",
                    "An invalid job identifier was passed to an Amazon Textract operation.", 400);
        }
        if (!expectedType.equals(type)) {
            throw new AwsException("InvalidJobIdException",
                    "Job was not started by the correct operation.", 400);
        }
    }
    private ObjectNode buildDocumentMetadata(int pages) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("Pages", pages);
        return meta;
    }
    /**
     * Builds a minimal AWS-shaped Block hierarchy: PAGE to LINE to WORD.
     * Each Block follows https://docs.aws.amazon.com/textract/latest/dg/API_Block.html
     */
    private ArrayNode buildStubBlocks() {
        ArrayNode blocks = objectMapper.createArrayNode();
        String wordId = UUID.randomUUID().toString();
        String lineId = UUID.randomUUID().toString();
        String pageId = UUID.randomUUID().toString();
        // WORD block
        ObjectNode word = objectMapper.createObjectNode();
        word.put("BlockType", "WORD");
        word.put("Id", wordId);
        word.put("Confidence", 99.9);
        word.put("Text", "Floci");
        word.set("Geometry", buildGeometry(0.1, 0.1, 0.15, 0.05));
        word.put("Page", 1);
        blocks.add(word);
        // LINE block (child: WORD)
        ObjectNode line = objectMapper.createObjectNode();
        line.put("BlockType", "LINE");
        line.put("Id", lineId);
        line.put("Confidence", 99.9);
        line.put("Text", "Floci");
        line.set("Geometry", buildGeometry(0.1, 0.1, 0.15, 0.05));
        line.set("Relationships", buildRelationships("CHILD", wordId));
        line.put("Page", 1);
        blocks.add(line);
        // PAGE block (child: LINE)
        ObjectNode page = objectMapper.createObjectNode();
        page.put("BlockType", "PAGE");
        page.put("Id", pageId);
        page.put("Confidence", 99.9);
        page.set("Geometry", buildGeometry(0.0, 0.0, 1.0, 1.0));
        page.set("Relationships", buildRelationships("CHILD", lineId));
        page.put("Page", 1);
        blocks.add(page);
        return blocks;
    }
    /**
     * Builds a Geometry object with BoundingBox and a 4-point Polygon.
     * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Geometry.html">Geometry</a>
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
    /**
     * Builds a single Relationship entry.
     * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Relationship.html">Relationship</a>
     */
    private ArrayNode buildRelationships(String type, String... childIds) {
        ArrayNode relationships = objectMapper.createArrayNode();
        ObjectNode rel = relationships.addObject();
        rel.put("Type", type);
        ArrayNode ids = rel.putArray("Ids");
        for (String id : childIds) {
            ids.add(id);
        }
        return relationships;
    }
}
