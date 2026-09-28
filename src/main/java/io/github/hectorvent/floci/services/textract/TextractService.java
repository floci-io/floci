package io.github.hectorvent.floci.services.textract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AiMockConfigLoader;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsGeometry;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Dummy response builder for Amazon Textract. Stateless for sync operations.
 * Async operations (Start* and Get*) use an in-memory job store.
 * No real OCR or document analysis is performed: every call returns a fixed
 * stub Block list matching the AWS Textract wire format by default.
 * <p>
 * Callers can override the sync-action default per exact "Bucket/Name" S3Object
 * key via {@link AiMockConfigLoader} — see {@code docs/services/textract.md}
 * "Mock Responses". A Bytes-backed Document has no such key, so mock lookup is
 * simply skipped for it. The async Start and Get action pairs do not support
 * mocking (the mock key would need to be persisted alongside the job id) — out
 * of scope.
 *
 * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Operations.html">Textract API Reference</a>
 */
@ApplicationScoped
public class TextractService implements Resettable {
    static final String MODEL_VERSION = "1.0";
    private static final String SERVICE_KEY = "textract";
    /** AWS retains asynchronous results for about 7 days; an expired job reads as an invalid job. */
    static final Duration RESULT_RETENTION = Duration.ofDays(7);
    /** Hard cap so a producer that never reads its results cannot grow the store without bound. */
    static final int MAX_RETAINED_JOBS = 10_000;
    private final ObjectMapper objectMapper;
    private final AiMockConfigLoader mockConfigLoader;
    private final Clock clock;
    /** In-memory async job store: jobId to the job's type, creation time, and stable result. */
    private final ConcurrentHashMap<String, AsyncJob> asyncJobs = new ConcurrentHashMap<>();
    @Inject
    public TextractService(ObjectMapper objectMapper, AiMockConfigLoader mockConfigLoader) {
        this(objectMapper, mockConfigLoader, Clock.systemUTC());
    }
    TextractService(ObjectMapper objectMapper, AiMockConfigLoader mockConfigLoader, Clock clock) {
        this.objectMapper = objectMapper;
        this.mockConfigLoader = mockConfigLoader;
        this.clock = clock;
    }
    public void clear() {
        asyncJobs.clear();
    }
    /** A completed async job, retained until {@link #RESULT_RETENTION} elapses. */
    record AsyncJob(String jobType, Instant createdAt, ObjectNode result) {}
    /**
     * DetectDocumentText — returns a stub PAGE + LINE + WORD block hierarchy.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_DetectDocumentText.html
     */
    public Response detectDocumentText(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "DetectDocumentText");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
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
    public Response analyzeDocument(String mockKey) {
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, mockKey, "AnalyzeDocument");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
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
        storeJob(jobId, "TEXT_DETECTION", buildTextDetectionResult());
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }
    /**
     * GetDocumentTextDetection — returns the job's stable SUCCEEDED result until it expires.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_GetDocumentTextDetection.html
     */
    public Response getDocumentTextDetection(String jobId) {
        return Response.ok(requireJobResult(jobId, "TEXT_DETECTION")).build();
    }
    /**
     * StartDocumentAnalysis — enqueues a fake async job and immediately marks it SUCCEEDED.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_StartDocumentAnalysis.html
     */
    public Response startDocumentAnalysis() {
        String jobId = UUID.randomUUID().toString();
        storeJob(jobId, "DOCUMENT_ANALYSIS", buildDocumentAnalysisResult());
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }
    /**
     * GetDocumentAnalysis — returns the job's stable SUCCEEDED result until it expires.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_GetDocumentAnalysis.html
     */
    public Response getDocumentAnalysis(String jobId) {
        return Response.ok(requireJobResult(jobId, "DOCUMENT_ANALYSIS")).build();
    }
    private ObjectNode buildTextDetectionResult() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", "SUCCEEDED");
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("DetectDocumentTextModelVersion", MODEL_VERSION);
        return root;
    }
    private ObjectNode buildDocumentAnalysisResult() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", "SUCCEEDED");
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("AnalyzeDocumentModelVersion", MODEL_VERSION);
        return root;
    }
    private void storeJob(String jobId, String jobType, ObjectNode result) {
        sweepExpired();
        asyncJobs.put(jobId, new AsyncJob(jobType, clock.instant(), result));
        enforceCapacity();
    }
    // Private helpers
    private ObjectNode requireJobResult(String jobId, String expectedType) {
        if (jobId == null || jobId.isBlank()) {
            throw new AwsException("ValidationException", "JobId is required.", 400);
        }
        AsyncJob job = asyncJobs.get(jobId);
        if (job == null) {
            throw invalidJobId();
        }
        if (job.createdAt().isBefore(clock.instant().minus(RESULT_RETENTION))) {
            asyncJobs.remove(jobId);
            throw invalidJobId();
        }
        if (!expectedType.equals(job.jobType())) {
            throw new AwsException("InvalidJobIdException",
                    "Job was not started by the correct operation.", 400);
        }
        return job.result();
    }
    private static AwsException invalidJobId() {
        return new AwsException("InvalidJobIdException",
                "An invalid job identifier was passed to an Amazon Textract operation.", 400);
    }
    private void sweepExpired() {
        Instant cutoff = clock.instant().minus(RESULT_RETENTION);
        asyncJobs.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }
    private void enforceCapacity() {
        int excess = asyncJobs.size() - MAX_RETAINED_JOBS;
        if (excess <= 0) {
            return;
        }
        List<Map.Entry<String, AsyncJob>> oldest = asyncJobs.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(AsyncJob::createdAt)))
                .limit(excess)
                .toList();
        oldest.forEach(entry -> asyncJobs.remove(entry.getKey(), entry.getValue()));
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
        word.set("Geometry", AwsGeometry.buildGeometry(0.1, 0.1, 0.15, 0.05));
        word.put("Page", 1);
        blocks.add(word);
        // LINE block (child: WORD)
        ObjectNode line = objectMapper.createObjectNode();
        line.put("BlockType", "LINE");
        line.put("Id", lineId);
        line.put("Confidence", 99.9);
        line.put("Text", "Floci");
        line.set("Geometry", AwsGeometry.buildGeometry(0.1, 0.1, 0.15, 0.05));
        line.set("Relationships", buildRelationships("CHILD", wordId));
        line.put("Page", 1);
        blocks.add(line);
        // PAGE block (child: LINE)
        ObjectNode page = objectMapper.createObjectNode();
        page.put("BlockType", "PAGE");
        page.put("Id", pageId);
        page.put("Confidence", 99.9);
        page.set("Geometry", AwsGeometry.buildGeometry(0.0, 0.0, 1.0, 1.0));
        page.set("Relationships", buildRelationships("CHILD", lineId));
        page.put("Page", 1);
        blocks.add(page);
        return blocks;
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
