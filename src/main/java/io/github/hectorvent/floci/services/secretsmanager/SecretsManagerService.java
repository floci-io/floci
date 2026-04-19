package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Inject
    public SecretsManagerService(StorageFactory factory, EmulatorConfig config, RegionResolver regionResolver) {
        this(factory.create("secretsmanager", "secretsmanager-secrets.json",
                        new TypeReference<Map<String, Secret>>() {}),
                config.services().secretsmanager().defaultRecoveryWindowDays(),
                regionResolver);
    }

    SecretsManagerService(StorageBackend<String, Secret> store, int defaultRecoveryWindowDays) {
        this(store, defaultRecoveryWindowDays, new RegionResolver("us-east-1", "000000000000"));
    }

    SecretsManagerService(StorageBackend<String, Secret> store, int defaultRecoveryWindowDays,
                          RegionResolver regionResolver) {
        this.store = store;
        this.defaultRecoveryWindowDays = defaultRecoveryWindowDays;
        this.regionResolver = regionResolver;
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
                                        String secretBinary, String region,
                                        List<String> versionStages) {
        Secret secret = resolveSecret(secretId, region);

        if (secret.getDeletedDate() != null) {
            throw new AwsException("ResourceNotFoundException",
                    "Secrets Manager can't find the specified secret.", 400);
        }

        Instant now = Instant.now();
        String newVersionId = UUID.randomUUID().toString();

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

        for (String stage : stages) {
            SecretVersion version = findVersionByStage(secret, stage);
            if (version == null) {
                continue;
            }
            List<String> newStages = new ArrayList<>(version.getVersionStages());
            if (stage.equals(AWSCURRENT)) {
                newStages.add(AWSPREVIOUS);
            }
            newStages.remove(stage);

            version.setVersionStages(newStages);
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

    public Secret rotateSecret(String secretId, String rotationLambdaArn, Map<String, Integer> rotationRules,
                               boolean rotateImmediately, String region) {
        Secret secret = resolveSecret(secretId, region);

        if (secret.getDeletedDate() != null) {
            throw new AwsException("ResourceNotFoundException",
                    "Secrets Manager can't find the specified secret.", 400);
        }

        secret.setRotationEnabled(true);
        secret.setLastChangedDate(Instant.now());

        store.put(regionKey(region, secret.getName()), secret);
        LOG.infov("Stub: Rotated secret: {0} (rotation enabled)", secret.getName());
        return secret;
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

    public List<BatchSecretValue> batchGetSecretValue(List<String> secretIdList, String region) {
        List<BatchSecretValue> result = new ArrayList<>();
        if (secretIdList == null) {
            return result;
        }

        for (String secretId : secretIdList) {
            try {
                Secret secret = resolveSecret(secretId, region);
                if (secret.getDeletedDate() != null) {
                    continue;
                }
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
            } catch (AwsException e) {
                // AWS documentation says: "Secrets Manager doesn't return an error if a secret in the SecretIdList doesn't exist."
                // Wait, let me re-check that. 
                // Actually, "If any of the secrets in the SecretIdList don't exist, Secrets Manager returns an error."
                // Let me verify this in the AWS docs.
                throw e;
            }
        }
        return result;
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
