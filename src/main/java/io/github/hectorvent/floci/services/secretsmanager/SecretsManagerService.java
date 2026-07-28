package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class SecretsManagerService {

    private static final Logger LOG = Logger.getLogger(SecretsManagerService.class);

    private static final String AWSCURRENT = "AWSCURRENT";
    private static final String AWSPREVIOUS = "AWSPREVIOUS";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final StorageBackend<String, Secret> store;
    private final int defaultRecoveryWindowDays;
    private final RegionResolver regionResolver;
    private final LambdaService lambdaService;
    private final ObjectMapper objectMapper;
    private final ExecutorService rotationExecutor = Executors.newCachedThreadPool();
    private final java.util.concurrent.ConcurrentHashMap<String, Object> rotationLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private Object lockFor(String secretArn) {
        return rotationLocks.computeIfAbsent(secretArn, k -> new Object());
    }

    @Inject
    public SecretsManagerService(StorageFactory factory, EmulatorConfig config, RegionResolver regionResolver,
                                 LambdaService lambdaService, ObjectMapper objectMapper) {
        this(factory.create("secretsmanager", "secretsmanager-secrets.json",
                        new TypeReference<Map<String, Secret>>() {}),
                config.services().secretsmanager().defaultRecoveryWindowDays(),
                regionResolver, lambdaService, objectMapper);
    }

    SecretsManagerService(StorageBackend<String, Secret> store, int defaultRecoveryWindowDays) {
        this(store, defaultRecoveryWindowDays, new RegionResolver("us-east-1", "000000000000"), null, new ObjectMapper());
    }

    SecretsManagerService(StorageBackend<String, Secret> store, int defaultRecoveryWindowDays,
                          RegionResolver regionResolver, LambdaService lambdaService, ObjectMapper objectMapper) {
        this.store = store;
        this.defaultRecoveryWindowDays = defaultRecoveryWindowDays;
        this.regionResolver = regionResolver;
        this.lambdaService = lambdaService;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        rotationExecutor.shutdown();
    }

    public Secret createSecret(String name, String secretString, String secretBinary,
                               String description, String kmsKeyId, List<Secret.Tag> tags, String region) {
        String storageKey = regionKey(region, name);
        Secret existing = store.get(storageKey).orElse(null);

        if (existing != null && existing.getDeletedDate() == null) {
            throw new AwsException("ResourceExistsException",
                    "A secret with the name " + name + " already exists.", 400);
        }

        String arn = buildSecretArn(region, name);
        Instant now = Instant.now();

        String versionId = UUID.randomUUID().toString();
        SecretVersion version = new SecretVersion();
        version.setVersionId(versionId);
        version.setSecretString(secretString);
        version.setSecretBinary(secretBinary);
        version.setVersionStages(List.of(AWSCURRENT));
        version.setCreatedDate(now);

        Map<String, SecretVersion> versions = new HashMap<>();
        versions.put(versionId, version);

        Secret secret = new Secret();
        secret.setName(name);
        secret.setArn(arn);
        secret.setDescription(description);
        secret.setKmsKeyId(kmsKeyId);
        secret.setRotationEnabled(false);
        secret.setCreatedDate(now);
        secret.setLastChangedDate(now);
        secret.setTags(tags != null ? new ArrayList<>(tags) : new ArrayList<>());
        secret.setVersions(versions);
        secret.setCurrentVersionId(versionId);

        store.put(storageKey, secret);
        LOG.infov("Created secret: {0} in region {1}", name, region);
        return secret;
    }

    public SecretVersion getSecretValue(String secretId, String versionId, String versionStage, String region) {
        Secret secret = resolveSecret(secretId, region);

        if (secret.getDeletedDate() != null) {
            throw new AwsException("ResourceNotFoundException",
                    "Secrets Manager can't find the specified secret.", 400);
        }

        SecretVersion version;
        if (versionId != null && !versionId.isEmpty()) {
            version = secret.getVersions().get(versionId);
            if (version == null) {
                throw new AwsException("ResourceNotFoundException",
                        "Secrets Manager can't find the specified secret version.", 400);
            }
        } else {
            String stage = (versionStage != null && !versionStage.isEmpty()) ? versionStage : AWSCURRENT;
            version = findVersionByStage(secret, stage);
            if (version == null) {
                throw new AwsException("ResourceNotFoundException",
                        "Secrets Manager can't find the specified secret value for staging label: " + stage, 400);
            }
        }

        version.setLastAccessedDate(Instant.now());
        secret.setLastAccessedDate(Instant.now());
        store.put(regionKey(region, secret.getName()), secret);

        return version;
    }

    public SecretVersion putSecretValue(String secretId, String secretString,
                                        String secretBinary, String clientRequestToken, String region,
                                        List<String> versionStages) {
        Secret secret = resolveSecret(secretId, region);

        if (secret.getDeletedDate() != null) {
            throw new AwsException("ResourceNotFoundException",
                    "Secrets Manager can't find the specified secret.", 400);
        }

        if (clientRequestToken != null && (clientRequestToken.length() < 32 || clientRequestToken.length() > 64)) {
            throw new AwsException("InvalidParameterException", "ClientRequestToken must be between 32 and 64 characters long.", 400);
        }

        if (clientRequestToken != null && secret.getVersions() != null && secret.getVersions().containsKey(clientRequestToken)) {
            SecretVersion existingVersion = secret.getVersions().get(clientRequestToken);
            boolean isPendingPlaceholder = existingVersion.getSecretString() == null
                    && existingVersion.getSecretBinary() == null
                    && existingVersion.getVersionStages() != null
                    && existingVersion.getVersionStages().contains("AWSPENDING");
            if (!isPendingPlaceholder) {
                if (!Objects.equals(existingVersion.getSecretString(), secretString) ||
                    !Objects.equals(existingVersion.getSecretBinary(), secretBinary)) {
                    throw new AwsException("ResourceExistsException",
                        "You can't use ClientRequestToken " + clientRequestToken
                            + " because that value is already in use for a version of secret " + secret.getArn(), 400);
                }
                return existingVersion;
            }
        }

        Instant now = Instant.now();
        String newVersionId = clientRequestToken != null ? clientRequestToken : UUID.randomUUID().toString();

        List<String> stages;
        if (versionStages != null) {
            if (versionStages.isEmpty() || versionStages.size() > 20) {
                throw new AwsException("ValidationException", "Invalid length for parameter VersionStages", 400);
            }
            if (versionStages.stream()
                    .anyMatch(stage -> stage == null
                            || stage.isEmpty()
                            || stage.length() > 256)) {
                throw new AwsException("ValidationException", "Member must have length less than or equal to 256, Member must have length greater than or equal to 1", 400);
            }
            stages = versionStages;
        } else {
            stages = List.of(AWSCURRENT);
        }

        SecretVersion previousCurrent = stages.contains(AWSCURRENT) ? findVersionByStage(secret, AWSCURRENT) : null;

        for (String stage : stages) {
            SecretVersion version = findVersionByStage(secret, stage);
            if (version == null) {
                continue;
            }
            List<String> newStages = new ArrayList<>(version.getVersionStages());
            // if stage is AWSCURRENT, the previous AWSCURRENT will become
            // AWSPREVIOUS, and the previous AWSPREVIOUS will drop that stage
            // name
            if (stage.equals(AWSCURRENT)) {
                SecretVersion previous = findVersionByStage(secret, AWSPREVIOUS);
                if (previous != null) {
                    List<String> oldPrevious = new ArrayList<>(previous.getVersionStages());
                    oldPrevious.remove(AWSPREVIOUS);
                    previous.setVersionStages(oldPrevious);
                }
                newStages.add(AWSPREVIOUS);
            }
            newStages.remove(stage);

            version.setVersionStages(newStages);
        }

        if (previousCurrent != null) {
            for (SecretVersion version : secret.getVersions().values()) {
                List<String> assignedStages = version.getVersionStages();
                if (assignedStages == null || !assignedStages.contains(AWSPREVIOUS)) {
                    continue;
                }
                List<String> newStages = new ArrayList<>(assignedStages);
                newStages.removeIf(AWSPREVIOUS::equals);
                version.setVersionStages(newStages);
            }

            List<String> newStages = new ArrayList<>(previousCurrent.getVersionStages());
            newStages.add(AWSPREVIOUS);
            previousCurrent.setVersionStages(newStages);
        }

        SecretVersion newVersion = new SecretVersion();
        newVersion.setVersionId(newVersionId);
        newVersion.setSecretString(secretString);
        newVersion.setSecretBinary(secretBinary);
        newVersion.setVersionStages(stages);
        newVersion.setCreatedDate(now);

        secret.getVersions().put(newVersionId, newVersion);
        if (stages.contains(AWSCURRENT)) {
            secret.setCurrentVersionId(newVersionId);
        }
        secret.setLastChangedDate(now);

        store.put(regionKey(region, secret.getName()), secret);
        LOG.infov("Put secret value for: {0}", secret.getName());
        return newVersion;
    }

    public Secret updateSecret(String secretId, String description, String kmsKeyId, String region) {
        Secret secret = resolveSecret(secretId, region);

        if (secret.getDeletedDate() != null) {
            throw new AwsException("ResourceNotFoundException",
                    "Secrets Manager can't find the specified secret.", 400);
        }

        if (description != null) {
            secret.setDescription(description);
        }
        if (kmsKeyId != null) {
            secret.setKmsKeyId(kmsKeyId);
        }
        secret.setLastChangedDate(Instant.now());

        store.put(regionKey(region, secret.getName()), secret);
        LOG.infov("Updated secret metadata: {0}", secret.getName());
        return secret;
    }

    public Secret describeSecret(String secretId, String region) {
        Secret secret = resolveSecret(secretId, region);
        return secret;
    }

    public List<Secret> listSecrets(String region) {
        String prefix = region + "::";
        return store.scan(key -> key.startsWith(prefix) && store.get(key)
                .map(s -> s.getDeletedDate() == null)
                .orElse(false));
    }

    public List<Secret> listSecrets(String region, List<Filter> filters) {
        List<Secret> allSecrets = listSecrets(region);
        if (filters == null || filters.isEmpty()) {
            return allSecrets;
        }
        List<Secret> result = new ArrayList<>();
        for (Secret secret : allSecrets) {
            if (matchesFilters(secret, filters, region)) {
                result.add(secret);
            }
        }
        return result;
    }

    public List<BatchSecretValue> batchGetSecretValueByFilters(List<Filter> filters, String region) {
        List<BatchSecretValue> result = new ArrayList<>();
        List<Secret> allSecrets = listSecrets(region);
        for (Secret secret : allSecrets) {
            if (matchesFilters(secret, filters, region)) {
                SecretVersion version = findVersionByStage(secret, AWSCURRENT);
                if (version != null) {
                    result.add(new BatchSecretValue(
                            secret.getArn(),
                            secret.getName(),
                            version.getSecretString(),
                            version.getSecretBinary(),
                            version.getVersionId(),
                            version.getVersionStages(),
                            version.getCreatedDate()
                    ));
                }
            }
        }
        // Stable order (created date, then name) so offset-based pagination in the handler
        // never skips or duplicates entries across calls — same contract as ListSecrets.
        result.sort(Comparator.comparing(BatchSecretValue::createdDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(BatchSecretValue::name, Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    private boolean matchesFilters(Secret secret, List<Filter> filters, String region) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Filter filter : filters) {
            if (!matchesFilter(secret, filter, region)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesFilter(Secret secret, Filter filter, String region) {
        String key = filter.key();
        List<String> values = filter.values();
        if (values == null || values.isEmpty()) {
            return true;
        }

        for (String val : values) {
            if (matchesValue(secret, key, val, region)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesValue(Secret secret, String key, String filterVal, String region) {
        boolean negate = filterVal.startsWith("!");
        String targetVal = negate ? filterVal.substring(1) : filterVal;

        boolean matched = matchesTarget(secret, key, targetVal, region);
        return negate ? !matched : matched;
    }

    private boolean matchesTarget(Secret secret, String key, String targetVal, String region) {
        return switch (key) {
            case "name" -> secret.getName() != null && secret.getName().startsWith(targetVal);
            case "description" -> secret.getDescription() != null && secret.getDescription().toLowerCase().startsWith(targetVal.toLowerCase());
            case "tag-key" -> secret.getTags() != null && secret.getTags().stream().anyMatch(t -> t.key() != null && t.key().startsWith(targetVal));
            case "tag-value" -> secret.getTags() != null && secret.getTags().stream().anyMatch(t -> t.value() != null && t.value().startsWith(targetVal));
            // Floci does not model cross-region replication, so every secret's primary region
            // IS the region it lives in — comparing the request region is equivalent to a
            // per-secret attribute here (all match when the prefix fits, none otherwise).
            case "primary-region" -> region != null && region.startsWith(targetVal);
            // owning-service is a valid Filter.Key enum value but is currently deferred (always returning false)
            case "owning-service" -> false;
            case "all" -> {
                String lowerVal = targetVal.toLowerCase();
                boolean nameMatch = secret.getName() != null && secret.getName().toLowerCase().startsWith(lowerVal);
                boolean descMatch = secret.getDescription() != null && secret.getDescription().toLowerCase().startsWith(lowerVal);
                boolean tagKeyMatch = secret.getTags() != null && secret.getTags().stream().anyMatch(t -> t.key() != null && t.key().toLowerCase().startsWith(lowerVal));
                boolean tagValueMatch = secret.getTags() != null && secret.getTags().stream().anyMatch(t -> t.value() != null && t.value().toLowerCase().startsWith(lowerVal));
                yield nameMatch || descMatch || tagKeyMatch || tagValueMatch;
            }
            default -> false;
        };
    }

    public record Filter(String key, List<String> values) {}

    public Secret deleteSecret(String secretId, Integer recoveryWindowInDays, boolean forceDelete, String region) {
        Secret secret = resolveSecret(secretId, region);
        String storageKey = regionKey(region, secret.getName());

        if (forceDelete || (recoveryWindowInDays != null && recoveryWindowInDays == 0)) {
            store.delete(storageKey);
            LOG.infov("Force-deleted secret: {0}", secret.getName());
            secret.setDeletedDate(Instant.now());
            return secret;
        }

        int windowDays = (recoveryWindowInDays != null) ? recoveryWindowInDays : defaultRecoveryWindowDays;
        Instant deletedDate = Instant.now().plusSeconds((long) windowDays * 86400);
        secret.setDeletedDate(deletedDate);
        store.put(storageKey, secret);
        LOG.infov("Scheduled deletion of secret: {0} at {1}", secret.getName(), deletedDate);
        return secret;
    }

    public Secret restoreSecret(String secretId, String region) {
        Secret secret = resolveSecret(secretId, region);
        String storageKey = regionKey(region, secret.getName());

        if (secret.getDeletedDate() == null) {
            throw new AwsException("InvalidRequestException",
                    "You can't perform this operation on the secret because it was not deleted.", 400);
        }

        secret.setDeletedDate(null);
        store.put(storageKey, secret);
        LOG.infov("Restored secret: {0}", secret.getName());
        return secret;
    }

    public Secret rotateSecret(String secretId, String clientRequestToken, String rotationLambdaArn, Secret.RotationRules rotationRules,
                               boolean rotateImmediately, String region) {
        Secret secret = resolveSecret(secretId, region);

        if (secret.getDeletedDate() != null) {
            throw new AwsException("ResourceNotFoundException",
                    "Secrets Manager can't find the specified secret.", 400);
        }

        if (clientRequestToken != null && (clientRequestToken.length() < 32 || clientRequestToken.length() > 64)) {
            throw new AwsException("InvalidParameterException", "ClientRequestToken must be between 32 and 64 characters long.", 400);
        }

        if (rotationRules != null && rotationRules.automaticallyAfterDays() != null && rotationRules.scheduleExpression() != null) {
            throw new AwsException("InvalidParameterException",
                    "RotationRules can't include both AutomaticallyAfterDays and ScheduleExpression.", 400);
        }

        String finalLambdaArn = rotationLambdaArn != null ? rotationLambdaArn : secret.getRotationLambdaArn();
        if (finalLambdaArn == null) {
            throw new AwsException("InvalidRequestException",
                    "You tried to enable rotation on a secret that doesn't already have a Lambda function ARN configured and you didn't include such an ARN as a parameter in this call.", 400);
        }

        // Validate Lambda exists synchronously
        if (lambdaService != null) {
            try {
                lambdaService.getFunction(region, finalLambdaArn);
            } catch (AwsException e) {
                if (e.getHttpStatus() == 404) {
                    throw new AwsException("ResourceNotFoundException",
                            "Secrets Manager cannot find the specified Lambda function.", 404);
                }
                throw e;
            }
        }

        synchronized (lockFor(secret.getArn())) {
            SecretVersion pendingVersion = findVersionByStage(secret, "AWSPENDING");
            if (pendingVersion != null) {
                SecretVersion currentVersion = findVersionByStage(secret, "AWSCURRENT");
                if (currentVersion == null || !pendingVersion.getVersionId().equals(currentVersion.getVersionId())) {
                    throw new AwsException("InvalidRequestException",
                            "A previous rotation isn't complete. That rotation will be reattempted.", 400);
                }
            }

            if (rotationLambdaArn != null) {
                secret.setRotationLambdaArn(rotationLambdaArn);
            }
            if (rotationRules != null) {
                secret.setRotationRules(rotationRules);
            }
            secret.setRotationEnabled(true);
            secret.setLastChangedDate(Instant.now());

            store.put(regionKey(region, secret.getName()), secret);
        }

        String arn = secret.getArn();
        String finalToken = clientRequestToken != null && !clientRequestToken.isEmpty() ? clientRequestToken : UUID.randomUUID().toString();
        boolean isExistingVersion = secret.getVersions() != null && secret.getVersions().containsKey(finalToken);
        
        rotationExecutor.submit(() -> {
            try {
                executeRotationLifecycle(arn, finalToken, finalLambdaArn, rotateImmediately, isExistingVersion, region);
            } catch (Exception e) {
                LOG.errorv(e, "Rotation lifecycle failed for secret {0}", arn);
            }
        });

        LOG.infov("Started background rotation for secret: {0}", secret.getName());
        return secret;
    }


    private void executeRotationLifecycle(String secretArn, String clientRequestToken, String lambdaArn, boolean rotateImmediately, boolean isExistingVersion, String region) {
        if (!rotateImmediately) {
            invokeRotationLambda(secretArn, clientRequestToken, lambdaArn, "testSecret", region);
            
            Secret refreshed = resolveSecret(secretArn, region);
            SecretVersion pending = findVersionByStage(refreshed, "AWSPENDING");
            if (pending != null) {
                List<String> stages = new ArrayList<>(pending.getVersionStages());
                stages.remove("AWSPENDING");
                pending.setVersionStages(stages);
                store.put(regionKey(region, refreshed.getName()), refreshed);
            }
            return;
        }

        try {
            if (!isExistingVersion) {
                synchronized (lockFor(secretArn)) {
                    Secret secret = resolveSecret(secretArn, region);
                    if (secret.getVersions() != null && secret.getVersions().containsKey(clientRequestToken)) {
                        isExistingVersion = true;
                    } else {
                        SecretVersion pendingVersion = new SecretVersion();
                        pendingVersion.setVersionId(clientRequestToken);
                        pendingVersion.setVersionStages(List.of("AWSPENDING"));
                        pendingVersion.setCreatedDate(Instant.now());
                        if (secret.getVersions() == null) {
                            secret.setVersions(new java.util.HashMap<>());
                        }
                        secret.getVersions().put(clientRequestToken, pendingVersion);
                        store.put(regionKey(region, secret.getName()), secret);
                    }
                }
            }
            if (!isExistingVersion) {
                invokeRotationLambda(secretArn, clientRequestToken, lambdaArn, "createSecret", region);
                invokeRotationLambda(secretArn, clientRequestToken, lambdaArn, "setSecret", region);
            }
            invokeRotationLambda(secretArn, clientRequestToken, lambdaArn, "testSecret", region);
            invokeRotationLambda(secretArn, clientRequestToken, lambdaArn, "finishSecret", region);
            
            Secret refreshed = resolveSecret(secretArn, region);
            refreshed.setLastRotatedDate(Instant.now());
            store.put(regionKey(region, refreshed.getName()), refreshed);
        } catch (Exception e) {
            LOG.errorv(e, "Error during rotation steps for secret {0}", secretArn);
            throw e;
        }
    }

    private void invokeRotationLambda(String secretArn, String clientRequestToken, String lambdaArn, String step, String region) {
        if (lambdaService == null) {
            LOG.warnv("LambdaService is not available; skipping rotation lambda invocation for step {0}", step);
            return;
        }
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("Step", step);
            payload.put("SecretId", secretArn);
            payload.put("ClientRequestToken", clientRequestToken);
            payload.put("RotationToken", clientRequestToken);

            String payloadStr = objectMapper.writeValueAsString(payload);

            InvokeResult result = lambdaService.invoke(region, lambdaArn, payloadStr.getBytes(java.nio.charset.StandardCharsets.UTF_8), InvocationType.RequestResponse);
            if (result.getFunctionError() != null) {
                throw new AwsException("InternalServiceError",
                        "Rotation lambda returned an error during " + step + ": " + result.getFunctionError(), 500);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke rotation lambda for step " + step, e);
        }
    }

    public void tagResource(String secretId, List<Secret.Tag> tags, String region) {
        Secret secret = resolveSecret(secretId, region);

        List<Secret.Tag> existing = secret.getTags() != null ? new ArrayList<>(secret.getTags()) : new ArrayList<>();
        for (Secret.Tag newTag : tags) {
            existing.removeIf(t -> t.key().equals(newTag.key()));
            existing.add(newTag);
        }
        secret.setTags(existing);
        store.put(regionKey(region, secret.getName()), secret);
    }

    public void untagResource(String secretId, List<String> tagKeys, String region) {
        Secret secret = resolveSecret(secretId, region);

        List<Secret.Tag> existing = secret.getTags() != null ? new ArrayList<>(secret.getTags()) : new ArrayList<>();
        existing.removeIf(t -> tagKeys.contains(t.key()));
        secret.setTags(existing);
        store.put(regionKey(region, secret.getName()), secret);
    }

    public Map<String, List<String>> listSecretVersionIds(String secretId, String region) {
        Secret secret = resolveSecret(secretId, region);

        Map<String, List<String>> result = new HashMap<>();
        if (secret.getVersions() != null) {
            for (Map.Entry<String, SecretVersion> entry : secret.getVersions().entrySet()) {
                result.put(entry.getKey(), entry.getValue().getVersionStages());
            }
        }
        return result;
    }

    public BatchGetSecretValueResult batchGetSecretValue(List<String> secretIdList, String region) {
        List<BatchSecretValue> secretValues = new ArrayList<>();
        List<BatchGetSecretValueError> errors = new ArrayList<>();

        if (secretIdList == null) {
            return BatchGetSecretValueResult.empty();
        }

        for (String secretId : secretIdList) {
            try {
                Secret secret = resolveSecret(secretId, region);
                if (secret.getDeletedDate() != null) {
                    errors.add(new BatchGetSecretValueError(secretId, "InvalidRequestException",
                            "You can't perform this operation on the secret because it was marked for deletion."));
                    continue;
                }

                SecretVersion version = findVersionByStage(secret, AWSCURRENT);
                if (version == null) {
                    errors.add(new BatchGetSecretValueError(secretId, "ResourceNotFoundException",
                            "Secrets Manager can't find the specified secret value for staging label: " + AWSCURRENT));
                    continue;
                }

                secretValues.add(new BatchSecretValue(
                        secret.getArn(),
                        secret.getName(),
                        version.getSecretString(),
                        version.getSecretBinary(),
                        version.getVersionId(),
                        version.getVersionStages(),
                        version.getCreatedDate()
                ));
            } catch (AwsException e) {
                errors.add(new BatchGetSecretValueError(secretId, e.getErrorCode(), e.getMessage()));
            }
        }

        return new BatchGetSecretValueResult(secretValues, errors);
    }

    public Secret updateSecretVersionStage(String secretId, String moveToVersionId, String removeFromVersionId, String versionStage, String region) {

        if (secretId == null || secretId.isEmpty() || secretId.length() > 2048) {
            throw new AwsException("InvalidParameterException", "Parameter validation failed. Invalid SecretId.", 400);
        } else if (versionStage == null || versionStage.isEmpty() || versionStage.length() > 256) {
            throw new AwsException("InvalidParameterException", "Parameter validation failed. Invalid VersionStage.", 400);
        } else if (moveToVersionId != null && (moveToVersionId.length() < 32 || moveToVersionId.length() > 64)) {
            throw new AwsException("InvalidParameterException", "Parameter validation failed. Invalid MoveToVersionId.", 400);
        } else if (removeFromVersionId != null && (removeFromVersionId.length() < 32 || removeFromVersionId.length() > 64)) {
            throw new AwsException("InvalidParameterException", "Parameter validation failed. Invalid RemoveFromVersionId.", 400);
        }

        Secret secret = resolveSecret(secretId, region);
        if (secret.getDeletedDate() != null) {
            throw new AwsException("ResourceNotFoundException", "Secrets Manager can't find the specified secret.", 400);
        }

        SecretVersion versionByStage = findVersionByStage(secret, versionStage);
        String currentVersionId = versionByStage != null
                ? versionByStage.getVersionId() : null;

        if (currentVersionId != null) {

            // If the label is attached and you either do not specify
            // this parameter, or the version ID does not match, then the
            // operation fails.
            if (removeFromVersionId == null) {
                throw new AwsException("InvalidParameterException",
                        ("The parameter RemoveFromVersionId can't be empty. Staging label %s is currently attached to "
                            + "version %s, so you must explicitly reference that version in RemoveFromVersionId.")
                        .formatted(versionByStage, currentVersionId), 400);
            } else if (!Objects.equals(currentVersionId, removeFromVersionId)) {
                throw new AwsException("InvalidParameterException",
                        ("When you move staging label %s, if you specify RemoveFromVersionId, it must be set to the "
                            + "version that currently has the staging label %s.")
                        .formatted(versionByStage, currentVersionId), 400);
            }

            List<String> mutableStages = new ArrayList<>(secret.getVersions()
                    .get(removeFromVersionId).getVersionStages());
            mutableStages.remove(versionStage);

            if (AWSCURRENT.equals(versionStage)) {
                mutableStages.add(AWSPREVIOUS);

                // remove AWSPREVIOUS tag from the previous SecretVersion
                SecretVersion previous = findVersionByStage(secret, AWSPREVIOUS);
                if (previous != null) {
                    List<String> mutablePrevStages =
                            new ArrayList<>(previous.getVersionStages());
                    mutablePrevStages.remove(AWSPREVIOUS);
                    previous.setVersionStages(mutablePrevStages);
                }

                // we will set currentVersionId further down
            }
            secret.getVersions().get(removeFromVersionId).setVersionStages(mutableStages);
        }

        if (moveToVersionId != null) {
            // check whether it exists
            if (!secret.getVersions().containsKey(moveToVersionId)) {
                throw new AwsException("ResourceNotFoundException",
                        "Secrets Manager can't find the specified secret value for VersionId: %s.".formatted(moveToVersionId),
                        400);
            }

            // we are adding versionStage to this ID
            List<String> mutableStages = new ArrayList<>(secret.getVersions().get(moveToVersionId).getVersionStages());
            mutableStages.add(versionStage);
            secret.getVersions().get(moveToVersionId).setVersionStages(mutableStages);
            
            if (AWSCURRENT.equals(versionStage)) {
                secret.setCurrentVersionId(moveToVersionId);
            }
        }

        store.put(regionKey(region, secret.getName()), secret);

        return secret;
    }

    public record BatchSecretValue(
            String arn,
            String name,
            String secretString,
            String secretBinary,
            String versionId,
            List<String> versionStages,
            Instant createdDate
    ) {
    }

    public record BatchGetSecretValueError(
            String secretId,
            String errorCode,
            String message
    ) {
    }

    public record BatchGetSecretValueResult(
        List<BatchSecretValue> values,
        List<BatchGetSecretValueError> errors
    ) {
        public static BatchGetSecretValueResult empty() {
            return new BatchGetSecretValueResult(Collections.emptyList(), Collections.emptyList());
        }
    }

    private Secret resolveSecret(String secretId, String region) {
        if (secretId.startsWith("arn:")) {
            // 1. Exact full-ARN match
            List<Secret> found = store.scan(key -> {
                Secret s = store.get(key).orElse(null);
                return s != null && secretId.equals(s.getArn());
            });
            if (!found.isEmpty()) {
                return found.getFirst();
            }

            // 2. Partial-ARN fallback: extract region + name and do a name-based lookup.
            //    AWS supports ARNs without the trailing "-XXXXXX" random suffix.
            //    ARN format: arn:aws:secretsmanager:<region>:<account>:secret:<name>
            String smPrefix = "arn:aws:secretsmanager:";
            if (secretId.startsWith(smPrefix)) {
                String[] parts = secretId.substring(smPrefix.length()).split(":", 4);
                if (parts.length == 4 && "secret".equals(parts[2])) {
                    String arnRegion = parts[0];
                    String nameFromArn = parts[3];
                    Secret byName = store.get(regionKey(arnRegion, nameFromArn)).orElse(null);
                    if (byName != null) {
                        return byName;
                    }
                }
            }

            throw new AwsException("ResourceNotFoundException",
                    "Secrets Manager can't find the specified secret.", 400);
        }

        String storageKey = regionKey(region, secretId);
        return store.get(storageKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Secrets Manager can't find the specified secret.", 400));
    }

    private SecretVersion findVersionByStage(Secret secret, String stage) {
        if (secret.getVersions() == null) {
            return null;
        }
        for (SecretVersion v : secret.getVersions().values()) {
            if (v.getVersionStages() != null && v.getVersionStages().contains(stage)) {
                return v;
            }
        }
        return null;
    }

    private String buildSecretArn(String region, String name) {
        String suffix = randomSuffix();
        return regionResolver.buildArn("secretsmanager", region, "secret:" + name + "-" + suffix);
    }

    private static String regionKey(String region, String name) {
        return region + "::" + name;
    }

    private static String randomSuffix() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
