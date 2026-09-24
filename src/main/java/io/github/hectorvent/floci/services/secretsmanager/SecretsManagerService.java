package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.scheduler.SchedulerExpressionParser;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class SecretsManagerService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(SecretsManagerService.class);

    private static final String AWSCURRENT = "AWSCURRENT";
    private static final String AWSPREVIOUS = "AWSPREVIOUS";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String AWS_MANAGED_KEY = "aws/secretsmanager";
    private static final String PENDING_DELETION_STATE = "PendingDeletion";
    private static final Pattern SECRET_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9/_+=.@-]+");

    private final StorageBackend<String, Secret> store;
    private final int defaultRecoveryWindowDays;
    private final RegionResolver regionResolver;
    private final LambdaService lambdaService;
    private final ObjectMapper objectMapper;
    private final KmsService kmsService;

    // Fixed threads with an unbounded queue: a burst of rotations is queued, not rejected,
    // because RotateSecret documents no overload error.
    static final int ROTATION_EXECUTOR_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());
    private final ExecutorService rotationExecutor =
            Executors.newFixedThreadPool(ROTATION_EXECUTOR_POOL_SIZE, new RotationThreadFactory());

    // Fixed stripes instead of one lock per ARN, which was never removed. Secrets that share a
    // stripe only contend; no lock is held while a rotation Lambda runs.
    private static final int ROTATION_LOCK_STRIPE_COUNT = 32;
    private final Object[] rotationLockStripes = newLockStripes(ROTATION_LOCK_STRIPE_COUNT);

    private static Object[] newLockStripes(int count) {
        Object[] stripes = new Object[count];
        for (int i = 0; i < count; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }

    private Object lockFor(String secretArn) {
        int index = Math.floorMod(secretArn.hashCode(), rotationLockStripes.length);
        return rotationLockStripes[index];
    }

    /** Names rotation threads for diagnostics. */
    private static final class RotationThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            // Set explicitly: a new thread otherwise inherits these from whichever thread grows the
            // pool, and a daemon rotation thread could be cut off mid-rotation at JVM exit.
            Thread thread = new Thread(runnable, "secretsmanager-rotation-" + nextId.getAndIncrement());
            thread.setDaemon(false);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }

    @Inject
    public SecretsManagerService(StorageFactory factory, EmulatorConfig config, RegionResolver regionResolver,
                                 LambdaService lambdaService, ObjectMapper objectMapper,
                                 KmsService kmsService) {
        this(factory.create("secretsmanager", "secretsmanager-secrets.json",
                        new TypeReference<Map<String, Secret>>() {}),
                config.services().secretsmanager().defaultRecoveryWindowDays(),
                regionResolver, lambdaService, objectMapper, kmsService);
    }

    SecretsManagerService(StorageBackend<String, Secret> store, int defaultRecoveryWindowDays) {
        this(store, defaultRecoveryWindowDays, new RegionResolver("us-east-1", "000000000000"), null,
                new ObjectMapper(), null);
    }

    SecretsManagerService(StorageBackend<String, Secret> store, int defaultRecoveryWindowDays,
                          RegionResolver regionResolver, LambdaService lambdaService, ObjectMapper objectMapper) {
        this(store, defaultRecoveryWindowDays, regionResolver, lambdaService, objectMapper, null);
    }

    SecretsManagerService(StorageBackend<String, Secret> store, int defaultRecoveryWindowDays,
                          RegionResolver regionResolver, LambdaService lambdaService,
                          ObjectMapper objectMapper, KmsService kmsService) {
        this.store = store;
        this.defaultRecoveryWindowDays = defaultRecoveryWindowDays;
        this.regionResolver = regionResolver;
        this.lambdaService = lambdaService;
        this.objectMapper = objectMapper;
        this.kmsService = kmsService;
    }

    @PreDestroy
    void shutdown() {
        rotationExecutor.shutdown();
    }

    public Secret createSecret(String name, String secretString, String secretBinary,
                               String description, String kmsKeyId, List<Secret.Tag> tags, String region) {
        return createSecret(name, secretString, secretBinary, description, kmsKeyId, tags, null, null, region);
    }

    /**
     * Creates a secret, optionally owned by an AWS service such as {@code rds}. A service-owned
     * secret is rotated by that service rather than by a rotation Lambda, so callers cannot supply
     * one for it. Only other floci services set {@code owningService}; the wire API cannot.
     */
    public Secret createSecret(String name, String secretString, String secretBinary,
                               String description, String kmsKeyId, List<Secret.Tag> tags,
                               String owningService, String region) {
        return createSecret(name, secretString, secretBinary, description, kmsKeyId, tags,
                owningService, null, region);
    }

    /**
     * Creates a secret whose initial version carries {@code clientRequestToken} as its version id.
     * AWS documents that token as becoming the {@code VersionId} of the new version, which is what
     * makes CreateSecret idempotent and lets a caller address the version it just wrote without
     * a follow-up DescribeSecret. A {@code null} token falls back to a generated UUID, matching
     * what the SDKs do on the client side.
     */
    public Secret createSecret(String name, String secretString, String secretBinary,
                               String description, String kmsKeyId, List<Secret.Tag> tags,
                               String owningService, String clientRequestToken, String region) {
        if (clientRequestToken != null
                && (clientRequestToken.length() < 32 || clientRequestToken.length() > 64)) {
            throw new AwsException("InvalidParameterException",
                    "ClientRequestToken must be between 32 and 64 characters long.", 400);
        }
        throwIfBothSecretValues(secretString, secretBinary);
        validateKmsKey(kmsKeyId, region);

        String storageKey = regionKey(region, name);
        Secret existing = store.get(storageKey).orElse(null);

        if (existing != null) {
            // A name inside its recovery window is still taken: the secret is recoverable via
            // RestoreSecret, so reusing the name has to be refused rather than allowed to
            // overwrite it. Without this the create would replace the stored secret at the same
            // key AND clear its deletedDate, so the original value became unrecoverable and
            // RestoreSecret then reported "was not deleted" - silent data loss.
            throwIfPendingDeletion(existing);

            // ClientRequestToken makes CreateSecret idempotent: AWS ignores a repeat that names
            // the same version and carries the same value, so a client retrying after a dropped
            // response gets the original secret back rather than ResourceExistsException.
            SecretVersion tokenVersion = clientRequestToken != null && existing.getVersions() != null
                    ? existing.getVersions().get(clientRequestToken) : null;
            if (tokenVersion != null
                    && Objects.equals(tokenVersion.getSecretString(), secretString)
                    && Objects.equals(tokenVersion.getSecretBinary(), secretBinary)) {
                return existing;
            }

            throw new AwsException("ResourceExistsException",
                    "A secret with the name " + name + " already exists.", 400);
        }

        String arn = buildSecretArn(region, name);
        Instant now = Instant.now();

        String versionId = clientRequestToken != null ? clientRequestToken : UUID.randomUUID().toString();
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
        secret.setOwningService(owningService);

        store.put(storageKey, secret);
        LOG.infov("Created secret: {0} in region {1}", name, region);
        return secret;
    }

    public SecretVersion getSecretValue(String secretId, String versionId, String versionStage, String region) {
        Secret secret = resolveSecret(secretId, region);
        throwIfPendingDeletion(secret);

        SecretVersion version;
        if (versionId != null && !versionId.isEmpty()) {
            version = secret.getVersions().get(versionId);
            if (version == null) {
                throw new AwsException("ResourceNotFoundException",
                        "Secrets Manager can't find the specified secret version.", 400);
            }
            // When both selectors are supplied they must name the same version.
            if (versionStage != null && !versionStage.isEmpty()
                    && (version.getVersionStages() == null
                            || !version.getVersionStages().contains(versionStage))) {
                throw new AwsException("InvalidRequestException",
                        "You provided a VersionStage that is not associated to the provided VersionId.", 400);
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
        // Deliberately not persist(): reading a secret is not a change to replicate, and pushing
        // the primary out to every replica on each read would rebuild them constantly and
        // clobber each replica's own last-accessed bookkeeping.
        putSecret(region, secret);

        return version;
    }

    public SecretVersion putSecretValue(String secretId, String secretString,
                                        String secretBinary, String clientRequestToken, String region,
                                        List<String> versionStages) {
        Secret secret = resolveSecret(secretId, region);
        throwIfPendingDeletion(secret);
        throwIfReplica(secret);
        throwIfBothSecretValues(secretString, secretBinary);

        if (clientRequestToken != null && (clientRequestToken.length() < 32 || clientRequestToken.length() > 64)) {
            throw new AwsException("InvalidParameterException", "ClientRequestToken must be between 32 and 64 characters long.", 400);
        }

        synchronized (lockFor(secret.getArn())) {
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
                // A version carrying the id being written is replaced wholesale below, so moving
                // the stage off it first would only leave that stage briefly unassigned to a
                // reader, which is how a rotation loses track of its own AWSPENDING version.
                if (version == null || newVersionId.equals(version.getVersionId())) {
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

            persist(secret, region);
            LOG.infov("Put secret value for: {0}", secret.getName());
            return newVersion;
        }
    }

    public Secret updateSecret(String secretId, String description, String kmsKeyId, String region) {
        Secret secret = resolveSecret(secretId, region);
        throwIfPendingDeletion(secret);
        throwIfReplica(secret);

        validateKmsKey(kmsKeyId, region);

        synchronized (lockFor(secret.getArn())) {
            if (description != null) {
                secret.setDescription(description);
            }
            if (kmsKeyId != null) {
                // AWS: "If you set this to an empty string, Secrets Manager uses the AWS managed key
                // aws/secretsmanager" - which DescribeSecret reports by omitting KmsKeyId entirely.
                secret.setKmsKeyId(kmsKeyId.isEmpty() ? null : kmsKeyId);
            }
            secret.setLastChangedDate(Instant.now());

            persist(secret, region);
            LOG.infov("Updated secret metadata: {0}", secret.getName());
            return secret;
        }
    }

    public Secret describeSecret(String secretId, String region) {
        Secret secret = resolveSecret(secretId, region);
        return secret;
    }

    /**
     * Marks the secret with this ARN as owned by an AWS service, so that it rotates the way a
     * service-managed secret does. Ownership is normally set when the owning service creates the
     * secret; this backfills secrets persisted before floci tracked it. A secret that is already
     * owned, or that no longer exists, is left as it is.
     *
     * <p>A backfill runs outside any request, so the account cannot come from the request context:
     * the secret's own ARN names the account whose store holds it, and that is the store this
     * addresses. Passing a name instead of an ARN would read the wrong account.
     */
    public void markOwnedByService(String secretArn, String owningService) {
        if (secretArn == null || !secretArn.startsWith("arn:")) {
            return;
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(secretArn);
        } catch (IllegalArgumentException e) {
            LOG.debugv("Ignoring malformed secret ARN during ownership backfill: {0}", secretArn);
            return;
        }

        synchronized (lockFor(secretArn)) {
            Secret secret = findByArnInAccount(secretArn, arn);
            if (secret == null || secret.getOwningService() != null) {
                return;
            }
            secret.setOwningService(owningService);
            String key = regionKey(arn.region(), secret.getName());
            if (store instanceof AccountAwareStorageBackend<Secret> aware) {
                aware.putForAccount(arn.accountId(), key, secret);
            } else {
                store.put(key, secret);
            }
            LOG.infov("Marked secret {0} in account {1} as owned by {2}",
                    secret.getName(), arn.accountId(), owningService);
        }
    }

    /** Finds a secret by full ARN within the account and region that ARN names. */
    private Secret findByArnInAccount(String secretArn, AwsArnUtils.Arn arn) {
        Predicate<String> inRegion = key -> key.startsWith(arn.region() + "::");
        List<Secret> candidates = store instanceof AccountAwareStorageBackend<Secret> aware
                ? aware.scanForAccount(arn.accountId(), inRegion)
                : store.scan(inRegion);
        return candidates.stream()
                .filter(s -> secretArn.equals(s.getArn()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Claims the single Secrets Manager target-attachment slot for a CloudFormation resource.
     *
     * @return {@code true} when this call created the claim, or {@code false} when the same
     *         resource already owned it.
     */
    public boolean claimTargetAttachment(String secretId, String attachmentOwner, String region) {
        Secret resolved = resolveSecret(secretId, region);
        synchronized (lockFor(resolved.getArn())) {
            Secret secret = resolveSecret(resolved.getArn(), region);
            throwIfPendingDeletion(secret);

            String existingOwner = secret.getTargetAttachmentOwner();
            if (existingOwner != null && !existingOwner.equals(attachmentOwner)) {
                throw new AwsException("ResourceExistsException",
                        "A target is already attached to secret " + secret.getArn() + ".", 400);
            }
            if (existingOwner != null) {
                return false;
            }

            secret.setTargetAttachmentOwner(attachmentOwner);
            persist(secret, region);
            return true;
        }
    }

    /**
     * Returns whether an attachment may mutate this secret. Unclaimed secrets remain manageable
     * so target attachments persisted before ownership tracking was introduced can still detach.
     */
    public boolean canManageTargetAttachment(String secretId, String attachmentOwner, String region) {
        Secret resolved;
        try {
            resolved = resolveSecret(secretId, region);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return true;
            }
            throw e;
        }

        synchronized (lockFor(resolved.getArn())) {
            Secret secret = resolveSecret(resolved.getArn(), region);
            String existingOwner = secret.getTargetAttachmentOwner();
            return existingOwner == null
                    || attachmentOwner != null && attachmentOwner.equals(existingOwner);
        }
    }

    /** Releases a target-attachment claim only when it is still owned by the caller. */
    public void releaseTargetAttachment(String secretId, String attachmentOwner, String region) {
        if (attachmentOwner == null || attachmentOwner.isBlank()) {
            return;
        }

        Secret resolved;
        try {
            resolved = resolveSecret(secretId, region);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return;
            }
            throw e;
        }

        synchronized (lockFor(resolved.getArn())) {
            Secret secret = resolveSecret(resolved.getArn(), region);
            if (!attachmentOwner.equals(secret.getTargetAttachmentOwner())) {
                return;
            }
            secret.setTargetAttachmentOwner(null);
            persist(secret, region);
        }
    }

    public List<Secret> listSecrets(String region) {
        return listSecrets(region, null, false);
    }

    public List<Secret> listSecrets(String region, List<Filter> filters) {
        return listSecrets(region, filters, false);
    }

    /**
     * Lists the region's secrets, by default hiding the ones inside their recovery window the way
     * AWS does. {@code includePlannedDeletion} surfaces those too, carrying their DeletedDate so a
     * caller can tell them apart.
     */
    public List<Secret> listSecrets(String region, List<Filter> filters, boolean includePlannedDeletion) {
        String prefix = region + "::";
        List<Secret> allSecrets = store.scan(key -> key.startsWith(prefix) && store.get(key)
                .map(s -> includePlannedDeletion || s.getDeletedDate() == null)
                .orElse(false));
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
            case "owning-service" -> secret.getOwningService() != null
                    && secret.getOwningService().startsWith(targetVal);
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
        // Parameters are checked before the secret is resolved, the way AWS orders it: an invalid
        // window is reported as such even when the secret does not exist.
        if (forceDelete && recoveryWindowInDays != null) {
            throw new AwsException("InvalidParameterException",
                    "You can't use ForceDeleteWithoutRecovery in conjunction with RecoveryWindowInDays.", 400);
        }
        // AWS accepts 7-30 only. Zero used to fall through to the force-delete branch below, which
        // silently turned "delete with no recovery window" into an unrecoverable delete; a negative
        // value produced a DeletionDate already in the past.
        if (recoveryWindowInDays != null && (recoveryWindowInDays < 7 || recoveryWindowInDays > 30)) {
            throw new AwsException("InvalidParameterException",
                    "The RecoveryWindowInDays value must be between 7 and 30 days, inclusive.", 400);
        }

        Secret secret = resolveSecret(secretId, region);
        throwIfReplica(secret);
        // AWS: "You can't delete a primary secret that is replicated to other Regions. You must
        // first delete the replicas." Deleting it here would strand every replica with no primary
        // to sync from and no way to remove them.
        if (secret.getReplicationStatus() != null && !secret.getReplicationStatus().isEmpty()) {
            throw new AwsException("InvalidRequestException",
                    "You can't delete a secret that is replicated to other Regions. "
                            + "Remove the replicas with RemoveRegionsFromReplication first.", 400);
        }
        String storageKey = regionKey(region, secret.getName());

        if (forceDelete) {
            store.delete(storageKey);
            LOG.infov("Force-deleted secret: {0}", secret.getName());
            secret.setDeletedDate(Instant.now());
            return secret;
        }

        // Guard placed AFTER the force-delete branch on purpose: force-deleting a secret that is
        // already inside its recovery window is a legitimate "skip the window, remove it now"
        // escape hatch, so only the scheduling path is refused. Re-scheduling an already-scheduled
        // secret silently moved its DeletionDate, which would quietly extend a window a caller
        // believed was already counting down.
        throwIfPendingDeletion(secret);

        int windowDays = (recoveryWindowInDays != null) ? recoveryWindowInDays : defaultRecoveryWindowDays;
        Instant deletedDate = Instant.now().plusSeconds((long) windowDays * 86400);
        secret.setDeletedDate(deletedDate);
        store.put(storageKey, secret);
        LOG.infov("Scheduled deletion of secret: {0} at {1}", secret.getName(), deletedDate);
        return secret;
    }

    // ─── Multi-region replication ──────────────────────────────────────────────

    private static final String STATUS_IN_SYNC = "InSync";
    private static final String STATUS_FAILED = "Failed";

    /**
     * When the next rotation is due, given the rules and a starting point.
     *
     * <p>{@code ScheduleExpression} wins over {@code AutomaticallyAfterDays} because RotateSecret
     * refuses a rules object carrying both. Returns null when the rules schedule nothing, which is
     * how a rotation-enabled secret with no schedule stays manual-only.
     *
     * @throws AwsException if the expression is not a form AWS accepts
     */
    /** {@code Duration} is a whole number of hours, "3h" style, 2-3 characters long. */
    private static final Pattern DURATION_PATTERN = Pattern.compile("[0-9]{1,2}h");

    /**
     * Secrets Manager accepts a narrower {@code rate()} than EventBridge Scheduler does: hours or
     * days only, where the scheduler also takes minutes and weeks.
     */
    private static final Pattern ROTATION_RATE_PATTERN =
            Pattern.compile("^rate\\(\\s*\\d+\\s+(hours?|days?)\\s*\\)$",
                    Pattern.CASE_INSENSITIVE);

    /** AWS: "You can rotate a secret as often as every four hours." */
    private static final long MIN_ROTATION_INTERVAL_MILLIS = 4 * 3_600_000L;

    /** Enforces the documented bounds on a RotationRules structure. */
    public static void validateRotationRules(Secret.RotationRules rules) {
        if (rules == null) {
            return;
        }
        Integer days = rules.automaticallyAfterDays();
        if (days != null && (days < 1 || days > 1000)) {
            throw new AwsException("InvalidParameterException",
                    "AutomaticallyAfterDays must be between 1 and 1000.", 400);
        }
        String duration = rules.duration();
        if (duration != null && !DURATION_PATTERN.matcher(duration).matches()) {
            throw new AwsException("InvalidParameterException",
                    "Duration must be a number of hours, for example 3h.", 400);
        }
        String expression = rules.scheduleExpression();
        if (expression != null) {
            if (expression.isEmpty() || expression.length() > 256) {
                throw new AwsException("InvalidParameterException",
                        "ScheduleExpression must be between 1 and 256 characters.", 400);
            }
            validateScheduleExpression(expression);
        }
    }

    private static void validateScheduleExpression(String expression) {
        SchedulerExpressionParser.Kind kind;
        try {
            kind = SchedulerExpressionParser.classify(expression);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException",
                    "Invalid ScheduleExpression: " + e.getMessage(), 400);
        }
        if (kind == SchedulerExpressionParser.Kind.AT) {
            throw new AwsException("InvalidParameterException",
                    "Secrets Manager rotation supports only rate() and cron() expressions.", 400);
        }
        if (kind != SchedulerExpressionParser.Kind.RATE) {
            return;
        }
        if (!ROTATION_RATE_PATTERN.matcher(expression.trim()).matches()) {
            throw new AwsException("InvalidParameterException",
                    "Secrets Manager rate() expressions must be in hours or days, "
                            + "for example rate(12 hours) or rate(10 days).", 400);
        }
        if (SchedulerExpressionParser.parseRateMillis(expression) < MIN_ROTATION_INTERVAL_MILLIS) {
            throw new AwsException("InvalidParameterException",
                    "You can rotate a secret as often as every four hours, but no more frequently.", 400);
        }
    }

    public static Instant computeNextRotationDate(Secret.RotationRules rules, Instant from) {
        if (rules == null) {
            return null;
        }
        String expression = rules.scheduleExpression();
        if (expression != null && !expression.isBlank()) {
            try {
                return switch (SchedulerExpressionParser.classify(expression)) {
                    case RATE -> from.plusMillis(SchedulerExpressionParser.parseRateMillis(expression));
                    case CRON -> SchedulerExpressionParser.nextCronFire(expression, from, null);
                    // at() is an EventBridge Scheduler form; Secrets Manager accepts only
                    // rate() and cron(), so it is rejected rather than silently honoured.
                    case AT -> throw new IllegalArgumentException(
                            "Secrets Manager rotation supports only rate() and cron() expressions.");
                };
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new AwsException("InvalidParameterException",
                        "Invalid ScheduleExpression: " + e.getMessage(), 400);
            }
        }
        if (rules.automaticallyAfterDays() != null) {
            return from.plusSeconds((long) rules.automaticallyAfterDays() * 86400);
        }
        return null;
    }

    /** A secret paired with the region whose store holds it, for background sweeps. */
    public record RegionalSecret(String region, Secret secret) {
    }

    /**
     * Every stored secret with its region, across every account.
     *
     * <p>Used by {@link SecretRotationScheduler}, which runs on a background thread. That matters
     * for the account: {@code store.keys()} resolves the account from the in-flight request, and
     * a sweep has none, so it would silently see only the default account's secrets and never
     * rotate anyone else's. {@code scanAllAccountEntries} is the explicit-account form built for
     * exactly this.
     */
    public List<RegionalSecret> listAllSecrets() {
        List<RegionalSecret> all = new ArrayList<>();
        if (store instanceof AccountAwareStorageBackend<Secret> aware) {
            for (AccountAwareStorageBackend.AccountEntry<Secret> entry
                    : aware.scanAllAccountEntries(k -> k.contains("::"))) {
                String keyRegion = entry.key().substring(0, entry.key().indexOf("::"));
                all.add(new RegionalSecret(keyRegion, entry.value()));
            }
            return all;
        }
        for (String key : store.keys()) {
            int separator = key.indexOf("::");
            if (separator < 0) {
                continue;
            }
            String keyRegion = key.substring(0, separator);
            store.get(key).ifPresent(secret -> all.add(new RegionalSecret(keyRegion, secret)));
        }
        return all;
    }

    /** A replication target: the region to copy into, and optionally the KMS key to record. */
    public record ReplicaRegion(String region, String kmsKeyId) {
    }

    /**
     * Copies the secret into each target region and records the outcome per region.
     *
     * <p>Real AWS replicates asynchronously and reports {@code InProgress} first; floci copies
     * synchronously, so a region is {@code InSync} by the time this returns. A region whose name
     * is already taken comes back {@code Failed} rather than throwing, because AWS reports
     * per-region outcomes and the other regions in the same call still succeed.
     */
    public Secret replicateSecretToRegions(String secretId, List<ReplicaRegion> targets,
                                           boolean forceOverwrite, String region) {
        if (targets == null || targets.isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    "AddReplicaRegions must contain at least one Region.", 400);
        }

        Secret primary = resolveSecret(secretId, region);
        throwIfPendingDeletion(primary);
        throwIfReplica(primary);

        synchronized (lockFor(primary.getArn())) {
            List<Secret.ReplicaStatus> statuses = primary.getReplicationStatus() != null
                    ? new ArrayList<>(primary.getReplicationStatus()) : new ArrayList<>();

            for (ReplicaRegion target : targets) {
                if (target.region() == null || target.region().equals(region)) {
                    throw new AwsException("InvalidParameterException",
                            "You can't replicate a secret into its own Region.", 400);
                }
                statuses.removeIf(s -> s.region().equals(target.region()));
                statuses.add(replicateInto(primary, target, forceOverwrite, region));
            }

            primary.setReplicationStatus(statuses);
            primary.setLastChangedDate(Instant.now());
            putSecret(region, primary);
            return primary;
        }
    }

    /** Writes one replica, or reports why it could not be written. */
    private Secret.ReplicaStatus replicateInto(Secret primary, ReplicaRegion target,
                                               boolean forceOverwrite, String primaryRegionName) {
        String replicaKey = regionKey(target.region(), primary.getName());
        Secret existing = store.get(replicaKey).orElse(null);
        // An unrelated secret already holding the name is only replaced when the caller asks;
        // overwriting it silently would destroy a secret this call never mentioned.
        if (existing != null && !existing.isReplica() && !forceOverwrite) {
            return new Secret.ReplicaStatus(target.region(), target.kmsKeyId(), STATUS_FAILED,
                    "A secret with this name already exists in this region.", null);
        }

        putSecret(target.region(), buildReplica(primary, target, primaryRegionName));
        LOG.infov("Replicated secret {0} to region {1}", primary.getName(), target.region());
        return new Secret.ReplicaStatus(target.region(), target.kmsKeyId(), STATUS_IN_SYNC,
                "Replication succeeded.", null);
    }

    /**
     * Builds the replica copy. The ARN is the primary's with the region swapped, which is the
     * identity AWS gives a replica and what StopReplicationToReplica promises to return.
     */
    private Secret buildReplica(Secret primary, ReplicaRegion target, String primaryRegionName) {
        Secret replica = new Secret();
        replica.setName(primary.getName());
        replica.setArn(replicaArn(primary.getArn(), target.region()));
        replica.setDescription(primary.getDescription());
        replica.setKmsKeyId(target.kmsKeyId() != null ? target.kmsKeyId() : primary.getKmsKeyId());
        replica.setCreatedDate(primary.getCreatedDate());
        replica.setLastChangedDate(primary.getLastChangedDate());
        replica.setLastRotatedDate(primary.getLastRotatedDate());
        replica.setRotationEnabled(primary.isRotationEnabled());
        replica.setRotationLambdaArn(primary.getRotationLambdaArn());
        replica.setRotationRules(primary.getRotationRules());
        replica.setNextRotationDate(primary.getNextRotationDate());
        replica.setOwningService(primary.getOwningService());
        replica.setTags(primary.getTags() != null ? new ArrayList<>(primary.getTags()) : null);
        replica.setCurrentVersionId(primary.getCurrentVersionId());
        replica.setPrimaryRegion(primaryRegionName);

        Map<String, SecretVersion> versions = new HashMap<>();
        if (primary.getVersions() != null) {
            primary.getVersions().forEach((id, version) -> versions.put(id, copyVersion(version)));
        }
        replica.setVersions(versions);
        return replica;
    }

    private static SecretVersion copyVersion(SecretVersion source) {
        SecretVersion copy = new SecretVersion();
        copy.setVersionId(source.getVersionId());
        copy.setSecretString(source.getSecretString());
        copy.setSecretBinary(source.getSecretBinary());
        copy.setCreatedDate(source.getCreatedDate());
        copy.setVersionStages(source.getVersionStages() != null
                ? new ArrayList<>(source.getVersionStages()) : null);
        return copy;
    }

    /** The primary ARN with only the region replaced, preserving the random name suffix. */
    private String replicaArn(String primaryArn, String replicaRegion) {
        AwsArnUtils.Arn parsed = parseOrNull(primaryArn);
        if (parsed == null) {
            return primaryArn;
        }
        return regionResolver.buildArn("secretsmanager", replicaRegion, parsed.resource());
    }

    /** Deletes the named replicas outright, as AWS does, and drops them from the primary. */
    public Secret removeRegionsFromReplication(String secretId, List<String> regionsToRemove, String region) {
        if (regionsToRemove == null || regionsToRemove.isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    "RemoveReplicaRegions must contain at least one Region.", 400);
        }

        Secret primary = resolveSecret(secretId, region);
        throwIfReplica(primary);

        synchronized (lockFor(primary.getArn())) {
            List<Secret.ReplicaStatus> statuses = primary.getReplicationStatus() != null
                    ? new ArrayList<>(primary.getReplicationStatus()) : new ArrayList<>();

            for (String target : regionsToRemove) {
                Secret replica = store.get(regionKey(target, primary.getName())).orElse(null);
                // Only a replica of THIS secret is removed; an unrelated secret that happens to
                // share the name in that region is left alone.
                if (replica != null && region.equals(replica.getPrimaryRegion())) {
                    store.delete(regionKey(target, primary.getName()));
                    LOG.infov("Removed replica of {0} from region {1}", primary.getName(), target);
                }
                statuses.removeIf(s -> s.region().equals(target));
            }

            primary.setReplicationStatus(statuses);
            primary.setLastChangedDate(Instant.now());
            putSecret(region, primary);
            return primary;
        }
    }

    /**
     * Promotes the replica in {@code region} to a standalone primary, which is why this must be
     * called in the replica's own region rather than the primary's.
     */
    public Secret stopReplicationToReplica(String secretId, String region) {
        Secret replica = resolveSecret(secretId, region);
        if (!replica.isReplica()) {
            throw new AwsException("InvalidRequestException",
                    "You must call StopReplicationToReplica from the Region of the replica "
                            + "you want to promote.", 400);
        }

        String primaryRegionName = replica.getPrimaryRegion();
        Secret primary = store.get(regionKey(primaryRegionName, replica.getName())).orElse(null);

        // The primary's monitor, not the replica's: persist() writes a primary's replica slots
        // while holding only the primary's lock, so guarding the promotion with the replica's own
        // ARN would leave the two paths writing the same slot under different monitors. Both
        // halves of the promotion run inside one critical section for the same reason: a sync
        // landing between them would write the replica back over the copy just promoted.
        synchronized (lockFor(primary != null ? primary.getArn() : replica.getArn())) {
            replica.setPrimaryRegion(null);
            replica.setLastChangedDate(Instant.now());
            putSecret(region, replica);

            // Detach from the old primary too, so it stops syncing over the promoted copy and can
            // itself be deleted once no replicas remain.
            if (primary != null && primary.getReplicationStatus() != null) {
                List<Secret.ReplicaStatus> statuses = new ArrayList<>(primary.getReplicationStatus());
                statuses.removeIf(s -> s.region().equals(region));
                primary.setReplicationStatus(statuses);
                putSecret(primaryRegionName, primary);
            }
        }

        LOG.infov("Promoted replica of {0} in region {1} to a standalone secret",
                replica.getName(), region);
        return replica;
    }

    /**
     * Persists a primary and pushes the change out to its replicas, so a replica never serves a
     * value the primary has moved past.
     */
    private void persist(Secret updated, String updatedRegion) {
        putSecret(updatedRegion, updated);

        List<Secret.ReplicaStatus> statuses = updated.getReplicationStatus();
        if (statuses == null || statuses.isEmpty() || updated.isReplica()) {
            return;
        }
        for (Secret.ReplicaStatus status : statuses) {
            if (!STATUS_IN_SYNC.equals(status.status())) {
                continue;
            }
            putSecret(status.region(),
                    buildReplica(updated, new ReplicaRegion(status.region(), status.kmsKeyId()), updatedRegion));
        }
    }

    /**
     * Writes a secret under the account its own ARN names rather than the ambient one.
     *
     * <p>In a request these are the same account, so this changes nothing there. On a background
     * thread they are not: the ambient account falls back to the default, so a plain
     * {@code store.put} would write a foreign account's secret into the default account's
     * namespace - a phantom copy, and the real secret left unrotated.
     */
    private void putSecret(String region, Secret secret) {
        String key = regionKey(region, secret.getName());
        AwsArnUtils.Arn arn = parseOrNull(secret.getArn());
        if (arn != null && store instanceof AccountAwareStorageBackend<Secret> aware) {
            aware.putForAccount(arn.accountId(), key, secret);
        } else {
            store.put(key, secret);
        }
    }

    /** A replica is read-only; AWS directs the caller to the primary's region instead. */
    private static void throwIfReplica(Secret secret) {
        if (secret.isReplica()) {
            throw new AwsException("InvalidRequestException",
                    "You can't modify a replica secret. Make the change in the primary secret's "
                            + "Region " + secret.getPrimaryRegion() + ".", 400);
        }
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
        throwIfPendingDeletion(secret);
        throwIfReplica(secret);

        if (clientRequestToken != null && (clientRequestToken.length() < 32 || clientRequestToken.length() > 64)) {
            throw new AwsException("InvalidParameterException", "ClientRequestToken must be between 32 and 64 characters long.", 400);
        }

        if (rotationRules != null && rotationRules.automaticallyAfterDays() != null && rotationRules.scheduleExpression() != null) {
            throw new AwsException("InvalidParameterException",
                    "RotationRules can't include both AutomaticallyAfterDays and ScheduleExpression.", 400);
        }
        validateRotationRules(rotationRules);

        // A service-managed secret is rotated by its owning service, so it has no rotation Lambda:
        // AWS rejects one here, and does not require one to enable rotation.
        boolean serviceManaged = secret.getOwningService() != null;
        if (serviceManaged && rotationLambdaArn != null) {
            throw new AwsException("InvalidRequestException",
                    "Rotation Lambda ARN is not supported for a service-managed secret.", 400);
        }

        String finalLambdaArn = rotationLambdaArn != null ? rotationLambdaArn : secret.getRotationLambdaArn();
        if (!serviceManaged && finalLambdaArn == null) {
            throw new AwsException("InvalidRequestException",
                    "You tried to enable rotation on a secret that doesn't already have a Lambda function ARN configured and you didn't include such an ARN as a parameter in this call.", 400);
        }

        // Validate Lambda exists synchronously
        if (!serviceManaged && lambdaService != null) {
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
            // Scheduled from now rather than from the rotation's completion on purpose: the
            // rotation itself runs asynchronously and may fail, and a schedule that only advanced
            // on success would leave the background sweep retrying it on every tick forever.
            secret.setNextRotationDate(computeNextRotationDate(secret.getRotationRules(), Instant.now()));
            // A service-managed secret is rotated by its owning service, which floci does not
            // emulate: the master password is not re-issued here, so nothing sets LastRotatedDate.
            // Reporting a rotation that did not happen would leave GetSecretValue and the database
            // holding a credential the secret claims to have replaced.

            persist(secret, region);
        }

        if (serviceManaged) {
            LOG.infov("Enabled service-managed rotation for secret: {0}", secret.getName());
            return secret;
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


    /**
     * The outcome of {@link #cancelRotateSecret}: the secret with rotation turned off, plus the id
     * of the version a cancelled rotation left behind, or {@code null} when no rotation was in
     * flight.
     */
    public record CancelRotationResult(Secret secret, String pendingVersionId) {
    }

    /**
     * Turns automatic rotation off and reports the half-built version an in-flight rotation left
     * staged as AWSPENDING.
     *
     * <p>The staging labels are deliberately left alone, as AWS leaves them: rolling forward or
     * back is the caller's decision, made with UpdateSecretVersionStage. The returned version id
     * is the only handle on that version, and it matters - a stale AWSPENDING that is not also
     * AWSCURRENT makes every later {@link #rotateSecret} fail with "a previous rotation isn't
     * complete", so a caller that cannot find it can never rotate the secret again.
     *
     * <p>{@code RotationRules} and the Lambda ARN are kept so rotation can be switched back on
     * with a bare RotateSecret, which is what DescribeSecret's "previously had rotation turned on"
     * contract promises.
     */
    public CancelRotationResult cancelRotateSecret(String secretId, String region) {
        Secret secret = resolveSecret(secretId, region);
        throwIfPendingDeletion(secret);
        throwIfReplica(secret);

        synchronized (lockFor(secret.getArn())) {
            secret.setRotationEnabled(false);
            // The schedule goes with the rotation; leaving it set would keep the background sweep
            // firing a rotation the caller just turned off.
            secret.setNextRotationDate(null);
            secret.setLastChangedDate(Instant.now());
            persist(secret, region);

            // AWSPENDING sitting on the AWSCURRENT version is the signature of a rotation that
            // finished, not one that was interrupted, so there is nothing partial to report.
            SecretVersion pending = findVersionByStage(secret, "AWSPENDING");
            SecretVersion current = findVersionByStage(secret, AWSCURRENT);
            String pendingVersionId = pending != null
                    && (current == null || !pending.getVersionId().equals(current.getVersionId()))
                    ? pending.getVersionId() : null;

            LOG.infov("Cancelled rotation for secret: {0}", secret.getName());
            return new CancelRotationResult(secret, pendingVersionId);
        }
    }

    private void executeRotationLifecycle(String secretArn, String clientRequestToken, String lambdaArn, boolean rotateImmediately, boolean isExistingVersion, String region) {
        if (!rotateImmediately) {
            invokeRotationLambda(secretArn, clientRequestToken, lambdaArn, "testSecret", region);

            synchronized (lockFor(secretArn)) {
                Secret refreshed = resolveSecret(secretArn, region);
                SecretVersion pending = findVersionByStage(refreshed, "AWSPENDING");
                if (pending != null) {
                    List<String> stages = new ArrayList<>(pending.getVersionStages());
                    stages.remove("AWSPENDING");
                    pending.setVersionStages(stages);
                    persist(refreshed, region);
                }
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
                            secret.setVersions(new HashMap<>());
                        }
                        secret.getVersions().put(clientRequestToken, pendingVersion);
                        persist(secret, region);
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
            persist(refreshed, region);
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
        throwIfPendingDeletion(secret);
        throwIfReplica(secret);

        List<Secret.Tag> existing = secret.getTags() != null ? new ArrayList<>(secret.getTags()) : new ArrayList<>();
        for (Secret.Tag newTag : tags) {
            existing.removeIf(t -> t.key().equals(newTag.key()));
            existing.add(newTag);
        }
        secret.setTags(existing);
        persist(secret, region);
    }

    public void untagResource(String secretId, List<String> tagKeys, String region) {
        Secret secret = resolveSecret(secretId, region);
        throwIfPendingDeletion(secret);
        throwIfReplica(secret);

        List<Secret.Tag> existing = secret.getTags() != null ? new ArrayList<>(secret.getTags()) : new ArrayList<>();
        existing.removeIf(t -> tagKeys.contains(t.key()));
        secret.setTags(existing);
        persist(secret, region);
    }

    public Secret putResourcePolicy(String secretId, String resourcePolicy, String region) {
        Secret secret = resolveSecret(secretId, region);
        throwIfPendingDeletion(secret);
        throwIfReplica(secret);
        secret.setResourcePolicy(resourcePolicy);
        persist(secret, region);
        LOG.infov("Put resource policy on secret: {0}", secret.getName());
        return secret;
    }

    // Real AWS rejects GetResourcePolicy on a secret in its recovery window the same way it
    // rejects the mutating ops; the terraform provider matches that InvalidRequestException's
    // "marked for deletion" message to treat the policy as gone, so the guard applies here too.
    public Secret getResourcePolicy(String secretId, String region) {
        Secret secret = resolveSecret(secretId, region);
        throwIfPendingDeletion(secret);
        return secret;
    }

    public Secret deleteResourcePolicy(String secretId, String region) {
        Secret secret = resolveSecret(secretId, region);
        throwIfPendingDeletion(secret);
        throwIfReplica(secret);
        secret.setResourcePolicy(null);
        persist(secret, region);
        LOG.infov("Deleted resource policy on secret: {0}", secret.getName());
        return secret;
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
        throwIfPendingDeletion(secret);
        throwIfReplica(secret);

        synchronized (lockFor(secret.getArn())) {
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
                            .formatted(versionStage, currentVersionId), 400);
                } else if (!Objects.equals(currentVersionId, removeFromVersionId)) {
                    throw new AwsException("InvalidParameterException",
                            ("When you move staging label %s, if you specify RemoveFromVersionId, it must be set to the "
                                + "version that currently has the staging label %s.")
                            .formatted(versionStage, currentVersionId), 400);
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

            persist(secret, region);

            return secret;
        }
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

    /**
     * A secret found by {@link #resolveSecret} with {@code deletedDate} set is on the
     * recovery-window path (still in {@code store}, still restorable) - {@link
     * #deleteSecret} force-deletes by removing the entry from {@code store} outright, so a
     * fully, permanently gone secret never reaches this check; {@code resolveSecret} throws
     * ResourceNotFoundException for that case on its own. Real AWS's error for the
     * recoverable case is InvalidRequestException, matching what {@code batchGetSecretValue}
     * already does correctly - the other call sites threw ResourceNotFoundException instead.
     */
    /**
     * AWS accepts ASCII letters, numbers and {@code /_+=.@-} in a secret name, up to 512 chars.
     *
     * <p>Enforced at the wire layer rather than here on purpose. The service API is also floci's
     * internal one: RDS creates {@code rds!db-...} secrets, whose {@code !} the public constraint
     * forbids, and the ARN resolver deliberately supports names containing colons for secrets
     * persisted by earlier versions. Validating here would make those unrepresentable.
     */
    public static void validateSecretName(String name) {
        if (name == null || name.isEmpty() || name.length() > 512) {
            throw new AwsException("InvalidParameterException",
                    "Invalid name. Must be a minimum of 1 and a maximum of 512 characters.", 400);
        }
        if (!SECRET_NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidParameterException",
                    "Invalid name. Must be a valid name containing alphanumeric characters, "
                            + "or any of the following: -/_+=.@", 400);
        }
    }

    /**
     * Checks that a caller-supplied KMS key exists and can actually encrypt.
     *
     * <p>Floci stores secret values in the clear - encrypting them with the emulator's own KMS
     * would protect nothing while making the store unreadable for debugging - so this validates
     * the key rather than using it. That covers the behaviour a caller can observe: AWS rejects a
     * key that is missing, disabled, or on its way out, and code under test should see the same
     * rejection here.
     *
     * <p>The AWS-managed {@code aws/secretsmanager} key is exempt: AWS creates it on demand, so
     * looking it up would fail on an emulator where nobody has created it.
     */
    private void validateKmsKey(String kmsKeyId, String region) {
        if (kmsService == null || kmsKeyId == null || kmsKeyId.isBlank()
                || AWS_MANAGED_KEY.equals(kmsKeyId) || ("alias/" + AWS_MANAGED_KEY).equals(kmsKeyId)) {
            return;
        }

        KmsKey key;
        try {
            key = kmsService.describeKey(kmsKeyId, region);
        } catch (AwsException e) {
            throw new AwsException("InvalidParameterException",
                    "You can't access the KMS key " + kmsKeyId + " in this Region.", 400);
        }
        if (key != null && (!key.isEnabled() || PENDING_DELETION_STATE.equals(key.getKeyState()))) {
            throw new AwsException("EncryptionFailure",
                    "Secrets Manager can't encrypt the protected secret text using the provided KMS key. "
                            + "Check that the KMS key is available, enabled, and not in an invalid state.", 400);
        }
    }

    /** AWS: "Either SecretString or SecretBinary must have a value, but not both." */
    private static void throwIfBothSecretValues(String secretString, String secretBinary) {
        if (secretString != null && secretBinary != null) {
            throw new AwsException("InvalidParameterException",
                    "You can't specify both SecretString and SecretBinary in the same request.", 400);
        }
    }

    /**
     * A secret another AWS service owns is managed through that service, so the public API refuses
     * to change it.
     *
     * <p>Applied at the wire layer, not here, for the same reason as {@link #validateSecretName}:
     * this service API is also floci's internal one, and the owning service has to keep managing
     * its own secret. RDS re-encrypts its managed master user secret in place on ModifyDBCluster
     * by calling {@link #updateSecret}; enforcing this here would break that.
     */
    public static void throwIfServiceManaged(Secret secret) {
        if (secret.getOwningService() != null) {
            throw new AwsException("InvalidRequestException",
                    "The secret is managed by another service, and you must use that service to update it.", 400);
        }
    }

    private void throwIfPendingDeletion(Secret secret) {
        if (secret.getDeletedDate() != null) {
            throw new AwsException("InvalidRequestException",
                    "You can't perform this operation on the secret because it was marked for deletion.", 400);
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
            //    ARN format: arn:<partition>:secretsmanager:<region>:<account>:secret:<name>
            //    Parsed rather than matched against a literal prefix, because the ARN carries the
            //    region's own partition and a pinned "arn:aws:" refused a GovCloud or China
            //    secret its own ARN. The name comes off the parsed resource without a second
            //    split: a secret name may itself contain colons.
            AwsArnUtils.Arn parsedArn = parseOrNull(secretId);
            String secretResourcePrefix = "secret:";
            if (parsedArn != null
                    && "secretsmanager".equals(parsedArn.service())
                    && parsedArn.resource().startsWith(secretResourcePrefix)) {
                String arnRegion = parsedArn.region();
                String nameFromArn = parsedArn.resource().substring(secretResourcePrefix.length());
                Secret byName = store.get(regionKey(arnRegion, nameFromArn)).orElse(null);
                if (byName != null) {
                    return byName;
                }
            }

            // 3. The scans above resolve the account from the in-flight request, so a background
            //    worker looking up a full ARN would miss a secret that is not in the default
            //    account. The ARN names its own account, so address that one directly.
            if (parsedArn != null) {
                Secret inOwningAccount = findByArnInAccount(secretId, parsedArn);
                if (inOwningAccount != null) {
                    return inOwningAccount;
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

    /** {@link AwsArnUtils#parse} result, or {@code null} when the value is not an ARN. */
    private static AwsArnUtils.Arn parseOrNull(String value) {
        try {
            return AwsArnUtils.parse(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Secret secret : store.scan(k -> true)) {
            String arn = secret.getArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            Map<String, String> tags = new LinkedHashMap<>();
            if (secret.getTags() != null) {
                for (Secret.Tag tag : secret.getTags()) {
                    tags.put(tag.key(), tag.value() != null ? tag.value() : "");
                }
            }
            resources.add(new ExplorerResource(
                    arn, "secretsmanager:secret", "secretsmanager",
                    parsed.region(), parsed.accountId(),
                    secret.getCreatedDate() != null ? secret.getCreatedDate() : Instant.now(),
                    tags));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("secretsmanager:secret", "secretsmanager", true));
    }
}
