package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class SecretsManagerJsonHandler {

    private static final int MAX_FILTERS = 10;
    private static final int MAX_BATCH_SECRET_IDS = 20;
    private static final int MAX_RESOURCE_POLICY_LENGTH = 20480;
    private static final String SORT_BY_CREATED_DATE = "created-date";
    private static final List<String> SORT_BY_VALUES =
            List.of(SORT_BY_CREATED_DATE, "last-accessed-date", "last-changed-date", "name");

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
            case "RestoreSecret" -> handleRestoreSecret(request, region);
            case "RotateSecret" -> handleRotateSecret(request, region);
            case "CancelRotateSecret" -> handleCancelRotateSecret(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListSecretVersionIds" -> handleListSecretVersionIds(request, region);
            case "GetResourcePolicy" -> handleGetResourcePolicy(request, region);
            case "GetRandomPassword" -> handleGetRandomPassword(request, region);
            case "BatchGetSecretValue" -> handleBatchGetSecretValue(request, region);
            case "DeleteResourcePolicy" -> handleDeleteResourcePolicy(request, region);
            case "PutResourcePolicy" -> handlePutResourcePolicy(request, region);
            case "UpdateSecretVersionStage" -> handleUpdateSecretVersionStage(request, region);
            case "ValidateResourcePolicy" -> handleValidateResourcePolicy(request, region);
            case "ReplicateSecretToRegions" -> handleReplicateSecretToRegions(request, region);
            case "RemoveRegionsFromReplication" -> handleRemoveRegionsFromReplication(request, region);
            case "StopReplicationToReplica" -> handleStopReplicationToReplica(request, region);
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

        if (request.has("SecretIdList") && request.has("Filters")) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException", "You cannot specify both SecretIdList and Filters."))
                    .build();
        }

        List<SecretsManagerService.BatchSecretValue> values;
        List<SecretsManagerService.BatchGetSecretValueError> errors = List.of();
        String nextToken = null;

        if (request.has("SecretIdList")) {
            List<String> secretIdList = new ArrayList<>();
            request.path("SecretIdList").forEach(id -> secretIdList.add(id.asText()));
            if (secretIdList.size() > MAX_BATCH_SECRET_IDS) {
                return Response.status(400)
                        .entity(new AwsErrorResponse("InvalidParameterException",
                                "SecretIdList can contain at most " + MAX_BATCH_SECRET_IDS + " items."))
                        .build();
            }
            SecretsManagerService.BatchGetSecretValueResult result =
                    service.batchGetSecretValue(secretIdList, region);
            values = result.values();
            errors = result.errors();
        } else {
            // Validate paging inputs before the service scans and filters the whole store.
            int maxResults = request.has("MaxResults") ? request.path("MaxResults").asInt() : 20;
            if (maxResults < 1 || maxResults > 20) {
                return Response.status(400)
                        .entity(new AwsErrorResponse("InvalidParameterException", "MaxResults must be between 1 and 20."))
                        .build();
            }

            int startIndex = 0;
            if (request.has("NextToken")) {
                try {
                    startIndex = Integer.parseInt(request.path("NextToken").asText());
                    if (startIndex < 0) {
                        throw new NumberFormatException("negative NextToken");
                    }
                } catch (NumberFormatException e) {
                    return Response.status(400)
                            .entity(new AwsErrorResponse("InvalidNextTokenException", "The NextToken value is invalid."))
                            .build();
                }
            }

            List<SecretsManagerService.Filter> filters = parseFilters(request);
            if (filters.size() > MAX_FILTERS) {
                return Response.status(400)
                        .entity(new AwsErrorResponse("InvalidParameterException",
                                "Filters can contain at most " + MAX_FILTERS + " items."))
                        .build();
            }

            List<SecretsManagerService.BatchSecretValue> allFilteredValues =
                    service.batchGetSecretValueByFilters(filters, region);

            if (startIndex > allFilteredValues.size()) {
                values = List.of();
            } else {
                int endIndex = Math.min(startIndex + maxResults, allFilteredValues.size());
                values = allFilteredValues.subList(startIndex, endIndex);
                if (endIndex < allFilteredValues.size()) {
                    nextToken = String.valueOf(endIndex);
                }
            }
        }

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
            putVersionStages(node, value.versionStages());
            secretValues.add(node);
        }
        response.set("SecretValues", secretValues);

        ArrayNode errorNodes = objectMapper.createArrayNode();
        for (SecretsManagerService.BatchGetSecretValueError error : errors) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("SecretId", error.secretId());
            node.put("ErrorCode", error.errorCode());
            node.put("Message", error.message());
            errorNodes.add(node);
        }
        response.set("Errors", errorNodes);

        if (nextToken != null) {
            response.put("NextToken", nextToken);
        }
        return Response.ok(response).build();
    }

    private Response handleCreateSecret(JsonNode request, String region) {
        String name = request.path("Name").asText();
        SecretsManagerService.validateSecretName(name);
        String secretString = request.has("SecretString") ? request.path("SecretString").asText() : null;
        String secretBinary = request.has("SecretBinary") ? request.path("SecretBinary").asText() : null;
        String description = request.has("Description") ? request.path("Description").asText() : null;
        String kmsKeyId = request.has("KmsKeyId") ? request.path("KmsKeyId").asText() : null;
        String clientRequestToken = request.hasNonNull("ClientRequestToken")
                ? request.path("ClientRequestToken").asText() : null;
        List<Secret.Tag> tags = parseTags(request);

        Secret secret = service.createSecret(name, secretString, secretBinary, description, kmsKeyId,
                tags, null, clientRequestToken, region);

        // AddReplicaRegions is the same work ReplicateSecretToRegions does, so the secret is
        // created first and then replicated, exactly as AWS documents the combined call.
        List<SecretsManagerService.ReplicaRegion> replicaRegions = parseReplicaRegions(request);
        if (!replicaRegions.isEmpty()) {
            secret = service.replicateSecretToRegions(secret.getArn(), replicaRegions,
                    request.path("ForceOverwriteReplicaSecret").asBoolean(false), region);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        response.put("VersionId", secret.getCurrentVersionId());
        if (!replicaRegions.isEmpty()) {
            response.set("ReplicationStatus", replicationStatusNode(secret));
        }
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
        putVersionStages(response, version.getVersionStages());
        return Response.ok(response).build();
    }

    private Response handlePutSecretValue(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        String secretString = request.has("SecretString") ? request.path("SecretString").asText() : null;
        String secretBinary = request.has("SecretBinary") ? request.path("SecretBinary").asText() : null;
        String clientRequestToken = request.has("ClientRequestToken") ? request.path("ClientRequestToken").asText() : null;

        // AWS: "You must include SecretBinary or SecretString, but not both." The service allows
        // neither, because that is how a rotation stages an empty AWSPENDING placeholder
        // internally, so the wire API is where a valueless request has to be refused.
        if (secretString == null && secretBinary == null) {
            throw new AwsException("InvalidParameterException",
                    "You must specify either SecretString or SecretBinary.", 400);
        }

        List<String> versionStages = request.has("VersionStages") && request.path("VersionStages").isArray()
                ? StreamSupport.stream(request.path("VersionStages").spliterator(), false).map(JsonNode::asText).toList()
                : null;

        Secret secret = service.describeSecret(secretId, region);
        SecretVersion version = service.putSecretValue(secretId, secretString, secretBinary, clientRequestToken, region, versionStages);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        response.put("VersionId", version.getVersionId());
        putVersionStages(response, version.getVersionStages());
        return Response.ok(response).build();
    }

    private Response handleUpdateSecret(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        String description = request.has("Description") ? request.path("Description").asText() : null;
        String kmsKeyId = request.has("KmsKeyId") ? request.path("KmsKeyId").asText() : null;
        String secretString = request.has("SecretString") ? request.path("SecretString").asText() : null;
        String secretBinary = request.has("SecretBinary") ? request.path("SecretBinary").asText() : null;
        if (secretString != null && secretBinary != null) {
            throw new AwsException("InvalidParameterException",
                    "You can't specify both SecretString and SecretBinary in the same request.", 400);
        }

        Secret existing = service.describeSecret(secretId, region);
        SecretsManagerService.throwIfServiceManaged(existing);

        // UpdateSecret, unlike PutSecretValue and CreateSecret, is NOT idempotent: AWS errors
        // when the token names a version that already exists, because an existing version can
        // never be modified. PutSecretValue below would happily treat it as a no-op retry.
        String clientRequestToken = request.hasNonNull("ClientRequestToken")
                ? request.path("ClientRequestToken").asText() : null;
        if (clientRequestToken != null && existing.getVersions() != null
                && existing.getVersions().containsKey(clientRequestToken)) {
            throw new AwsException("ResourceExistsException",
                    "The ClientRequestToken " + clientRequestToken
                            + " already names a version of this secret. You can't modify an existing "
                            + "version, only create a new one.", 400);
        }

        Secret secret = service.updateSecret(secretId, description, kmsKeyId, region);

        String versionId = null;
        if (secretString != null || secretBinary != null) {
            SecretVersion version = service.putSecretValue(secretId, secretString, secretBinary,
                    clientRequestToken != null ? clientRequestToken : UUID.randomUUID().toString(),
                    region, null);
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
        if (secret.getRotationLambdaArn() != null) {
            response.put("RotationLambdaARN", secret.getRotationLambdaArn());
        }
        if (secret.getRotationRules() != null) {
            response.set("RotationRules", rotationRulesNode(secret.getRotationRules()));
        }
        if (secret.getLastRotatedDate() != null) {
            response.put("LastRotatedDate", secret.getLastRotatedDate().toEpochMilli() / 1000.0);
        }

        Instant nextRotationDate = nextRotationDate(secret);
        if (nextRotationDate != null) {
            response.put("NextRotationDate", nextRotationDate.toEpochMilli() / 1000.0);
        }
        if (secret.getCreatedDate() != null) {
            response.put("CreatedDate", secret.getCreatedDate().toEpochMilli() / 1000.0);
        }
        if (secret.getLastChangedDate() != null) {
            response.put("LastChangedDate", secret.getLastChangedDate().toEpochMilli() / 1000.0);
        }
        if (secret.getDeletedDate() != null) {
            response.put("DeletedDate", secret.getDeletedDate().toEpochMilli() / 1000.0);
        }
        if (secret.getOwningService() != null) {
            response.put("OwningService", secret.getOwningService());
        }

        // PrimaryRegion is a multi-region-only field: AWS returns only fields "that have a
        // value", and a standalone secret carries none. A replica points back at its primary's
        // region; a primary that has replicas reports its own.
        if (secret.isReplica()) {
            response.put("PrimaryRegion", secret.getPrimaryRegion());
        } else if (isReplicated(secret)) {
            response.put("PrimaryRegion", region);
            response.set("ReplicationStatus", replicationStatusNode(secret));
        }
        response.set("Tags", tagsNode(secret));
        response.set("VersionIdsToStages", versionStagesNode(secret));
        return Response.ok(response).build();
    }

    private static Comparator<Secret> sortComparator(String sortBy) {
        Comparator<Secret> comparator = switch (sortBy) {
            case "name" -> Comparator.comparing(Secret::getName, Comparator.nullsLast(Comparator.naturalOrder()));
            case "last-accessed-date" -> Comparator.comparing(Secret::getLastAccessedDate,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "last-changed-date" -> Comparator.comparing(Secret::getLastChangedDate,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(Secret::getCreatedDate,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        return comparator.thenComparing(Secret::getName, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * The rotation date AWS reports: the one explicitly stored, otherwise the one implied by
     * {@code AutomaticallyAfterDays}. Null when rotation is off, which is what AWS returns.
     */
    private static Instant nextRotationDate(Secret secret) {
        if (secret.getNextRotationDate() != null) {
            return secret.getNextRotationDate();
        }
        if (!secret.isRotationEnabled() || secret.getRotationRules() == null
                || secret.getRotationRules().automaticallyAfterDays() == null) {
            return null;
        }
        Instant lastRotated = secret.getLastRotatedDate() != null
                ? secret.getLastRotatedDate() : secret.getCreatedDate();
        return lastRotated == null ? null
                : lastRotated.plusSeconds((long) secret.getRotationRules().automaticallyAfterDays() * 86400);
    }

    private ObjectNode rotationRulesNode(Secret.RotationRules rules) {
        ObjectNode node = objectMapper.createObjectNode();
        if (rules.automaticallyAfterDays() != null) {
            node.put("AutomaticallyAfterDays", rules.automaticallyAfterDays());
        }
        if (rules.duration() != null) {
            node.put("Duration", rules.duration());
        }
        if (rules.scheduleExpression() != null) {
            node.put("ScheduleExpression", rules.scheduleExpression());
        }
        return node;
    }

    private ArrayNode tagsNode(Secret secret) {
        ArrayNode tagsArray = objectMapper.createArrayNode();
        if (secret.getTags() != null) {
            for (Secret.Tag tag : secret.getTags()) {
                ObjectNode tagNode = objectMapper.createObjectNode();
                tagNode.put("Key", tag.key());
                tagNode.put("Value", tag.value());
                tagsArray.add(tagNode);
            }
        }
        return tagsArray;
    }

    /**
     * The version-id to staging-label map. DescribeSecret calls it {@code VersionIdsToStages} and
     * ListSecrets calls it {@code SecretVersionsToStages}; the shape is identical.
     */
    private ObjectNode versionStagesNode(Secret secret) {
        ObjectNode versionIdsToStages = objectMapper.createObjectNode();
        if (secret.getVersions() != null) {
            for (Map.Entry<String, SecretVersion> entry : secret.getVersions().entrySet()) {
                List<String> stages = entry.getValue().getVersionStages();
                // A version with every staging label stripped is deprecated - AWS lists only
                // versions "that have staging labels attached" and never an empty array.
                if (stages == null || stages.isEmpty()) {
                    continue;
                }
                ArrayNode stagesArray = objectMapper.createArrayNode();
                stages.forEach(stagesArray::add);
                versionIdsToStages.set(entry.getKey(), stagesArray);
            }
        }
        return versionIdsToStages;
    }

    /** Parses the request's {@code Filters} array, shared by BatchGetSecretValue and ListSecrets. */
    private List<SecretsManagerService.Filter> parseFilters(JsonNode request) {
        List<SecretsManagerService.Filter> filters = new ArrayList<>();
        JsonNode filtersNode = request.path("Filters");
        if (filtersNode.isArray()) {
            for (JsonNode f : filtersNode) {
                String key = f.path("Key").asText();
                List<String> filterValues = new ArrayList<>();
                f.path("Values").forEach(v -> filterValues.add(v.asText()));
                filters.add(new SecretsManagerService.Filter(key, filterValues));
            }
        }
        return filters;
    }

    private Response handleListSecrets(JsonNode request, String region) {
        List<SecretsManagerService.Filter> filters = parseFilters(request);
        if (filters.size() > MAX_FILTERS) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException",
                            "Filters can contain at most " + MAX_FILTERS + " items."))
                    .build();
        }

        String sortBy = request.hasNonNull("SortBy") ? request.path("SortBy").asText() : SORT_BY_CREATED_DATE;
        if (!SORT_BY_VALUES.contains(sortBy)) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException",
                            "Invalid SortBy value, must be one of " + String.join(" | ", SORT_BY_VALUES)))
                    .build();
        }

        String sortOrder = request.hasNonNull("SortOrder") ? request.path("SortOrder").asText() : "asc";
        if (!"asc".equals(sortOrder) && !"desc".equals(sortOrder)) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException",
                            "Invalid SortOrder value, must be asc or desc"))
                    .build();
        }

        boolean includePlannedDeletion = request.path("IncludePlannedDeletion").asBoolean(false);
        List<Secret> secrets = new ArrayList<>(service.listSecrets(region, filters, includePlannedDeletion));

        // AWS lists secrets by CreatedDate when SortBy is absent. Whatever the key, name breaks
        // ties so offset-based pagination stays stable across calls.
        Comparator<Secret> comparator = sortComparator(sortBy);
        secrets.sort("desc".equals(sortOrder) ? comparator.reversed() : comparator);

        // MaxResults is constrained to 1-100; when absent the full result set is returned.
        int maxResults = secrets.size();
        if (request.hasNonNull("MaxResults")) {
            maxResults = request.path("MaxResults").asInt();
            if (maxResults < 1 || maxResults > 100) {
                return Response.status(400)
                        .entity(new AwsErrorResponse("InvalidParameterException",
                                "Invalid MaxResults value, must be between 1 and 100"))
                        .build();
            }
        }

        // NextToken is an opaque offset into the sorted secret list.
        int offset = 0;
        if (request.hasNonNull("NextToken")) {
            try {
                offset = Integer.parseInt(request.path("NextToken").asText());
                if (offset < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                return Response.status(400)
                        .entity(new AwsErrorResponse("InvalidNextTokenException", "Invalid NextToken"))
                        .build();
            }
        }
        offset = Math.min(offset, secrets.size());
        int end = Math.min(secrets.size(), offset + maxResults);
        List<Secret> page = secrets.subList(offset, end);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode secretList = objectMapper.createArrayNode();
        for (Secret secret : page) {
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
            if (secret.getRotationLambdaArn() != null) {
                node.put("RotationLambdaARN", secret.getRotationLambdaArn());
            }
            if (secret.getRotationRules() != null) {
                node.set("RotationRules", rotationRulesNode(secret.getRotationRules()));
            }
            if (secret.getLastRotatedDate() != null) {
                node.put("LastRotatedDate", secret.getLastRotatedDate().toEpochMilli() / 1000.0);
            }
            Instant nextRotation = nextRotationDate(secret);
            if (nextRotation != null) {
                node.put("NextRotationDate", nextRotation.toEpochMilli() / 1000.0);
            }
            if (secret.getCreatedDate() != null) {
                node.put("CreatedDate", secret.getCreatedDate().toEpochMilli() / 1000.0);
            }
            if (secret.getLastChangedDate() != null) {
                node.put("LastChangedDate", secret.getLastChangedDate().toEpochMilli() / 1000.0);
            }
            if (secret.getLastAccessedDate() != null) {
                node.put("LastAccessedDate", secret.getLastAccessedDate().toEpochMilli() / 1000.0);
            }
            if (secret.getDeletedDate() != null) {
                node.put("DeletedDate", secret.getDeletedDate().toEpochMilli() / 1000.0);
            }
            if (secret.getOwningService() != null) {
                node.put("OwningService", secret.getOwningService());
            }
            if (secret.isReplica()) {
                node.put("PrimaryRegion", secret.getPrimaryRegion());
            } else if (isReplicated(secret)) {
                node.put("PrimaryRegion", region);
                node.set("ReplicationStatus", replicationStatusNode(secret));
            }
            node.set("Tags", tagsNode(secret));
            node.set("SecretVersionsToStages", versionStagesNode(secret));
            secretList.add(node);
        }
        response.set("SecretList", secretList);
        if (end < secrets.size()) {
            response.put("NextToken", String.valueOf(end));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteSecret(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        boolean forceDelete = request.path("ForceDeleteWithoutRecovery").asBoolean(false);
        Integer recoveryWindowInDays = request.hasNonNull("RecoveryWindowInDays")
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

    private Response handleRestoreSecret(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        Secret secret = service.restoreSecret(secretId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        return Response.ok(response).build();
    }

    private Response handleRotateSecret(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        String clientRequestToken = request.has("ClientRequestToken") ? request.path("ClientRequestToken").asText() : UUID.randomUUID().toString();
        
        String lambdaArn = request.has("RotationLambdaARN") ? request.path("RotationLambdaARN").asText() : null;
                          
        boolean rotateImmediately = true;
        if (request.has("RotateImmediately")) {
            rotateImmediately = request.path("RotateImmediately").asBoolean();
        }

        Secret.RotationRules rotationRules = null;
        JsonNode rulesNode = request.has("RotationRules") ? request.path("RotationRules") : null;
        
        if (rulesNode != null && !rulesNode.isNull()) {
            Integer automaticallyAfterDays = null;
            if (rulesNode.hasNonNull("AutomaticallyAfterDays")) {
                automaticallyAfterDays = rulesNode.path("AutomaticallyAfterDays").asInt();
            }
            
            String duration = rulesNode.hasNonNull("Duration") ? rulesNode.path("Duration").asText() : null;
            String scheduleExpression = rulesNode.hasNonNull("ScheduleExpression") ? rulesNode.path("ScheduleExpression").asText() : null;
            rotationRules = new Secret.RotationRules(automaticallyAfterDays, duration, scheduleExpression);
        }

        Secret secret = service.rotateSecret(secretId, clientRequestToken, lambdaArn, rotationRules, rotateImmediately, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        // A service-managed secret is rotated in place by its owning service, staging no version
        // for the request token, so report the version that exists rather than one that would
        // resolve to nothing.
        boolean serviceManaged = secret.getOwningService() != null && secret.getCurrentVersionId() != null;
        response.put("VersionId", serviceManaged ? secret.getCurrentVersionId() : clientRequestToken);
        return Response.ok(response).build();
    }

    private Response handleCancelRotateSecret(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        // A service-managed secret is rotated by its owning service, so rotation can't be turned
        // off from outside - the same guard RotateSecret applies.
        SecretsManagerService.throwIfServiceManaged(service.describeSecret(secretId, region));
        SecretsManagerService.CancelRotationResult result = service.cancelRotateSecret(secretId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", result.secret().getArn());
        response.put("Name", result.secret().getName());
        // AWS omits VersionId when no rotation was in flight, as in its own example response.
        if (result.pendingVersionId() != null) {
            response.put("VersionId", result.pendingVersionId());
        }
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        // Tags is a required parameter; an absent list used to parse to empty and no-op.
        if (!request.path("Tags").isArray() || request.path("Tags").isEmpty()) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException",
                            "Tags must contain at least one tag."))
                    .build();
        }
        List<Secret.Tag> tags = parseTags(request);
        service.tagResource(secretId, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        // TagKeys is a required parameter; an absent list used to parse to empty and no-op.
        if (!request.path("TagKeys").isArray() || request.path("TagKeys").isEmpty()) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException",
                            "TagKeys must contain at least one key."))
                    .build();
        }
        List<String> tagKeys = new ArrayList<>();
        request.path("TagKeys").forEach(k -> tagKeys.add(k.asText()));
        service.untagResource(secretId, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListSecretVersionIds(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        // A version with no staging labels is deprecated and AWS hides it unless asked.
        boolean includeDeprecated = request.path("IncludeDeprecated").asBoolean(false);

        int maxResults = 100;
        if (request.hasNonNull("MaxResults")) {
            maxResults = request.path("MaxResults").asInt();
            if (maxResults < 1 || maxResults > 100) {
                return Response.status(400)
                        .entity(new AwsErrorResponse("InvalidParameterException",
                                "Invalid MaxResults value, must be between 1 and 100"))
                        .build();
            }
        }

        int offset = 0;
        if (request.hasNonNull("NextToken")) {
            try {
                offset = Integer.parseInt(request.path("NextToken").asText());
                if (offset < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                return Response.status(400)
                        .entity(new AwsErrorResponse("InvalidNextTokenException", "Invalid NextToken"))
                        .build();
            }
        }

        Secret secret = service.describeSecret(secretId, region);
        Map<String, List<String>> versionMap = service.listSecretVersionIds(secretId, region);

        // Sorted by creation so offset-based pagination is stable across calls.
        List<String> versionIds = new ArrayList<>(versionMap.keySet());
        versionIds.removeIf(id -> {
            List<String> stages = versionMap.get(id);
            return !includeDeprecated && (stages == null || stages.isEmpty());
        });
        versionIds.sort(Comparator.comparing(
                id -> versionCreatedDate(secret, id), Comparator.nullsLast(Comparator.naturalOrder())));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());

        offset = Math.min(offset, versionIds.size());
        int end = Math.min(versionIds.size(), offset + maxResults);

        ArrayNode versions = objectMapper.createArrayNode();
        for (String versionId : versionIds.subList(offset, end)) {
            ObjectNode versionNode = objectMapper.createObjectNode();
            versionNode.put("VersionId", versionId);
            List<String> stages = versionMap.get(versionId);
            // AWS's own example omits VersionStages entirely for a deprecated version rather
            // than returning an empty array.
            if (stages != null && !stages.isEmpty()) {
                ArrayNode stagesArray = objectMapper.createArrayNode();
                stages.forEach(stagesArray::add);
                versionNode.set("VersionStages", stagesArray);
            }
            SecretVersion sv = secret.getVersions() != null ? secret.getVersions().get(versionId) : null;
            if (sv != null && sv.getCreatedDate() != null) {
                versionNode.put("CreatedDate", sv.getCreatedDate().toEpochMilli() / 1000.0);
            }
            if (sv != null && sv.getLastAccessedDate() != null) {
                versionNode.put("LastAccessedDate", sv.getLastAccessedDate().toEpochMilli() / 1000.0);
            }
            versions.add(versionNode);
        }
        response.set("Versions", versions);
        if (end < versionIds.size()) {
            response.put("NextToken", String.valueOf(end));
        }
        return Response.ok(response).build();
    }

    private static Instant versionCreatedDate(Secret secret, String versionId) {
        SecretVersion version = secret.getVersions() != null ? secret.getVersions().get(versionId) : null;
        return version != null ? version.getCreatedDate() : null;
    }

    private Response handleGetResourcePolicy(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        Secret secret = service.getResourcePolicy(secretId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        // AWS omits ResourcePolicy entirely when no policy is attached; the terraform provider
        // reads the absent field as "no policy" rather than as an error.
        if (secret.getResourcePolicy() != null) {
            response.put("ResourcePolicy", secret.getResourcePolicy());
        }
        return Response.ok(response).build();
    }

    private Response handlePutResourcePolicy(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        String resourcePolicy = request.hasNonNull("ResourcePolicy") ? request.get("ResourcePolicy").asText() : null;
        if (resourcePolicy == null || resourcePolicy.isBlank()) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException",
                            "Invalid parameter: ResourcePolicy must not be null or empty."))
                    .build();
        }
        if (resourcePolicy.length() > MAX_RESOURCE_POLICY_LENGTH) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException",
                            "ResourcePolicy must be at most " + MAX_RESOURCE_POLICY_LENGTH + " characters."))
                    .build();
        }

        JsonNode parsedPolicy;
        try {
            parsedPolicy = objectMapper.readTree(resourcePolicy);
        } catch (JsonProcessingException e) {
            parsedPolicy = null;
        }
        if (parsedPolicy == null || !parsedPolicy.isObject()) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("MalformedPolicyDocumentException",
                            "The resource policy is not a valid JSON policy document."))
                    .build();
        }

        // BlockPublicPolicy refuses a policy that hands the secret to everyone. AWS leaves it off
        // by default, so an unguarded call still accepts a wildcard principal.
        if (request.path("BlockPublicPolicy").asBoolean(false)
                && !ResourcePolicyValidator.validate(parsedPolicy).isEmpty()) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("PublicPolicyException",
                            "The BlockPublicPolicy parameter is set to true, and the resource policy "
                                    + "did not prevent broad access to the secret."))
                    .build();
        }

        Secret secret = service.putResourcePolicy(secretId, resourcePolicy, region);
        ObjectNode response = objectMapper.createObjectNode();
        // The terraform provider uses the returned ARN as the aws_secretsmanager_secret_policy
        // resource id, so an empty response breaks its create outright.
        response.put("ARN", secret.getArn());
        response.put("Name", secret.getName());
        return Response.ok(response).build();
    }

    /** Parses an {@code AddReplicaRegions} array into replication targets. */
    private List<SecretsManagerService.ReplicaRegion> parseReplicaRegions(JsonNode request) {
        List<SecretsManagerService.ReplicaRegion> targets = new ArrayList<>();
        JsonNode node = request.path("AddReplicaRegions");
        if (node.isArray()) {
            for (JsonNode entry : node) {
                targets.add(new SecretsManagerService.ReplicaRegion(
                        entry.path("Region").asText(null),
                        entry.hasNonNull("KmsKeyId") ? entry.path("KmsKeyId").asText() : null));
            }
        }
        return targets;
    }

    /**
     * Attaches {@code VersionStages} only when the version actually carries labels. AWS models
     * the array with a one-item minimum, so a deprecated version omits the field rather than
     * sending an empty array.
     */
    private void putVersionStages(ObjectNode node, List<String> stages) {
        if (stages == null || stages.isEmpty()) {
            return;
        }
        ArrayNode array = objectMapper.createArrayNode();
        stages.forEach(array::add);
        node.set("VersionStages", array);
    }

    /** True when this primary has at least one replica. */
    private static boolean isReplicated(Secret secret) {
        return secret.getReplicationStatus() != null && !secret.getReplicationStatus().isEmpty();
    }

    private ArrayNode replicationStatusNode(Secret secret) {
        ArrayNode statuses = objectMapper.createArrayNode();
        if (secret.getReplicationStatus() == null) {
            return statuses;
        }
        for (Secret.ReplicaStatus status : secret.getReplicationStatus()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Region", status.region());
            if (status.kmsKeyId() != null) {
                node.put("KmsKeyId", status.kmsKeyId());
            }
            node.put("Status", status.status());
            if (status.statusMessage() != null) {
                node.put("StatusMessage", status.statusMessage());
            }
            if (status.lastAccessedDate() != null) {
                node.put("LastAccessedDate", status.lastAccessedDate().toEpochMilli() / 1000.0);
            }
            statuses.add(node);
        }
        return statuses;
    }

    private Response handleReplicateSecretToRegions(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        boolean force = request.path("ForceOverwriteReplicaSecret").asBoolean(false);
        Secret primary = service.replicateSecretToRegions(
                secretId, parseReplicaRegions(request), force, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", primary.getArn());
        response.set("ReplicationStatus", replicationStatusNode(primary));
        return Response.ok(response).build();
    }

    private Response handleRemoveRegionsFromReplication(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        List<String> regionsToRemove = new ArrayList<>();
        request.path("RemoveReplicaRegions").forEach(r -> regionsToRemove.add(r.asText()));

        Secret primary = service.removeRegionsFromReplication(secretId, regionsToRemove, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", primary.getArn());
        response.set("ReplicationStatus", replicationStatusNode(primary));
        return Response.ok(response).build();
    }

    private Response handleStopReplicationToReplica(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        Secret promoted = service.stopReplicationToReplica(secretId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ARN", promoted.getArn());
        return Response.ok(response).build();
    }

    private Response handleValidateResourcePolicy(JsonNode request, String region) {
        String resourcePolicy = request.hasNonNull("ResourcePolicy")
                ? request.get("ResourcePolicy").asText() : null;
        if (resourcePolicy == null || resourcePolicy.isBlank()) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("InvalidParameterException",
                            "Invalid parameter: ResourcePolicy must not be null or empty."))
                    .build();
        }

        // SecretId is optional on this operation, but a caller that names a secret expects the
        // usual not-found handling rather than a silent pass.
        if (request.hasNonNull("SecretId")) {
            service.describeSecret(request.path("SecretId").asText(), region);
        }

        JsonNode parsedPolicy;
        try {
            parsedPolicy = objectMapper.readTree(resourcePolicy);
        } catch (JsonProcessingException e) {
            parsedPolicy = null;
        }
        if (parsedPolicy == null || !parsedPolicy.isObject()) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("MalformedPolicyDocumentException",
                            "The resource policy is not a valid JSON policy document."))
                    .build();
        }

        List<ResourcePolicyValidator.ValidationError> errors =
                ResourcePolicyValidator.validate(parsedPolicy);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("PolicyValidationPassed", errors.isEmpty());
        ArrayNode errorNodes = objectMapper.createArrayNode();
        for (ResourcePolicyValidator.ValidationError error : errors) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("CheckName", error.checkName());
            node.put("ErrorMessage", error.errorMessage());
            errorNodes.add(node);
        }
        response.set("ValidationErrors", errorNodes);
        return Response.ok(response).build();
    }

    private Response handleDeleteResourcePolicy(JsonNode request, String region) {
        String secretId = request.path("SecretId").asText();
        Secret secret = service.deleteResourcePolicy(secretId, region);
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
