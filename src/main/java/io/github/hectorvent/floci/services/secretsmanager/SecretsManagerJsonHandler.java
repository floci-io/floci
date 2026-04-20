package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class SecretsManagerJsonHandler {

    private final SecretsManagerService service;
    private final ObjectMapper objectMapper;

    @Inject
    public SecretsManagerJsonHandler(SecretsManagerService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateSecret" -> handleCreateSecret(request, region);
            case "GetSecretValue" -> handleGetSecretValue(request, region);
            case "PutSecretValue" -> handlePutSecretValue(request, region);
            case "UpdateSecret" -> handleUpdateSecret(request, region);
            case "DescribeSecret" -> handleDescribeSecret(request, region);
            case "ListSecrets" -> handleListSecrets(request, region);
            case "DeleteSecret" -> handleDeleteSecret(request, region);
            case "RotateSecret" -> handleRotateSecret(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListSecretVersionIds" -> handleListSecretVersionIds(request, region);
            case "GetResourcePolicy" -> handleGetResourcePolicy(request, region);
            case "GetRandomPassword" -> handleGetRandomPassword(request, region);
            case "BatchGetSecretValue" -> handleBatchGetSecretValue(request, region);
            case "DeleteResourcePolicy" -> Response.ok(objectMapper.createObjectNode()).build();
            case "PutResourcePolicy" -> Response.ok(objectMapper.createObjectNode()).build();
            case "UpdateSecretVersionStage" -> handleUpdateSecretVersionStage(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleBatchGetSecretValue(JsonNode request, String region) {
        if (!request.has("SecretIdList") && !request.has("Filters")) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException", "You must specify either SecretIdList or Filters."))
                    .build();
        }

        List<String> secretIdList = new ArrayList<>();
        if (request.has("SecretIdList")) {
            request.path("SecretIdList").forEach(id -> secretIdList.add(id.asText()));
        }

        // Filters are not fully implemented yet, but for now we only support SecretIdList
        List<SecretsManagerService.BatchSecretValue> values = service.batchGetSecretValue(secretIdList, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode secretValues = objectMapper.createArrayNode();
        for (SecretsManagerService.BatchSecretValue value : values) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("ARN", value.arn());
            node.put("Name", value.name());
            node.put("VersionId", value.versionId());
            if (value.secretString() != null) {
                node.put("SecretString", value.secretString());
            }
            if (value.secretBinary() != null) {
                node.put("SecretBinary", value.secretBinary());
            }
            if (value.createdDate() != null) {
                node.put("CreatedDate", value.createdDate().toEpochMilli() / 1000.0);
            }
            ArrayNode stages = objectMapper.createArrayNode();
            if (value.versionStages() != null) {
                value.versionStages().forEach(stages::add);
            }
            node.set("VersionStages", stages);
            secretValues.add(node);
        }
        response.set("SecretValues", secretValues);
        return Response.ok(response).build();
    }

    private Response handleCreateSecret(JsonNode request, String region) {
        String name = request.path("Name").asText();
        String secretString = request.has("SecretString") ? request.path("SecretString").asText() : null;
        String secretBinary = request.has("SecretBinary") ? request.path("SecretBinary").asText() : null;
        String description = request.has("Description") ? request.path("Description").asText() : null;
        String kmsKeyId = request.has("KmsKeyId") ? request.path("KmsKeyId").asText() : null;
        List<Secret.Tag> tags = parseTags(request);

        Secret secret = service.createSecret(name, secretString, secretBinary, description, kmsKeyId, tags, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        response.put("VersionId", secret.getCurrentVersionId());
        return Response.ok(response).build();
    }

    private Response handleGetSecretValue(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        String versionId = request.has("VersionId") ? request.path("VersionId").asText() : null;
        String versionStage = request.has("VersionStage") ? request.path("VersionStage").asText() : null;

        Secret secret = service.describeSecret(secretId, region);
        SecretVersion version = service.getSecretValue(secretId, versionId, versionStage, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        response.put("VersionId", version.getVersionId());
        if (version.getSecretString() != null) {
            response.put("SecretString", version.getSecretString());
        }
        if (version.getSecretBinary() != null) {
            response.put("SecretBinary", version.getSecretBinary());
        }
        if (version.getCreatedDate() != null) {
            response.put("CreatedDate", version.getCreatedDate().toEpochMilli() / 1000.0);
        }
        ArrayNode stages = objectMapper.createArrayNode();
        if (version.getVersionStages() != null) {
            version.getVersionStages().forEach(stages::add);
        }
        response.set("VersionStages", stages);
        return Response.ok(response).build();
    }

    private Response handlePutSecretValue(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        String secretString = request.has("SecretString") ? request.path("SecretString").asText() : null;
        String secretBinary = request.has("SecretBinary") ? request.path("SecretBinary").asText() : null;

        List<String> versionStages = request.has("VersionStages") && request.path("VersionStages").isArray()
                ? StreamSupport.stream(request.path("VersionStages").spliterator(), false).map(JsonNode::asText).toList()
                : null;

        Secret secret = service.describeSecret(secretId, region);
        SecretVersion version = service.putSecretValue(secretId, secretString, secretBinary, region, versionStages);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        response.put("VersionId", version.getVersionId());
        ArrayNode stages = objectMapper.createArrayNode();
        if (version.getVersionStages() != null) {
            version.getVersionStages().forEach(stages::add);
        }
        response.set("VersionStages", stages);
        return Response.ok(response).build();
    }

    private Response handleUpdateSecret(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        String description = request.has("Description") ? request.path("Description").asText() : null;
        String kmsKeyId = request.has("KmsKeyId") ? request.path("KmsKeyId").asText() : null;
        String secretString = request.has("SecretString") ? request.path("SecretString").asText() : null;
        String secretBinary = request.has("SecretBinary") ? request.path("SecretBinary").asText() : null;

        Secret secret = service.updateSecret(secretId, description, kmsKeyId, region);

        String versionId = null;
        if (secretString != null || secretBinary != null) {
            SecretVersion version = service.putSecretValue(secretId, secretString, secretBinary, region, null);
            versionId = version.getVersionId();
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        if (versionId != null) {
            response.put("VersionId", versionId);
        }
        return Response.ok(response).build();
    }

    private Response handleDescribeSecret(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        Secret secret = service.describeSecret(secretId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        if (secret.getDescription() != null) {
            response.put("Description", secret.getDescription());
        }
        if (secret.getKmsKeyId() != null) {
            response.put("KmsKeyId", secret.getKmsKeyId());
        }
        response.put("RotationEnabled", secret.isRotationEnabled());
        if (secret.getCreatedDate() != null) {
            response.put("CreatedDate", secret.getCreatedDate().toEpochMilli() / 1000.0);
        }
        if (secret.getLastChangedDate() != null) {
            response.put("LastChangedDate", secret.getLastChangedDate().toEpochMilli() / 1000.0);
        }
        if (secret.getDeletedDate() != null) {
            response.put("DeletedDate", secret.getDeletedDate().toEpochMilli() / 1000.0);
        }

        ArrayNode tagsArray = objectMapper.createArrayNode();
        if (secret.getTags() != null) {
            for (Secret.Tag tag : secret.getTags()) {
                ObjectNode tagNode = objectMapper.createObjectNode();
                tagNode.put("Key", tag.key());
                tagNode.put("Value", tag.value());
                tagsArray.add(tagNode);
            }
        }
        response.set("Tags", tagsArray);

        ObjectNode versionIdsToStages = objectMapper.createObjectNode();
        if (secret.getVersions() != null) {
            for (Map.Entry<String, SecretVersion> entry
                    : secret.getVersions().entrySet()) {
                ArrayNode stagesArray = objectMapper.createArrayNode();
                if (entry.getValue().getVersionStages() != null) {
                    entry.getValue().getVersionStages().forEach(stagesArray::add);
                }
                versionIdsToStages.set(entry.getKey(), stagesArray);
            }
        }
        response.set("VersionIdsToStages", versionIdsToStages);
        return Response.ok(response).build();
    }

    private Response handleListSecrets(JsonNode request, String region) {
        List<Secret> secrets = service.listSecrets(region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode secretList = objectMapper.createArrayNode();
        for (Secret secret : secrets) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("ARN", secret.getArn());
            node.put("Name", secret.getName());
            if (secret.getDescription() != null) {
                node.put("Description", secret.getDescription());
            }
            if (secret.getKmsKeyId() != null) {
                node.put("KmsKeyId", secret.getKmsKeyId());
            }
            node.put("RotationEnabled", secret.isRotationEnabled());
            if (secret.getCreatedDate() != null) {
                node.put("CreatedDate", secret.getCreatedDate().toEpochMilli() / 1000.0);
            }
            if (secret.getLastChangedDate() != null) {
                node.put("LastChangedDate", secret.getLastChangedDate().toEpochMilli() / 1000.0);
            }
            if (secret.getLastAccessedDate() != null) {
                node.put("LastAccessedDate", secret.getLastAccessedDate().toEpochMilli() / 1000.0);
            }
            ArrayNode tagsArray = objectMapper.createArrayNode();
            if (secret.getTags() != null) {
                for (Secret.Tag tag : secret.getTags()) {
                    ObjectNode tagNode = objectMapper.createObjectNode();
                    tagNode.put("Key", tag.key());
                    tagNode.put("Value", tag.value());
                    tagsArray.add(tagNode);
                }
            }
            node.set("Tags", tagsArray);
            secretList.add(node);
        }
        response.set("SecretList", secretList);
        return Response.ok(response).build();
    }

    private Response handleDeleteSecret(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        boolean forceDelete = request.path("ForceDeleteWithoutRecovery").asBoolean(false);
        Integer recoveryWindowInDays = request.has("RecoveryWindowInDays")
                ? request.path("RecoveryWindowInDays").asInt() : null;

        Secret secret = service.deleteSecret(secretId, recoveryWindowInDays, forceDelete, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        if (secret.getDeletedDate() != null) {
            response.put("DeletionDate", secret.getDeletedDate().toEpochMilli() / 1000.0);
        }
        return Response.ok(response).build();
    }

    private Response handleRotateSecret(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        String lambdaArn = request.has("RotationLambdaARN") ? request.path("RotationLambdaARN").asText() : null;
        boolean rotateImmediately = request.path("RotateImmediately").asBoolean(true);

        Map<String, Integer> rules = new HashMap<>();
        JsonNode rulesNode = request.path("RotationRules");
        if (rulesNode.has("AutomaticallyAfterDays")) {
            rules.put("AutomaticallyAfterDays", rulesNode.path("AutomaticallyAfterDays").asInt());
        }

        Secret secret = service.rotateSecret(secretId, lambdaArn, rules, rotateImmediately, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        List<Secret.Tag> tags = parseTags(request);
        service.tagResource(secretId, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        List<String> tagKeys = new ArrayList<>();
        request.path("TagKeys").forEach(k -> tagKeys.add(k.asText()));
        service.untagResource(secretId, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListSecretVersionIds(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        Secret secret = service.describeSecret(secretId, region);
        Map<String, List<String>> versionMap = service.listSecretVersionIds(secretId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());

        ArrayNode versions = objectMapper.createArrayNode();
        for (Map.Entry<String, List<String>> entry : versionMap.entrySet()) {
            ObjectNode versionNode = objectMapper.createObjectNode();
            versionNode.put("VersionId", entry.getKey());
            ArrayNode stagesArray = objectMapper.createArrayNode();
            if (entry.getValue() != null) {
                entry.getValue().forEach(stagesArray::add);
            }
            versionNode.set("VersionStages", stagesArray);
            SecretVersion sv = secret.getVersions() != null ? secret.getVersions().get(entry.getKey()) : null;
            if (sv != null && sv.getCreatedDate() != null) {
                versionNode.put("CreatedDate", sv.getCreatedDate().toEpochMilli() / 1000.0);
            }
            versions.add(versionNode);
        }
        response.set("Versions", versions);
        return Response.ok(response).build();
    }

    private Response handleGetResourcePolicy(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        Secret secret = service.describeSecret(secretId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        return Response.ok(response).build();
    }

    /**
     * Generates a random password.
     * <p>
     * By default uses uppercase and lowercase letters, numbers, and the following special characters:
     * {@code !"#$%&'()*+,-./:;<=>?@[\]^_`{|}~}
     *
     * @param request JSON request body with the following optional fields:
     *   <ul>
     *     <li>{@code PasswordLength} (Long) – Length of the password. Default: 32. Min: 1, Max: 4096.</li>
     *     <li>{@code ExcludeCharacters} (String) – Characters to exclude from the password. Max length: 4096.</li>
     *     <li>{@code ExcludeLowercase} (Boolean) – Exclude lowercase letters.</li>
     *     <li>{@code ExcludeUppercase} (Boolean) – Exclude uppercase letters.</li>
     *     <li>{@code ExcludeNumbers} (Boolean) – Exclude numbers.</li>
     *     <li>{@code ExcludePunctuation} (Boolean) – Exclude punctuation characters.</li>
     *     <li>{@code IncludeSpace} (Boolean) – Include the space character.</li>
     *     <li>{@code RequireEachIncludedType} (Boolean) – Require at least one character from each included type. Default: true.</li>
     *   </ul>
     * @param region AWS region (unused for this operation)
     * @return response containing {@code RandomPassword} string
     * @see <a href="https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_GetRandomPassword.html">AWS Secrets Manager – GetRandomPassword</a>
     */
    private Response handleGetRandomPassword(JsonNode request, String region) {
        try {
            String password = RandomPasswordGenerator.generate(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("RandomPassword", password);
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException", e.getMessage()))
                    .build();
        }
    }

    private Response handleUpdateSecretVersionStage(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        String moveToVersionId = request.path("MoveToVersionId").asText(null);
        String removeFromVersionId = request.path("RemoveFromVersionId").asText(null);
        String versionStage = request.path("VersionStage").asText();

        Secret secret = service.updateSecretVersionStage(secretId,
                moveToVersionId, removeFromVersionId, versionStage, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        return Response.ok(response).build();
    }

    private List<Secret.Tag> parseTags(JsonNode request) {
        List<Secret.Tag> tags = new ArrayList<>();
        JsonNode tagsNode = request.path("Tags");
        if (tagsNode.isArray()) {
            tagsNode.forEach(t -> tags.add(new Secret.Tag(t.path("Key").asText(), t.path("Value").asText())));
        }
        return tags;
    }

}
