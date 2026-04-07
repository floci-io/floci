package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.ecr.model.AuthorizationData;
import io.github.hectorvent.floci.services.ecr.model.Image;
import io.github.hectorvent.floci.services.ecr.model.ImageDetail;
import io.github.hectorvent.floci.services.ecr.model.ImageFailure;
import io.github.hectorvent.floci.services.ecr.model.ImageIdentifier;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * AWS JSON 1.1 dispatcher for the {@code AmazonEC2ContainerRegistry_V20150921}
 * target prefix.
 */
@ApplicationScoped
public class EcrJsonHandler {

    private final EcrService service;
    private final ObjectMapper objectMapper;

    @Inject
    public EcrJsonHandler(EcrService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateRepository" -> handleCreateRepository(request, region);
            case "DescribeRepositories" -> handleDescribeRepositories(request, region);
            case "DeleteRepository" -> handleDeleteRepository(request, region);
            case "GetAuthorizationToken" -> handleGetAuthorizationToken(request);
            case "ListImages" -> handleListImages(request, region);
            case "DescribeImages" -> handleDescribeImages(request, region);
            case "BatchGetImage" -> handleBatchGetImage(request, region);
            case "BatchDeleteImage" -> handleBatchDeleteImage(request, region);
            case "PutImageTagMutability" -> handlePutImageTagMutability(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            case "PutLifecyclePolicy" -> handlePutLifecyclePolicy(request, region);
            case "GetLifecyclePolicy" -> handleGetLifecyclePolicy(request, region);
            case "DeleteLifecyclePolicy" -> handleDeleteLifecyclePolicy(request, region);
            case "SetRepositoryPolicy" -> handleSetRepositoryPolicy(request, region);
            case "GetRepositoryPolicy" -> handleGetRepositoryPolicy(request, region);
            case "DeleteRepositoryPolicy" -> handleDeleteRepositoryPolicy(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation",
                            "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateRepository(JsonNode request, String region) {
        String repositoryName = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String tagMutability = request.path("imageTagMutability").asText(null);
        Boolean scanOnPush = request.path("imageScanningConfiguration").has("scanOnPush")
                ? request.path("imageScanningConfiguration").path("scanOnPush").asBoolean()
                : null;
        String encType = request.path("encryptionConfiguration").path("encryptionType").asText(null);
        String kmsKey = request.path("encryptionConfiguration").path("kmsKey").asText(null);
        Map<String, String> tags = parseTags(request.path("tags"));

        Repository repo = service.createRepository(repositoryName, registryId, tagMutability,
                scanOnPush, encType, kmsKey, tags, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("repository", buildRepository(repo));
        return Response.ok(response).build();
    }

    private Response handleDescribeRepositories(JsonNode request, String region) {
        List<String> names = parseStringList(request.path("repositoryNames"));
        String registryId = request.path("registryId").asText(null);

        List<Repository> repos = service.describeRepositories(names, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (Repository r : repos) {
            arr.add(buildRepository(r));
        }
        response.set("repositories", arr);
        return Response.ok(response).build();
    }

    private Response handleDeleteRepository(JsonNode request, String region) {
        String repositoryName = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        boolean force = request.path("force").asBoolean(false);

        Repository repo = service.deleteRepository(repositoryName, registryId, force, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("repository", buildRepository(repo));
        return Response.ok(response).build();
    }

    private Response handleGetAuthorizationToken(JsonNode request) {
        AuthorizationData data = service.getAuthorizationToken();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("authorizationToken", data.getAuthorizationToken());
        entry.put("expiresAt", data.getExpiresAt().getEpochSecond());
        entry.put("proxyEndpoint", data.getProxyEndpoint());
        arr.add(entry);
        response.set("authorizationData", arr);
        return Response.ok(response).build();
    }

    // ============================================================
    // Image inspection / batch operations
    // ============================================================

    private Response handleListImages(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        List<ImageIdentifier> ids = service.listImages(repo, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (ImageIdentifier id : ids) {
            arr.add(buildImageIdentifier(id));
        }
        response.set("imageIds", arr);
        return Response.ok(response).build();
    }

    private Response handleDescribeImages(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        List<ImageIdentifier> requested = parseImageIds(request.path("imageIds"));

        EcrService.DescribeImagesResult result = service.describeImages(repo, requested, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = objectMapper.createArrayNode();
        for (ImageDetail d : result.imageDetails()) {
            details.add(buildImageDetail(d));
        }
        response.set("imageDetails", details);
        ArrayNode failures = objectMapper.createArrayNode();
        for (ImageFailure f : result.failures()) {
            failures.add(buildImageFailure(f));
        }
        response.set("failures", failures);
        return Response.ok(response).build();
    }

    private Response handleBatchGetImage(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        List<ImageIdentifier> ids = parseImageIds(request.path("imageIds"));
        List<String> accepted = parseStringList(request.path("acceptedMediaTypes"));

        EcrService.BatchGetImageResult result = service.batchGetImage(repo, ids, accepted, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode imgs = objectMapper.createArrayNode();
        for (Image img : result.images()) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("registryId", img.getRegistryId());
            n.put("repositoryName", img.getRepositoryName());
            n.set("imageId", buildImageIdentifier(img.getImageId()));
            if (img.getImageManifest() != null) {
                n.put("imageManifest", img.getImageManifest());
            }
            if (img.getImageManifestMediaType() != null) {
                n.put("imageManifestMediaType", img.getImageManifestMediaType());
            }
            imgs.add(n);
        }
        response.set("images", imgs);
        ArrayNode failures = objectMapper.createArrayNode();
        for (ImageFailure f : result.failures()) {
            failures.add(buildImageFailure(f));
        }
        response.set("failures", failures);
        return Response.ok(response).build();
    }

    private Response handleBatchDeleteImage(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        List<ImageIdentifier> ids = parseImageIds(request.path("imageIds"));

        EcrService.BatchDeleteImageResult result = service.batchDeleteImage(repo, ids, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (ImageIdentifier id : result.imageIds()) {
            arr.add(buildImageIdentifier(id));
        }
        response.set("imageIds", arr);
        ArrayNode failures = objectMapper.createArrayNode();
        for (ImageFailure f : result.failures()) {
            failures.add(buildImageFailure(f));
        }
        response.set("failures", failures);
        return Response.ok(response).build();
    }

    // ============================================================
    // Tag mutability + resource tags
    // ============================================================

    private Response handlePutImageTagMutability(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String mutability = request.path("imageTagMutability").asText(null);

        Repository updated = service.putImageTagMutability(repo, registryId, mutability, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", updated.getRegistryId());
        response.put("repositoryName", updated.getRepositoryName());
        response.put("imageTagMutability", updated.getImageTagMutability());
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText(null);
        String repoName = repoNameFromArn(resourceArn);
        Map<String, String> tags = parseTags(request.path("tags"));
        service.tagResource(repoName, accountFromArn(resourceArn), tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText(null);
        String repoName = repoNameFromArn(resourceArn);
        List<String> keys = parseStringList(request.path("tagKeys"));
        service.untagResource(repoName, accountFromArn(resourceArn), keys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText(null);
        String repoName = repoNameFromArn(resourceArn);
        Map<String, String> tags = service.listTagsForResource(repoName, accountFromArn(resourceArn), region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (Map.Entry<String, String> e : tags.entrySet()) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("Key", e.getKey());
            entry.put("Value", e.getValue());
            arr.add(entry);
        }
        response.set("tags", arr);
        return Response.ok(response).build();
    }

    // ============================================================
    // Lifecycle + repository policies
    // ============================================================

    private Response handlePutLifecyclePolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String text = request.path("lifecyclePolicyText").asText(null);
        Repository updated = service.putLifecyclePolicy(repo, registryId, text, region);
        return lifecycleResponse(updated);
    }

    private Response handleGetLifecyclePolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        Repository r = service.getLifecyclePolicy(repo, registryId, region);
        return lifecycleResponse(r);
    }

    private Response handleDeleteLifecyclePolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        Repository r = service.deleteLifecyclePolicy(repo, registryId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", r.getRegistryId());
        response.put("repositoryName", r.getRepositoryName());
        return Response.ok(response).build();
    }

    private Response lifecycleResponse(Repository r) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", r.getRegistryId());
        response.put("repositoryName", r.getRepositoryName());
        if (r.getLifecyclePolicyText() != null) {
            response.put("lifecyclePolicyText", r.getLifecyclePolicyText());
        }
        return Response.ok(response).build();
    }

    private Response handleSetRepositoryPolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String text = request.path("policyText").asText(null);
        Repository updated = service.setRepositoryPolicy(repo, registryId, text, region);
        return repoPolicyResponse(updated);
    }

    private Response handleGetRepositoryPolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        Repository r = service.getRepositoryPolicy(repo, registryId, region);
        return repoPolicyResponse(r);
    }

    private Response handleDeleteRepositoryPolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        Repository r = service.deleteRepositoryPolicy(repo, registryId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", r.getRegistryId());
        response.put("repositoryName", r.getRepositoryName());
        return Response.ok(response).build();
    }

    private Response repoPolicyResponse(Repository r) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", r.getRegistryId());
        response.put("repositoryName", r.getRepositoryName());
        if (r.getRepositoryPolicyText() != null) {
            response.put("policyText", r.getRepositoryPolicyText());
        }
        return Response.ok(response).build();
    }

    // ============================================================
    // Builders / parsers
    // ============================================================

    private ObjectNode buildImageIdentifier(ImageIdentifier id) {
        ObjectNode n = objectMapper.createObjectNode();
        if (id.getImageTag() != null) n.put("imageTag", id.getImageTag());
        if (id.getImageDigest() != null) n.put("imageDigest", id.getImageDigest());
        return n;
    }

    private ObjectNode buildImageDetail(ImageDetail d) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("registryId", d.getRegistryId());
        n.put("repositoryName", d.getRepositoryName());
        if (d.getImageDigest() != null) n.put("imageDigest", d.getImageDigest());
        ArrayNode tags = objectMapper.createArrayNode();
        for (String t : d.getImageTags()) tags.add(t);
        n.set("imageTags", tags);
        n.put("imageSizeInBytes", d.getImageSizeInBytes());
        if (d.getImagePushedAt() != null) {
            n.put("imagePushedAt", d.getImagePushedAt().getEpochSecond());
        }
        if (d.getImageManifestMediaType() != null) {
            n.put("imageManifestMediaType", d.getImageManifestMediaType());
        }
        if (d.getArtifactMediaType() != null) {
            n.put("artifactMediaType", d.getArtifactMediaType());
        }
        return n;
    }

    private ObjectNode buildImageFailure(ImageFailure f) {
        ObjectNode n = objectMapper.createObjectNode();
        if (f.getImageId() != null) n.set("imageId", buildImageIdentifier(f.getImageId()));
        if (f.getFailureCode() != null) n.put("failureCode", f.getFailureCode());
        if (f.getFailureReason() != null) n.put("failureReason", f.getFailureReason());
        return n;
    }

    private static List<ImageIdentifier> parseImageIds(JsonNode node) {
        List<ImageIdentifier> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return out;
        }
        node.forEach(e -> out.add(new ImageIdentifier(
                e.path("imageTag").asText(null),
                e.path("imageDigest").asText(null))));
        return out;
    }

    private static String repoNameFromArn(String arn) {
        if (arn == null) return null;
        int idx = arn.indexOf(":repository/");
        return idx < 0 ? null : arn.substring(idx + ":repository/".length());
    }

    private static String accountFromArn(String arn) {
        if (arn == null) return null;
        // arn:aws:ecr:<region>:<account>:repository/<name>
        String[] parts = arn.split(":");
        return parts.length >= 5 ? parts[4] : null;
    }

    private ObjectNode buildRepository(Repository repo) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("repositoryArn", repo.getRepositoryArn());
        n.put("registryId", repo.getRegistryId());
        n.put("repositoryName", repo.getRepositoryName());
        n.put("repositoryUri", repo.getRepositoryUri());
        if (repo.getCreatedAt() != null) {
            n.put("createdAt", repo.getCreatedAt().getEpochSecond());
        }
        n.put("imageTagMutability", repo.getImageTagMutability());

        ObjectNode scanCfg = objectMapper.createObjectNode();
        scanCfg.put("scanOnPush", repo.isScanOnPush());
        n.set("imageScanningConfiguration", scanCfg);

        ObjectNode enc = objectMapper.createObjectNode();
        enc.put("encryptionType", repo.getEncryptionType());
        if (repo.getKmsKey() != null) {
            enc.put("kmsKey", repo.getKmsKey());
        }
        n.set("encryptionConfiguration", enc);
        return n;
    }

    private static List<String> parseStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, String> parseTags(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return new HashMap<>();
        }
        Map<String, String> tags = new HashMap<>();
        Iterator<JsonNode> it = node.elements();
        while (it.hasNext()) {
            JsonNode entry = it.next();
            String key = entry.path("Key").asText(null);
            String value = entry.path("Value").asText("");
            if (key != null) {
                tags.put(key, value);
            }
        }
        return tags;
    }
}
