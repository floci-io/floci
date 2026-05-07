package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.model.*;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class BackupService {

    private static final Logger LOG = Logger.getLogger(BackupService.class);

    private static final List<String> SUPPORTED_RESOURCE_TYPES = List.of(
            "S3", "RDS", "DynamoDB", "EFS", "EC2", "EBS",
            "Aurora", "DocumentDB", "Neptune", "FSx", "VirtualMachine"
    );

    private final StorageBackend<String, BackupVault>     vaultStore;
    private final StorageBackend<String, BackupPlan>      planStore;
    private final StorageBackend<String, BackupSelection> selectionStore;
    private final StorageBackend<String, BackupJob>       jobStore;
    private final StorageBackend<String, RecoveryPoint>   recoveryStore;

    private final RegionResolver regionResolver;
    private final int jobCompletionDelaySeconds;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backup-job-scheduler");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public BackupService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this.vaultStore     = storageFactory.create("backup", "backup-vaults.json",     new TypeReference<>() {});
        this.planStore      = storageFactory.create("backup", "backup-plans.json",      new TypeReference<>() {});
        this.selectionStore = storageFactory.create("backup", "backup-selections.json", new TypeReference<>() {});
        this.jobStore       = storageFactory.create("backup", "backup-jobs.json",       new TypeReference<>() {});
        this.recoveryStore  = storageFactory.create("backup", "backup-recovery-points.json", new TypeReference<>() {});
        this.regionResolver = regionResolver;
        this.jobCompletionDelaySeconds = config.services().backup().jobCompletionDelaySeconds();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ── Vault ──────────────────────────────────────────────────────────────────

    public BackupVault createBackupVault(String vaultName, String encryptionKeyArn,
                                         String creatorRequestId, Map<String, String> tags,
                                         String region) {
        String key = vaultKey(region, vaultName);
        if (vaultStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Backup vault already exists: " + vaultName, 400);
        }
        BackupVault vault = new BackupVault();
        vault.setBackupVaultName(vaultName);
        vault.setBackupVaultArn(regionResolver.buildArn("backup", region, "backup-vault:" + vaultName));
        vault.setEncryptionKeyArn(encryptionKeyArn);
        vault.setCreationDate(Instant.now().getEpochSecond());
        vault.setCreatorRequestId(creatorRequestId);
        vault.setNumberOfRecoveryPoints(0);
        vault.setTags(tags);
        vaultStore.put(key, vault);
        LOG.infov("Created backup vault {0} in {1}", vaultName, region);
        return vault;
    }

    public BackupVault describeBackupVault(String vaultName, String region) {
        return vaultStore.get(vaultKey(region, vaultName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup vault not found: " + vaultName, 404));
    }

    public void deleteBackupVault(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (vault.getNumberOfRecoveryPoints() > 0) {
            throw new AwsException("InvalidRequestException",
                    "Non-empty backup vault cannot be deleted: " + vaultName, 400);
        }
        vaultStore.delete(vaultKey(region, vaultName));
    }

    public List<BackupVault> listBackupVaults(String region) {
        String prefix = region + ":";
        return vaultStore.scan(k -> k.startsWith(prefix));
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    public BackupPlan createBackupPlan(String planName, List<BackupRule> rules,
                                       String creatorRequestId, String region) {
        String planId = UUID.randomUUID().toString();
        BackupPlan plan = new BackupPlan();
        plan.setBackupPlanId(planId);
        plan.setBackupPlanArn(regionResolver.buildArn("backup", region, "backup-plan:" + planId));
        plan.setBackupPlanName(planName);
        plan.setCreationDate(Instant.now().getEpochSecond());
        plan.setVersionId(shortId());
        assignRuleIds(rules);
        plan.setRules(rules);
        planStore.put(planId, plan);
        return plan;
    }

    public BackupPlan getBackupPlan(String planId) {
        return planStore.get(planId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup plan not found: " + planId, 404));
    }

    public BackupPlan updateBackupPlan(String planId, String planName, List<BackupRule> rules) {
        BackupPlan plan = getBackupPlan(planId);
        if (planName != null) {
            plan.setBackupPlanName(planName);
        }
        assignRuleIds(rules);
        plan.setRules(rules);
        plan.setVersionId(shortId());
        planStore.put(planId, plan);
        return plan;
    }

    public void deleteBackupPlan(String planId) {
        getBackupPlan(planId);
        long selectionCount = selectionStore.scan(k -> true).stream()
                .filter(s -> planId.equals(s.getBackupPlanId()))
                .count();
        if (selectionCount > 0) {
            throw new AwsException("InvalidRequestException",
                    "Backup plan has active selections and cannot be deleted", 400);
        }
        planStore.delete(planId);
    }

    public List<BackupPlan> listBackupPlans() {
        return planStore.scan(k -> true);
    }

    // ── Selection ──────────────────────────────────────────────────────────────

    public BackupSelection createBackupSelection(String planId, String selectionName,
                                                  String iamRoleArn, List<String> resources,
                                                  List<String> notResources, String creatorRequestId) {
        getBackupPlan(planId);
        String selectionId = UUID.randomUUID().toString();
        BackupSelection selection = new BackupSelection();
        selection.setSelectionId(selectionId);
        selection.setSelectionName(selectionName);
        selection.setBackupPlanId(planId);
        selection.setIamRoleArn(iamRoleArn);
        selection.setResources(resources);
        selection.setNotResources(notResources);
        selection.setCreationDate(Instant.now().getEpochSecond());
        selection.setCreatorRequestId(creatorRequestId);
        selectionStore.put(selectionId, selection);
        return selection;
    }

    public BackupSelection getBackupSelection(String planId, String selectionId) {
        BackupSelection sel = selectionStore.get(selectionId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup selection not found: " + selectionId, 404));
        if (!planId.equals(sel.getBackupPlanId())) {
            throw new AwsException("ResourceNotFoundException", "Backup selection not found in plan: " + planId, 404);
        }
        return sel;
    }

    public void deleteBackupSelection(String planId, String selectionId) {
        getBackupSelection(planId, selectionId);
        selectionStore.delete(selectionId);
    }

    public List<BackupSelection> listBackupSelections(String planId) {
        return selectionStore.scan(k -> true).stream()
                .filter(s -> planId.equals(s.getBackupPlanId()))
                .toList();
    }

    // ── Job ────────────────────────────────────────────────────────────────────

    public BackupJob startBackupJob(String vaultName, String resourceArn, String iamRoleArn,
                                     Lifecycle lifecycle, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);

        String jobId = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();

        BackupJob job = new BackupJob();
        job.setBackupJobId(jobId);
        job.setBackupVaultName(vaultName);
        job.setBackupVaultArn(vault.getBackupVaultArn());
        job.setResourceArn(resourceArn);
        job.setResourceType(inferResourceType(resourceArn));
        job.setIamRoleArn(iamRoleArn);
        job.setState("CREATED");
        job.setPercentDone("0.0");
        job.setCreationDate(now);
        job.setExpectedCompletionDate(now + jobCompletionDelaySeconds);
        job.setStartBy(now + 3600L);
        job.setAccountId(regionResolver.getAccountId());
        jobStore.put(jobId, job);

        scheduler.schedule(() -> transitionJob(jobId, vaultName, region), 1, TimeUnit.SECONDS);
        scheduler.schedule(() -> completeJob(jobId, vaultName, region), jobCompletionDelaySeconds, TimeUnit.SECONDS);

        return job;
    }

    public BackupJob describeBackupJob(String jobId) {
        return jobStore.get(jobId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup job not found: " + jobId, 404));
    }

    public void stopBackupJob(String jobId) {
        BackupJob job = describeBackupJob(jobId);
        String state = job.getState();
        if ("COMPLETED".equals(state) || "ABORTED".equals(state) || "FAILED".equals(state)) {
            throw new AwsException("InvalidRequestException",
                    "Job cannot be stopped in state: " + state, 400);
        }
        job.setState("ABORTING");
        job.setStatusMessage("Job stop requested");
        jobStore.put(jobId, job);
        scheduler.schedule(() -> abortJob(jobId), 1, TimeUnit.SECONDS);
    }

    public List<BackupJob> listBackupJobs(String byVaultName, String byState,
                                           String byResourceArn, String byResourceType) {
        return jobStore.scan(k -> true).stream()
                .filter(j -> byVaultName == null || byVaultName.equals(j.getBackupVaultName()))
                .filter(j -> byState == null || byState.equals(j.getState()))
                .filter(j -> byResourceArn == null || byResourceArn.equals(j.getResourceArn()))
                .filter(j -> byResourceType == null || byResourceType.equals(j.getResourceType()))
                .toList();
    }

    // ── Recovery Point ─────────────────────────────────────────────────────────

    public RecoveryPoint describeRecoveryPoint(String vaultName, String recoveryPointArn, String region) {
        describeBackupVault(vaultName, region);
        return recoveryStore.get(recoveryPointArn)
                .filter(rp -> vaultName.equals(rp.getBackupVaultName()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Recovery point not found: " + recoveryPointArn, 404));
    }

    public List<RecoveryPoint> listRecoveryPointsByBackupVault(String vaultName, String region) {
        describeBackupVault(vaultName, region);
        return recoveryStore.scan(k -> true).stream()
                .filter(rp -> vaultName.equals(rp.getBackupVaultName()))
                .toList();
    }

    public void deleteRecoveryPoint(String vaultName, String recoveryPointArn, String region) {
        RecoveryPoint rp = describeRecoveryPoint(vaultName, recoveryPointArn, region);
        recoveryStore.delete(recoveryPointArn);
        decrementVaultCount(vaultName, region);
    }

    // ── Tags ───────────────────────────────────────────────────────────────────

    public Map<String, String> listTags(String resourceArn) {
        return findTagsByArn(resourceArn);
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        applyTags(resourceArn, tags);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        removeTags(resourceArn, tagKeys);
    }

    // ── Supported resource types ───────────────────────────────────────────────

    public List<String> getSupportedResourceTypes() {
        return SUPPORTED_RESOURCE_TYPES;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void transitionJob(String jobId, String vaultName, String region) {
        jobStore.get(jobId).ifPresent(job -> {
            if ("CREATED".equals(job.getState())) {
                job.setState("RUNNING");
                job.setPercentDone("50.0");
                jobStore.put(jobId, job);
            }
        });
    }

    private void completeJob(String jobId, String vaultName, String region) {
        jobStore.get(jobId).ifPresent(job -> {
            if ("RUNNING".equals(job.getState())) {
                long now = Instant.now().getEpochSecond();
                String rpArn = regionResolver.buildArn("backup", region,
                        "recovery-point:" + UUID.randomUUID());

                job.setState("COMPLETED");
                job.setPercentDone("100.0");
                job.setCompletionDate(now);
                job.setRecoveryPointArn(rpArn);
                job.setBackupSizeInBytes(0L);
                job.setBytesTransferred(0L);
                jobStore.put(jobId, job);

                RecoveryPoint rp = new RecoveryPoint();
                rp.setRecoveryPointArn(rpArn);
                rp.setBackupVaultName(vaultName);
                rp.setBackupVaultArn(job.getBackupVaultArn());
                rp.setResourceArn(job.getResourceArn());
                rp.setResourceType(job.getResourceType());
                rp.setIamRoleArn(job.getIamRoleArn());
                rp.setStatus("COMPLETED");
                rp.setCreationDate(job.getCreationDate());
                rp.setCompletionDate(now);
                rp.setBackupSizeInBytes(0L);
                rp.setStorageClass("WARM");
                rp.setEncrypted(false);
                recoveryStore.put(rpArn, rp);

                incrementVaultCount(vaultName, region);
                LOG.infov("Backup job {0} completed, recovery point: {1}", jobId, rpArn);
            }
        });
    }

    private void abortJob(String jobId) {
        jobStore.get(jobId).ifPresent(job -> {
            if ("ABORTING".equals(job.getState())) {
                job.setState("ABORTED");
                job.setCompletionDate(Instant.now().getEpochSecond());
                jobStore.put(jobId, job);
            }
        });
    }

    private void incrementVaultCount(String vaultName, String region) {
        vaultStore.get(vaultKey(region, vaultName)).ifPresent(vault -> {
            vault.setNumberOfRecoveryPoints(vault.getNumberOfRecoveryPoints() + 1);
            vaultStore.put(vaultKey(region, vaultName), vault);
        });
    }

    private void decrementVaultCount(String vaultName, String region) {
        vaultStore.get(vaultKey(region, vaultName)).ifPresent(vault -> {
            vault.setNumberOfRecoveryPoints(Math.max(0, vault.getNumberOfRecoveryPoints() - 1));
            vaultStore.put(vaultKey(region, vaultName), vault);
        });
    }

    private Map<String, String> findTagsByArn(String arn) {
        Optional<BackupVault> vault = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vault.isPresent()) {
            return vault.get().getTags();
        }
        Optional<BackupPlan> plan = planStore.scan(k -> true).stream()
                .filter(p -> arn.equals(p.getBackupPlanArn()))
                .findFirst();
        if (plan.isPresent()) {
            return new HashMap<>();
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private void applyTags(String arn, Map<String, String> newTags) {
        Optional<BackupVault> vaultOpt = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vaultOpt.isPresent()) {
            BackupVault vault = vaultOpt.get();
            vault.getTags().putAll(newTags);
            vaultStore.put(vaultKey(vault), vault);
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private void removeTags(String arn, List<String> tagKeys) {
        Optional<BackupVault> vaultOpt = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vaultOpt.isPresent()) {
            BackupVault vault = vaultOpt.get();
            tagKeys.forEach(vault.getTags()::remove);
            vaultStore.put(vaultKey(vault), vault);
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private static void assignRuleIds(List<BackupRule> rules) {
        if (rules == null) {
            return;
        }
        for (BackupRule rule : rules) {
            if (rule.getRuleId() == null) {
                rule.setRuleId(UUID.randomUUID().toString());
            }
        }
    }

    private static String inferResourceType(String resourceArn) {
        if (resourceArn == null) {
            return null;
        }
        if (resourceArn.contains(":s3:::")) {
            return "S3";
        }
        if (resourceArn.contains(":rds:")) {
            return "RDS";
        }
        if (resourceArn.contains(":dynamodb:")) {
            return "DynamoDB";
        }
        if (resourceArn.contains(":ec2:")) {
            return "EC2";
        }
        if (resourceArn.contains(":elasticfilesystem:")) {
            return "EFS";
        }
        return null;
    }

    private static String vaultKey(String region, String vaultName) {
        return region + ":" + vaultName;
    }

    private static String vaultKey(BackupVault vault) {
        String arn = vault.getBackupVaultArn();
        // arn:aws:backup:{region}:{account}:backup-vault:{name}
        String[] parts = arn.split(":");
        return parts[3] + ":" + vault.getBackupVaultName();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
