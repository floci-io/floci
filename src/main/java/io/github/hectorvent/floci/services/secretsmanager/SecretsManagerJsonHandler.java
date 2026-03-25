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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            case "DeleteResourcePolicy" -> Response.ok(objectMapper.createObjectNode()).build();
            case "PutResourcePolicy" -> Response.ok(objectMapper.createObjectNode()).build();
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
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

        Secret secret = service.describeSecret(secretId, region);
        SecretVersion version = service.putSecretValue(secretId, secretString, secretBinary, region);

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

        if (secretString != null || secretBinary != null) {
            service.putSecretValue(secretId, secretString, secretBinary, region);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
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
            if (secret.getLastChangedDate() != null) {
                node.put("LastChangedDate", secret.getLastChangedDate().toEpochMilli() / 1000.0);
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

        var excludeCharacters = request.get("ExcludeCharacters");
        var excludeLowerCase = request.get("ExcludeLowercase");
        var excludeNumbers = request.get("ExcludeNumbers");
        var excludePunctuation = request.get("ExcludePunctuation");
        var excludeUpperCase = request.get("ExcludeUppercase");
        var includeSpace = request.get("IncludeSpace");
        var passwordLength = request.get("PasswordLength");
        var requireEachIncludedType = request.get("RequireEachIncludedType");

        int length = (passwordLength != null && !passwordLength.isNull()) ? passwordLength.asInt(32) : 32;
        if (length < 1 || length > 4096) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException", "PasswordLength must be between 1 and 4096."))
                    .build();
        }

        StringBuilder charset = new StringBuilder();
        if (excludeLowerCase == null || excludeLowerCase.isNull() || !excludeLowerCase.asBoolean()) {
            charset.append("abcdefghijklmnopqrstuvwxyz");
        }
        if (excludeUpperCase == null || excludeUpperCase.isNull() || !excludeUpperCase.asBoolean()) {
            charset.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        }
        if (excludeNumbers == null || excludeNumbers.isNull() || !excludeNumbers.asBoolean()) {
            charset.append("0123456789");
        }
        if (excludePunctuation == null || excludePunctuation.isNull() || !excludePunctuation.asBoolean()) {
            charset.append("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~");
        }
        if (includeSpace != null && !includeSpace.isNull() && includeSpace.asBoolean()) {
            charset.append(" ");
        }
        if (excludeCharacters != null && !excludeCharacters.isNull()) {
            String excluded = excludeCharacters.asText();
            for (int i = charset.length() - 1; i >= 0; i--) {
                if (excluded.indexOf(charset.charAt(i)) >= 0) {
                    charset.deleteCharAt(i);
                }
            }
        }

        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(length);

        boolean requireEach = requireEachIncludedType == null || requireEachIncludedType.isNull() || requireEachIncludedType.asBoolean();
        if (requireEach) {
            List<String> includedTypes = new ArrayList<>();
            if (excludeLowerCase == null || excludeLowerCase.isNull() || !excludeLowerCase.asBoolean()) {
                includedTypes.add("abcdefghijklmnopqrstuvwxyz");
            }
            if (excludeUpperCase == null || excludeUpperCase.isNull() || !excludeUpperCase.asBoolean()) {
                includedTypes.add("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
            }
            if (excludeNumbers == null || excludeNumbers.isNull() || !excludeNumbers.asBoolean()) {
                includedTypes.add("0123456789");
            }
            if (excludePunctuation == null || excludePunctuation.isNull() || !excludePunctuation.asBoolean()) {
                includedTypes.add("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~");
            }
            if (includeSpace != null && !includeSpace.isNull() && includeSpace.asBoolean()) {
                includedTypes.add(" ");
            }
            if (excludeCharacters != null && !excludeCharacters.isNull()) {
                String excluded = excludeCharacters.asText();
                includedTypes = includedTypes.stream()
                        .map(t -> t.chars().filter(c -> excluded.indexOf(c) < 0)
                                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString())
                        .filter(t -> !t.isEmpty())
                        .collect(java.util.stream.Collectors.toList());
            }
            // Seed one char from each required type
            for (String type : includedTypes) {
                password.append(type.charAt(random.nextInt(type.length())));
            }
        }

        for (int i = password.length(); i < length; i++) {
            password.append(charset.charAt(random.nextInt(charset.length())));
        }

        // Shuffle so required chars aren't always at the start
        for (int i = length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = password.charAt(i);
            password.setCharAt(i, password.charAt(j));
            password.setCharAt(j, tmp);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("RandomPassword", password.toString());
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
