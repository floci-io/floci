package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon SageMaker control plane.
 *
 * <p>Only the operations {@code aws_sagemaker_algorithm} needs for a full create, read, tag,
 * and delete round trip are implemented. Everything else falls through to a clean
 * {@code UnknownOperationException} rather than a stub success, so callers fail fast instead
 * of stranding a waiter.
 */
@ApplicationScoped
public class SageMakerJsonHandler {

    private final SageMakerService service;
    private final ObjectMapper objectMapper;

    @Inject
    public SageMakerJsonHandler(SageMakerService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateAlgorithm" -> handleCreateAlgorithm(request, region);
            case "DescribeAlgorithm" -> handleDescribeAlgorithm(request);
            case "DeleteAlgorithm" -> handleDeleteAlgorithm(request);
            case "ListAlgorithms" -> handleListAlgorithms(request);
            case "AddTags" -> handleAddTags(request);
            case "ListTags" -> handleListTags(request);
            case "DeleteTags" -> handleDeleteTags(request);
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
    }

    private Response handleCreateAlgorithm(JsonNode request, String region) {
        String name = textOrNull(request, "AlgorithmName");
        String description = textOrNull(request, "AlgorithmDescription");
        JsonNode trainingSpecification = request.get("TrainingSpecification");
        JsonNode inferenceSpecification = request.get("InferenceSpecification");
        JsonNode validationSpecification = request.get("ValidationSpecification");
        boolean certifyForMarketplace = request.path("CertifyForMarketplace").asBoolean(false);
        Map<String, String> tags = tagsFromList(request.get("Tags"));

        SageMakerAlgorithm algorithm = service.createAlgorithm(name, description, trainingSpecification,
                inferenceSpecification, validationSpecification, certifyForMarketplace, tags, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("AlgorithmArn", algorithm.getAlgorithmArn());
        return Response.ok(response).build();
    }

    private Response handleDescribeAlgorithm(JsonNode request) {
        String nameOrArn = textOrNull(request, "AlgorithmName");
        SageMakerAlgorithm algorithm = service.describeAlgorithm(nameOrArn);
        return Response.ok(toDescribeNode(algorithm)).build();
    }

    private Response handleDeleteAlgorithm(JsonNode request) {
        String name = textOrNull(request, "AlgorithmName");
        service.deleteAlgorithm(name);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListAlgorithms(JsonNode request) {
        String nameContains = textOrNull(request, "NameContains");
        List<SageMakerAlgorithm> algorithms = service.listAlgorithms(nameContains);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("AlgorithmSummaryList");
        for (SageMakerAlgorithm algorithm : algorithms) {
            ObjectNode summary = summaries.addObject();
            summary.put("AlgorithmName", algorithm.getAlgorithmName());
            summary.put("AlgorithmArn", algorithm.getAlgorithmArn());
            if (algorithm.getAlgorithmDescription() != null) {
                summary.put("AlgorithmDescription", algorithm.getAlgorithmDescription());
            }
            summary.put("CreationTime", epochSeconds(algorithm.getCreationTime()));
            summary.put("AlgorithmStatus", algorithm.getAlgorithmStatus());
        }
        return Response.ok(response).build();
    }

    private Response handleAddTags(JsonNode request) {
        String resourceArn = textOrNull(request, "ResourceArn");
        Map<String, String> tags = tagsFromList(request.get("Tags"));
        if (tags.isEmpty()) {
            throw new AwsException("ValidationException", "Tags is required.", 400);
        }
        service.addTags(resourceArn, tags);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsToList(service.listTags(resourceArn)));
        return Response.ok(response).build();
    }

    private Response handleListTags(JsonNode request) {
        String resourceArn = textOrNull(request, "ResourceArn");
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsToList(service.listTags(resourceArn)));
        return Response.ok(response).build();
    }

    private Response handleDeleteTags(JsonNode request) {
        String resourceArn = textOrNull(request, "ResourceArn");
        List<String> tagKeys = new ArrayList<>();
        if (request.has("TagKeys") && request.get("TagKeys").isArray()) {
            request.get("TagKeys").forEach(key -> tagKeys.add(key.asText()));
        }
        service.deleteTags(resourceArn, tagKeys);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode toDescribeNode(SageMakerAlgorithm algorithm) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AlgorithmName", algorithm.getAlgorithmName());
        node.put("AlgorithmArn", algorithm.getAlgorithmArn());
        if (algorithm.getAlgorithmDescription() != null) {
            node.put("AlgorithmDescription", algorithm.getAlgorithmDescription());
        }
        node.put("CreationTime", epochSeconds(algorithm.getCreationTime()));
        node.set("TrainingSpecification", algorithm.getTrainingSpecification());
        if (algorithm.getInferenceSpecification() != null && !algorithm.getInferenceSpecification().isNull()) {
            node.set("InferenceSpecification", algorithm.getInferenceSpecification());
        }
        if (algorithm.getValidationSpecification() != null && !algorithm.getValidationSpecification().isNull()) {
            node.set("ValidationSpecification", algorithm.getValidationSpecification());
        }
        node.put("AlgorithmStatus", algorithm.getAlgorithmStatus());
        ObjectNode statusDetails = node.putObject("AlgorithmStatusDetails");
        statusDetails.putArray("ValidationStatuses");
        statusDetails.putArray("ImageScanStatuses");
        if (algorithm.getProductId() != null) {
            node.put("ProductId", algorithm.getProductId());
        }
        node.put("CertifyForMarketplace", algorithm.isCertifyForMarketplace());
        return node;
    }

    private static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private static String textOrNull(JsonNode request, String field) {
        return request != null && request.hasNonNull(field) ? request.get(field).asText() : null;
    }

    private static Map<String, String> tagsFromList(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                if (tag.hasNonNull("Key")) {
                    tags.put(tag.get("Key").asText(), tag.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    private ArrayNode tagsToList(Map<String, String> tags) {
        ArrayNode list = objectMapper.createArrayNode();
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        return list;
    }
}
