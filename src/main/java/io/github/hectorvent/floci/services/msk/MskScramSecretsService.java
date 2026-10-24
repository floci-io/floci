package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsKeyUsage;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskScramAssociations;
import io.github.hectorvent.floci.services.msk.model.ScramSecretAssociation;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MskScramSecretsService {

    private static final Logger LOG = Logger.getLogger(MskScramSecretsService.class);
    private static final int MAX_PAGE = 100;

    private final AccountAwareStorageBackend<MskScramAssociations> storage;
    private final EmulatorConfig config;
    private final MskService mskService;
    private final RedpandaManager redpandaManager;
    private final SecretsManagerService secretsManagerService;
    private final KmsService kmsService;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService reconciler = Executors.newSingleThreadScheduledExecutor();
    private final ThreadPoolExecutor reconciliationWorkers = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128));
    private final Set<String> pendingReconciliations = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> clusterLocks = new ConcurrentHashMap<>();

    private final Map<String, ClusterReconciliationFailure> clusterReconciliationFailures = new ConcurrentHashMap<>();
    private record ClusterReconciliationFailure(Class<?> type, String message) {}
    private final Map<ReconciliationKey, ReconciliationFailure> secretReconciliationFailures = new ConcurrentHashMap<>();
    private record ReconciliationKey(String clusterArn, String secretArn) {}
    private record ReconciliationFailure(String errorCode, String message) {}

    @Inject
    public MskScramSecretsService(StorageFactory storageFactory, EmulatorConfig config, MskService mskService,
                                  RedpandaManager redpandaManager, SecretsManagerService secretsManagerService,
                                  KmsService kmsService, ObjectMapper objectMapper) {
        this.storage = storageFactory.create("msk", "msk-scram-associations.json",
                new TypeReference<Map<String, MskScramAssociations>>() {});
        this.config = config;
        this.mskService = mskService;
        this.redpandaManager = redpandaManager;
        this.secretsManagerService = secretsManagerService;
        this.kmsService = kmsService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        long intervalMs = config.services().msk().scramReconciliationIntervalMs();
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("floci.services.msk.scram-reconciliation-interval-ms must be greater than zero.");
        }
        reconciler.scheduleWithFixedDelay(() -> {
            try {
                scheduleScramReconciliation();
            } catch (RuntimeException e) {
                LOG.error("MSK SCRAM reconciliation failed; retrying on the next scheduled run", e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        reconciler.shutdown();
        reconciliationWorkers.shutdownNow();
    }

    private Object clusterLock(String clusterArn) {
        return clusterLocks.computeIfAbsent(String.valueOf(clusterArn), ignored -> new Object());
    }

    private void scheduleScramReconciliation() {
        int deferredClusters = 0;
        for (AccountAwareStorageBackend.AccountEntry<MskScramAssociations> entry : storage.scanAllAccountEntries(key -> true)) {
            String clusterArn = entry.key();
            if (!pendingReconciliations.add(clusterArn)) {
                continue;
            }
            try {
                reconciliationWorkers.execute(() -> runClusterReconciliation(entry));
            } catch (RejectedExecutionException e) {
                pendingReconciliations.remove(clusterArn);
                if (!reconciliationWorkers.isShutdown()) {
                    deferredClusters++;
                }
            }
        }
        if (deferredClusters > 0) {
            LOG.warnv("MSK SCRAM reconciliation workers are full; retrying {0} clusters on the next scheduled run",
                deferredClusters);
        }
    }

    private void runClusterReconciliation(AccountAwareStorageBackend.AccountEntry<MskScramAssociations> entry) {
        String clusterArn = entry.key();
        try {
            reconcileCluster(clusterArn, entry.accountId());
            clusterReconciliationFailures.remove(clusterArn);
        } catch (RuntimeException e) {
            ClusterReconciliationFailure failure = new ClusterReconciliationFailure(e.getClass(), e.getMessage());
            if (!failure.equals(clusterReconciliationFailures.put(clusterArn, failure))) {
                LOG.errorv(e, "MSK SCRAM reconciliation failed for cluster {0}; retrying on the next scheduled run",
                    clusterArn);
            } else {
                LOG.debugv(e, "MSK SCRAM reconciliation failed for cluster {0}; retrying on the next scheduled run",
                    clusterArn);
            }
        } finally {
            pendingReconciliations.remove(clusterArn);
        }
    }

    void reconcileCluster(String clusterArn, String accountId) {
        synchronized (clusterLock(clusterArn)) {
            Optional<MskCluster> clusterGet = mskService.getClusterForAccount(accountId, clusterArn);
            if (clusterGet.isEmpty()) {
                storage.deleteForAccount(accountId, clusterArn);
                secretReconciliationFailures.keySet().removeIf(key -> key.clusterArn().equals(clusterArn));
            } else {
                reconcileScramSecretsForCluster(clusterGet.get(), clusterArn);
            }
        }
    }

    private void reconcileScramSecretsForCluster(MskCluster cluster, String clusterArn) {
        if (mskService.isServerless(cluster) || cluster.getState() != ClusterState.ACTIVE) {
            return;
        }
        MskScramAssociations clusterAssociations = associationsFor(clusterArn);
        for (String secretArn : new ArrayList<>(clusterAssociations.getAssociations().keySet())) {
            ReconciliationKey reconciliationKey = new ReconciliationKey(clusterArn, secretArn);
            try {
                reconcileScramSecret(secretArn, clusterAssociations, cluster);
                secretReconciliationFailures.remove(reconciliationKey);
            } catch (AwsException e) {
                ReconciliationFailure failure = new ReconciliationFailure(e.getErrorCode(), e.getMessage());
                if (!failure.equals(secretReconciliationFailures.put(reconciliationKey, failure))) {
                    LOG.warnv("Could not reconcile MSK SCRAM secret {0} for cluster {1}: {2}",
                        secretArn, clusterArn, e.getErrorCode());
                } else {
                    LOG.debugv("Could not reconcile MSK SCRAM secret {0} for cluster {1}: {2}",
                        secretArn, clusterArn, e.getErrorCode());
                }
            }
        }
    }

    private void reconcileScramSecret(String secretArn, MskScramAssociations clusterAssociations, MskCluster cluster) {
        ScramSecretAssociation association = clusterAssociations.getAssociations().get(secretArn);
        if (association == null) {
            // Should never happen
            throw new AwsException("InternalServerErrorException", "SCRAM association is missing during reconciliation.", 500);
        }
        if (association.isDisassociating()) {
            // Recover if disassociation was interrupted
            removeScramAssociation(cluster, clusterAssociations, secretArn);
            return;
        }
        Optional<Secret> secret = describeSecret(secretArn);
        if (secret.isEmpty() || secret.get().getDeletedDate() != null) {
            // Revoke credentials for secret that are deleted or scheduled for deletion, but keep the association
            revokeScramCredentials(cluster, clusterAssociations, association);
            return;
        }
        
        SecretVersion secretVersion = getCurrentSecretVersion(secret.get());
        boolean versionIdChanged = !Objects.equals(secretVersion.getVersionId(), association.getAppliedVersionId());
        boolean containerIdChanged = !Objects.equals(cluster.getContainerId(), association.getAppliedContainerId());
        // Reapply if the secret or cluster container has changed
        // Reapply if credentials where revoked, which can happen if the secret was deleted and then restored
        if (association.isCredentialsRevoked() || versionIdChanged || containerIdChanged) {
            applyScramCredentials(cluster, clusterAssociations, association, secretVersion);
        }
    }

    private void revokeScramCredentials(MskCluster cluster, MskScramAssociations state, ScramSecretAssociation association) {
        if (association.isCredentialsRevoked()) {
            // Skip repeated delete calls to the cluster
            return;
        }
        deleteScramUser(cluster, state, association);
        association.setCredentialsRevoked(true);
        putAssociations(cluster.getClusterArn(), state);
    }
    
    public List<Map<String, String>> batchAssociateScramSecret(String clusterArn, List<String> secretArns) {
        synchronized (clusterLock(clusterArn)) {
            MskCluster cluster = scramCluster(clusterArn);
            validateSecretArns(secretArns);
            if (new HashSet<>(secretArns).size() != secretArns.size()) {
                throw new AwsException("BadRequestException", "secretArnList must not contain duplicate ARNs.", 400,
                        Map.of("invalidParameter", "secretArnList"));
            }
            MskScramAssociations state = associationsFor(clusterArn);
            List<Map<String, String>> unprocessedScramSecrets = new ArrayList<>();
            for (String secretArn : secretArns) {
                boolean newAssociation = !state.getAssociations().containsKey(secretArn);
                try {
                    Secret secret = validateSecretArn(clusterArn, secretArn);
                    if (!newAssociation) {
                        throw new AwsException("InvalidSecretArn", "The provided secret ARN is invalid.", 400);
                    }
                    validateSecretEncryption(secret);
                    // Amazon MSK reads only the AWSCURRENT version of an associated secret
                    SecretVersion secretVersion = getCurrentSecretVersion(secret);
                    ScramSecretAssociation association = state.getAssociations()
                            .computeIfAbsent(secretArn, ignored -> new ScramSecretAssociation());
                    applyScramCredentials(cluster, state, association, secretVersion);
                } catch (AwsException e) {
                    if (newAssociation && state.getAssociations().remove(secretArn) != null) {
                        putAssociations(clusterArn, state);
                    }
                    unprocessedScramSecrets.add(scramFailure(secretArn, e));
                }
            }
            return unprocessedScramSecrets;
        }
    }

    public PaginatedResult<String> listScramSecrets(String clusterArn, Integer maxResults, String nextToken) {
        synchronized (clusterLock(clusterArn)) {
            scramCluster(clusterArn);
            return Pagination.paginate(new ArrayList<>(associationsFor(clusterArn).getAssociations().keySet()),
                    arn -> arn, maxResults, nextToken, MAX_PAGE, "BadRequestException");
        }
    }

    public List<Map<String, String>> batchDisassociateScramSecret(String clusterArn, List<String> secretArns) {
        synchronized (clusterLock(clusterArn)) {
            MskCluster cluster = scramCluster(clusterArn);
            validateSecretArns(secretArns);
            MskScramAssociations state = associationsFor(clusterArn);
            List<Map<String, String>> unprocessedScramSecrets = new ArrayList<>();
            for (String secretArn : secretArns) {
                try {
                    if (!state.getAssociations().containsKey(secretArn)) {
                        throw new AwsException("InvalidSecretArn", "The provided secret ARN is invalid.", 400);
                    }
                    removeScramAssociation(cluster, state, secretArn);
                } catch (AwsException e) {
                    unprocessedScramSecrets.add(scramFailure(secretArn, e));
                }
            }
            return unprocessedScramSecrets;
        }
    }

    private MskCluster scramCluster(String clusterArn) {
        MskCluster cluster = mskService.describeCluster(clusterArn);
        if (mskService.isServerless(cluster)) {
            throw new AwsException("BadRequestException",
                    "SCRAM secrets are not supported for serverless clusters.", 400,
                    Map.of("invalidParameter", "clusterArn"));
        }
        return cluster;
    }

    private void validateSecretArns(List<String> secretArns) {
        if (secretArns == null || secretArns.isEmpty() || secretArns.size() > 10
                || secretArns.stream().anyMatch(arn -> arn == null || arn.isBlank())) {
            throw new AwsException("BadRequestException",
                    "secretArnList must contain between 1 and 10 secret ARNs.", 400,
                    Map.of("invalidParameter", "secretArnList"));
        }
    }

    private Secret validateSecretArn(String clusterArn, String secretArn) {
        if (!AwsArnUtils.isArn(secretArn)) {
            throw new AwsException("BadRequestException", "Invalid secret ARN.", 400,
                    Map.of("invalidParameter", "secretArnList"));
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(secretArn);
        AwsArnUtils.Arn owner = AwsArnUtils.parse(clusterArn);
        if (!"secretsmanager".equals(arn.service()) || !arn.resource().startsWith("secret:")
                || !owner.partition().equals(arn.partition()) || !owner.region().equals(arn.region())
                || !owner.accountId().equals(arn.accountId())) {
            throw new AwsException("BadRequestException",
                    "Secret must belong to the cluster's account, region and partition.", 400,
                    Map.of("invalidParameter", "secretArnList"));
        }
        String secretName = arn.resource().substring("secret:".length());
        if (!secretName.startsWith("AmazonMSK_")) {
            throw new AwsException("BadRequestException",
                    "The name of secrets associated with an Amazon MSK cluster must have the prefix AmazonMSK_.", 400,
                    Map.of("invalidParameter", "secretArnList"));
        }
        Secret secret = secretsManagerService.describeSecret(secretArn, arn.region());
        if (!secretArn.equals(secret.getArn())) {
            throw new AwsException("InvalidSecretArn", "Amazon MSK wasn't able to access the provided secret.", 400);
        }
        return secret;
    }

    private void validateSecretEncryption(Secret secret) {
        String region = AwsArnUtils.parse(secret.getArn()).region();
        String keyId = secret.getKmsKeyId();
        boolean hasAliasPrefix = keyId != null && keyId.startsWith("alias/");
        boolean hasAliasArn = AwsArnUtils.isArnFor(keyId, "kms") && AwsArnUtils.parse(keyId).resource().startsWith("alias/");
        if (hasAliasPrefix || hasAliasArn) {
            throw new AwsException("BadRequestException",
                    "SCRAM secrets must reference a KMS key ID or key ARN instead of an alias.", 400,
                    Map.of("invalidParameter", "secretArnList"));
        }
        
        if (keyId != null && !keyId.isBlank()) {
            KmsKey key = kmsService.describeKey(keyId, region);
            if ("CUSTOMER".equals(key.getKeyManager())
                    && key.getKeySpec() == KmsKeySpec.SYMMETRIC_DEFAULT
                    && key.getKeyUsage() == KmsKeyUsage.ENCRYPT_DECRYPT) {
                return;
            }
        }
        throw new AwsException("BadRequestException",
                "SCRAM secrets must be encrypted with a customer-managed symmetric KMS key.", 400,
                Map.of("invalidParameter", "secretArnList"));
    }

    private Map<String, String> scramFailure(String secretArn, AwsException error) {
        return Map.of("secretArn", secretArn, "errorCode", error.getErrorCode(), "errorMessage", error.getMessage());
    }

    private MskScramAssociations associationsFor(String clusterArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(clusterArn);
        return storage.getForAccount(arn.accountId(), clusterArn).orElseGet(MskScramAssociations::new);
    }

    private void putAssociations(String clusterArn, MskScramAssociations associations) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(clusterArn);
        if (associations.getAssociations().isEmpty()) {
            storage.deleteForAccount(arn.accountId(), clusterArn);
        } else {
            storage.putForAccount(arn.accountId(), clusterArn, associations);
        }
    }

    private void applyScramCredentials(MskCluster cluster, MskScramAssociations state,
                                       ScramSecretAssociation association, SecretVersion secretValue) {
        ScramCredentials credentials = readScramCredentials(secretValue.getSecretString());
        boolean ownershipChanged = !Objects.equals(association.getUsername(), credentials.username());
        if (ownershipChanged) {
            deleteScramUser(cluster, state, association);
            association.setUsername(credentials.username());
            // Persist ownership changes before updating the broker; mark the version as applied only after success.
            putAssociations(cluster.getClusterArn(), state);
        }
        if (!config.services().msk().mock()) {
            redpandaManager.createUser(cluster, credentials.username(), credentials.password());
        }
        association.setAppliedVersionId(secretValue.getVersionId());
        association.setAppliedContainerId(cluster.getContainerId());
        association.setCredentialsRevoked(false);
        putAssociations(cluster.getClusterArn(), state);
    }

    private void removeScramAssociation(MskCluster cluster, MskScramAssociations state, String secretArn) {
        ScramSecretAssociation association = state.getAssociations().get(secretArn);
        if (association == null) {
            return;
        }
        if (!association.isDisassociating()) {
            association.setDisassociating(true);
            // Persist the removal intent before deleting broker credentials so a failure does not lose it.
            putAssociations(cluster.getClusterArn(), state);
        }
        deleteScramUser(cluster, state, association);
        state.getAssociations().remove(secretArn);
        putAssociations(cluster.getClusterArn(), state);
        secretReconciliationFailures.remove(new ReconciliationKey(cluster.getClusterArn(), secretArn));
    }

    private void deleteScramUser(MskCluster cluster, MskScramAssociations state, ScramSecretAssociation association) {
        String username = association.getUsername();
        // Revoked credentials have already been deleted
        if (username != null && !association.isCredentialsRevoked()) {
            if (!config.services().msk().mock()) {
                redpandaManager.deleteUser(cluster, username);
            }
            // Allow a user existing in multiple secrets to be reconciled again
            invalidateUserAssociations(state, username);
        }
    }

    private void invalidateUserAssociations(MskScramAssociations state, String username) {
        for (ScramSecretAssociation association : state.getAssociations().values()) {
            if (username.equals(association.getUsername())) {
                association.setAppliedVersionId(null);
                association.setAppliedContainerId(null);
            }
        }
    }

    private Optional<Secret> describeSecret(String secretArn) {
        try {
            Secret secret = secretsManagerService.describeSecret(secretArn, AwsArnUtils.parse(secretArn).region());
            return Optional.of(secret);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private SecretVersion getCurrentSecretVersion(Secret secret) {
        if (secret.getDeletedDate() != null) {
            throw new AwsException("InternalServerError",
                SecretsManagerService.SCHEDULED_DELETION_ERROR_MESSAGE, 500);
        }
        if (secret.getVersions() != null) {
            for (SecretVersion version : secret.getVersions().values()) {
                if (version.getVersionStages() != null && version.getVersionStages().contains("AWSCURRENT")) {
                    return version;
                }
            }
        }
        throw new AwsException("ResourceNotFoundException",
            "Secrets Manager can't find the specified secret value for staging label: AWSCURRENT", 400);
    }

    record ScramCredentials(String username, String password) {
    }

    ScramCredentials readScramCredentials(String secretString) {
        if (secretString == null) {
            throw new AwsException("InternalServerError",
                    "The request could not be processed because of an internal error. Try again.", 500);
        }
        JsonNode credentials;
        try {
            credentials = objectMapper.readTree(secretString);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidSecretArn", "The provided secret has an invalid schema.", 400);
        }
        if (credentials != null && credentials.isNull()) {
            throw new AwsException("InternalServerError",
                "The request could not be processed because of an internal error. Try again.", 500);
        }
        if (credentials == null || !credentials.isObject()) {
            throw new AwsException("InvalidSecretArn", "The provided secret has an invalid schema.", 400);
        }
        JsonNode username = credentials.path("username");
        if ((!username.isTextual() && !username.isNumber()) || username.asText().isBlank()) {
            throw new AwsException("InvalidSecretArn", "The provided secret has an invalid schema.", 400);
        }
        JsonNode password = credentials.path("password");
        if ((!password.isTextual() && !password.isBoolean()) || password.asText().isBlank()) {
            throw new AwsException("InvalidSecretArn", "The provided secret has an invalid schema.", 400);
        }
        return new ScramCredentials(username.asText(), password.asText());
    }
}
