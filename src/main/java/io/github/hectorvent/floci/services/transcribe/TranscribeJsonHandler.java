package io.github.hectorvent.floci.services.transcribe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.transcribe.model.TranscriptionJob;
import io.github.hectorvent.floci.services.transcribe.model.VocabularyInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * JSON 1.1 handler for Amazon Transcribe API operations.
 * Dispatches X-Amz-Target: Transcribe.* actions to {@link TranscribeService}.
 *
 * @see <a href="https://docs.aws.amazon.com/transcribe/latest/APIReference/Welcome.html">Transcribe API</a>
 */
@ApplicationScoped
public class TranscribeJsonHandler {

    private static final Logger LOG = Logger.getLogger(TranscribeJsonHandler.class);

    private final TranscribeService transcribeService;
    private final ObjectMapper objectMapper;

    @Inject
    public TranscribeJsonHandler(TranscribeService transcribeService, ObjectMapper objectMapper) {
        this.transcribeService = transcribeService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Transcribe action: {0}", action);
        return switch (action) {
            case "StartTranscriptionJob" -> {
                TranscriptionJob job = transcribeService.startTranscriptionJob(
                        getStringField(request, "TranscriptionJobName"),
                        getMediaFileUri(request),
                        getStringField(request, "LanguageCode"),
                        getStringField(request, "MediaFormat"));
                yield Response.ok(Map.of("TranscriptionJob", job)).build();
            }
            case "GetTranscriptionJob" -> {
                TranscriptionJob job = transcribeService.getTranscriptionJob(
                        getStringField(request, "TranscriptionJobName"));
                yield Response.ok(Map.of("TranscriptionJob", job)).build();
            }
            case "ListTranscriptionJobs" -> {
                var result = transcribeService.listTranscriptionJobs(
                        getStringField(request, "Status"),
                        getStringField(request, "JobNameContains"),
                        getIntField(request, "MaxResults"));
                ObjectNode root = objectMapper.createObjectNode();
                if (result.status() != null) {
                    root.put("Status", result.status());
                }
                root.set("TranscriptionJobSummaries",
                        objectMapper.valueToTree(result.summaries()));
                if (result.nextToken() != null) {
                    root.put("NextToken", result.nextToken());
                }
                yield Response.ok(root).build();
            }
            case "DeleteTranscriptionJob" -> {
                transcribeService.deleteTranscriptionJob(
                        getStringField(request, "TranscriptionJobName"));
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "CreateVocabulary" -> {
                VocabularyInfo vocab = transcribeService.createVocabulary(
                        getStringField(request, "VocabularyName"),
                        getStringField(request, "LanguageCode"));
                yield Response.ok(vocab).build();
            }
            case "GetVocabulary" -> {
                VocabularyInfo vocab = transcribeService.getVocabulary(
                        getStringField(request, "VocabularyName"));
                yield Response.ok(vocab).build();
            }
            case "ListVocabularies" -> {
                var result = transcribeService.listVocabularies(
                        getStringField(request, "StateEquals"),
                        getStringField(request, "NameContains"),
                        getIntField(request, "MaxResults"));
                ObjectNode root = objectMapper.createObjectNode();
                if (result.status() != null) {
                    root.put("Status", result.status());
                }
                root.set("Vocabularies", objectMapper.valueToTree(result.vocabularies()));
                if (result.nextToken() != null) {
                    root.put("NextToken", result.nextToken());
                }
                yield Response.ok(root).build();
            }
            case "DeleteVocabulary" -> {
                transcribeService.deleteVocabulary(
                        getStringField(request, "VocabularyName"));
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Transcribe." + action))
                    .build();
        };
    }

    private String getStringField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }

    private String getMediaFileUri(JsonNode request) {
        if (request == null) return null;
        JsonNode media = request.path("Media");
        if (media.isMissingNode()) return null;
        JsonNode uri = media.get("MediaFileUri");
        return (uri != null && !uri.isNull()) ? uri.asText() : null;
    }

    private Integer getIntField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asInt() : null;
    }
}
