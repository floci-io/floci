package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessNamespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessWorkgroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redshift Serverless management-plane business logic.
 * <p>
 * Namespace and workgroup names are scoped per region by real AWS (verified against
 * {@code aws-sdk-go-v2/service/redshiftserverless} {@code types.Namespace}/{@code Workgroup}),
 * so every store here is keyed {@code region + ":" + name} rather than by name alone — this is
 * what lets the standing DR pair (SPE-71256 serverless design §2) give both regions' resources
 * the identical {@code spec.name} inside one Floci process.
 * <p>
 * Resources converge synchronously (created straight into {@code AVAILABLE}), the same
 * simplification {@code EmrService} makes for clusters: Phase 1 has no need to model the
 * CREATING/MODIFYING transition window, only the terminal state Crossplane observes.
 * {@code RestoreFromSnapshot} deliberately does not create a namespace or workgroup — real
 * {@code RestoreFromSnapshot} restores into an existing one, which is the fact that shaped the
 * whole serverless design (see design doc §1: "restores into an existing namespace").
 */
@ApplicationScoped
public class RedshiftServerlessService {

    private static final String LOWER_ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, RedshiftServerlessNamespace> namespaceStore;
    private final StorageBackend<String, RedshiftServerlessWorkgroup> workgroupStore;
    private final StorageBackend<String, RedshiftServerlessSnapshot> snapshotStore;
    private final Map<String, Map<String, String>> tagsByArn = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;

    @Inject
    public RedshiftServerlessService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.namespaceStore = storageFactory.create("redshiftserverless", "redshiftserverless-namespaces.json",
                new TypeReference<Map<String, RedshiftServerlessNamespace>>() {});
        this.workgroupStore = storageFactory.create("redshiftserverless", "redshiftserverless-workgroups.json",
                new TypeReference<Map<String, RedshiftServerlessWorkgroup>>() {});
        this.snapshotStore = storageFactory.create("redshiftserverless", "redshiftserverless-snapshots.json",
                new TypeReference<Map<String, RedshiftServerlessSnapshot>>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Namespaces ────────────────────────────

    public RedshiftServerlessNamespace createNamespace(RedshiftServerlessNamespace namespace, String region) {
        if (namespace.getNamespaceName() == null || namespace.getNamespaceName().isBlank()) {
            throw new AwsException("ValidationException", "1 validation error detected: Value null at "
                    + "'namespaceName' failed to satisfy constraint: Member must not be null", 400);
        }
        String key = key(region, namespace.getNamespaceName());
        if (namespaceStore.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "Namespace " + namespace.getNamespaceName() + " already exists.", 400);
        }
        namespace.setNamespaceId("namespace-" + randomId());
        namespace.setRegion(region);
        namespace.setAccountId(regionResolver.getAccountId());
        namespace.setNamespaceArn(regionResolver.buildArn("redshift-serverless", region,
                "namespace/" + namespace.getNamespaceId()));
        namespace.setStatus("AVAILABLE");
        namespace.setCreationDate(Instant.now());
        namespaceStore.put(key, namespace);
        return namespace;
    }

    public RedshiftServerlessNamespace getNamespace(String namespaceName, String region) {
        return namespaceStore.get(key(region, namespaceName))
                .orElseThrow(() -> notFound("Namespace " + namespaceName + " not found."));
    }

    public RedshiftServerlessNamespace updateNamespace(String namespaceName, String region,
            RedshiftServerlessNamespace patch) {
        RedshiftServerlessNamespace existing = getNamespace(namespaceName, region);
        if (patch.getAdminUsername() != null) {
            existing.setAdminUsername(patch.getAdminUsername());
        }
        if (patch.getDbName() != null) {
            existing.setDbName(patch.getDbName());
        }
        if (patch.getDefaultIamRoleArn() != null) {
            existing.setDefaultIamRoleArn(patch.getDefaultIamRoleArn());
        }
        if (!patch.getIamRoles().isEmpty()) {
            existing.setIamRoles(patch.getIamRoles());
        }
        if (patch.getKmsKeyId() != null) {
            existing.setKmsKeyId(patch.getKmsKeyId());
        }
        if (!patch.getLogExports().isEmpty()) {
            existing.setLogExports(patch.getLogExports());
        }
        namespaceStore.put(key(region, namespaceName), existing);
        return existing;
    }

    public void deleteNamespace(String namespaceName, String region) {
        String key = key(region, namespaceName);
        if (namespaceStore.get(key).isEmpty()) {
            throw notFound("Namespace " + namespaceName + " not found.");
        }
        namespaceStore.delete(key);
    }

    public List<RedshiftServerlessNamespace> listNamespaces(String region) {
        return namespaceStore.scan(k -> k.startsWith(region + ":"));
    }

    // ──────────────────────────── Workgroups ────────────────────────────

    public RedshiftServerlessWorkgroup createWorkgroup(RedshiftServerlessWorkgroup workgroup, String region) {
        if (workgroup.getWorkgroupName() == null || workgroup.getWorkgroupName().isBlank()) {
            throw new AwsException("ValidationException", "1 validation error detected: Value null at "
                    + "'workgroupName' failed to satisfy constraint: Member must not be null", 400);
        }
        if (workgroup.getNamespaceName() == null || workgroup.getNamespaceName().isBlank()) {
            throw new AwsException("ValidationException", "1 validation error detected: Value null at "
                    + "'namespaceName' failed to satisfy constraint: Member must not be null", 400);
        }
        // Real CreateWorkgroup requires the namespace to already exist; enforcing it here
        // catches a composition ordering bug the same way AWS would.
        getNamespace(workgroup.getNamespaceName(), region);

        String key = key(region, workgroup.getWorkgroupName());
        if (workgroupStore.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "Workgroup " + workgroup.getWorkgroupName() + " already exists.", 400);
        }
        workgroup.setWorkgroupId("workgroup-" + randomId());
        workgroup.setRegion(region);
        workgroup.setAccountId(regionResolver.getAccountId());
        workgroup.setWorkgroupArn(regionResolver.buildArn("redshift-serverless", region,
                "workgroup/" + workgroup.getWorkgroupId()));
        workgroup.setStatus("AVAILABLE");
        workgroup.setEndpointAddress(workgroup.getWorkgroupName() + "." + regionResolver.getAccountId()
                + "." + region + ".redshift-serverless.amazonaws.com");
        workgroup.setEndpointPort(workgroup.getPort() != null ? workgroup.getPort() : 5439);
        workgroup.setCreationDate(Instant.now());
        workgroupStore.put(key, workgroup);
        return workgroup;
    }

    public RedshiftServerlessWorkgroup getWorkgroup(String workgroupName, String region) {
        return workgroupStore.get(key(region, workgroupName))
                .orElseThrow(() -> notFound("Workgroup " + workgroupName + " not found."));
    }

    public RedshiftServerlessWorkgroup updateWorkgroup(String workgroupName, String region,
            RedshiftServerlessWorkgroup patch) {
        RedshiftServerlessWorkgroup existing = getWorkgroup(workgroupName, region);
        if (patch.getBaseCapacity() != null) {
            existing.setBaseCapacity(patch.getBaseCapacity());
        }
        if (patch.getMaxCapacity() != null) {
            existing.setMaxCapacity(patch.getMaxCapacity());
        }
        if (patch.getEnhancedVpcRouting() != null) {
            existing.setEnhancedVpcRouting(patch.getEnhancedVpcRouting());
        }
        if (patch.getPubliclyAccessible() != null) {
            existing.setPubliclyAccessible(patch.getPubliclyAccessible());
        }
        if (!patch.getSecurityGroupIds().isEmpty()) {
            existing.setSecurityGroupIds(patch.getSecurityGroupIds());
        }
        if (!patch.getSubnetIds().isEmpty()) {
            existing.setSubnetIds(patch.getSubnetIds());
        }
        if (patch.getPort() != null) {
            existing.setPort(patch.getPort());
            existing.setEndpointPort(patch.getPort());
        }
        workgroupStore.put(key(region, workgroupName), existing);
        return existing;
    }

    public void deleteWorkgroup(String workgroupName, String region) {
        String key = key(region, workgroupName);
        if (workgroupStore.get(key).isEmpty()) {
            throw notFound("Workgroup " + workgroupName + " not found.");
        }
        workgroupStore.delete(key);
    }

    public List<RedshiftServerlessWorkgroup> listWorkgroups(String region) {
        return workgroupStore.scan(k -> k.startsWith(region + ":"));
    }

    // ──────────────────────────── Snapshots ────────────────────────────

    /**
     * Creates a snapshot directly in {@code region}. Real {@code CreateSnapshot} always creates
     * in the source namespace's own region; a snapshot that should appear as a cross-region copy
     * for switchover testing is seeded by calling this against the standby region's Floci
     * endpoint directly — see design doc §floci ("pre-seed state rather than modelling the full
     * lifecycle").
     */
    public RedshiftServerlessSnapshot createSnapshot(String snapshotName, String namespaceName, String region) {
        if (snapshotName == null || snapshotName.isBlank()) {
            throw new AwsException("ValidationException", "1 validation error detected: Value null at "
                    + "'snapshotName' failed to satisfy constraint: Member must not be null", 400);
        }
        RedshiftServerlessNamespace namespace = getNamespace(namespaceName, region);
        String key = key(region, snapshotName);
        if (snapshotStore.get(key).isPresent()) {
            throw new AwsException("ConflictException", "Snapshot " + snapshotName + " already exists.", 400);
        }
        RedshiftServerlessSnapshot snapshot = new RedshiftServerlessSnapshot();
        snapshot.setSnapshotName(snapshotName);
        snapshot.setNamespaceName(namespaceName);
        snapshot.setNamespaceArn(namespace.getNamespaceArn());
        snapshot.setRegion(region);
        snapshot.setAccountId(regionResolver.getAccountId());
        snapshot.setOwnerAccount(regionResolver.getAccountId());
        snapshot.setSnapshotArn(regionResolver.buildArn("redshift-serverless", region, "snapshot/" + snapshotName));
        snapshot.setStatus("AVAILABLE");
        snapshot.setSnapshotCreateTime(Instant.now());
        snapshotStore.put(key, snapshot);
        return snapshot;
    }

    public RedshiftServerlessSnapshot getSnapshot(String snapshotName, String region) {
        return snapshotStore.get(key(region, snapshotName))
                .orElseThrow(() -> notFound("Snapshot " + snapshotName + " not found."));
    }

    public void deleteSnapshot(String snapshotName, String region) {
        String key = key(region, snapshotName);
        if (snapshotStore.get(key).isEmpty()) {
            throw notFound("Snapshot " + snapshotName + " not found.");
        }
        snapshotStore.delete(key);
    }

    public List<RedshiftServerlessSnapshot> listSnapshots(String region, String namespaceName) {
        List<RedshiftServerlessSnapshot> all = snapshotStore.scan(k -> k.startsWith(region + ":"));
        if (namespaceName == null || namespaceName.isBlank()) {
            return all;
        }
        List<RedshiftServerlessSnapshot> filtered = new ArrayList<>();
        for (RedshiftServerlessSnapshot s : all) {
            if (namespaceName.equals(s.getNamespaceName())) {
                filtered.add(s);
            }
        }
        return filtered;
    }

    // ──────────────────────────── Restore ────────────────────────────

    /**
     * Restores into the already-existing {@code namespaceName}/{@code workgroupName} in
     * {@code region} — never creates either. This mirrors real {@code RestoreFromSnapshot}
     * (design doc §1) and is exactly what {@code RedshiftServerlessRestore} in
     * velos-aws-provisioner-controller depends on.
     */
    public RedshiftServerlessNamespace restoreFromSnapshot(String namespaceName, String workgroupName,
            String snapshotName, String region) {
        getSnapshot(snapshotName, region);
        RedshiftServerlessNamespace namespace = getNamespace(namespaceName, region);
        // Workgroup must exist too, or the restore has nothing to attach the refreshed data to.
        getWorkgroup(workgroupName, region);
        namespace.setStatus("AVAILABLE");
        namespaceStore.put(key(region, namespaceName), namespace);
        return namespace;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public void tagResource(String resourceArn, Map<String, String> tags) {
        tagsByArn.computeIfAbsent(resourceArn, k -> new ConcurrentHashMap<>()).putAll(tags);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        Map<String, String> existing = tagsByArn.get(resourceArn);
        if (existing != null) {
            tagKeys.forEach(existing::remove);
        }
    }

    public Map<String, String> listTags(String resourceArn) {
        return tagsByArn.getOrDefault(resourceArn, Map.of());
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private String key(String region, String name) {
        return region + ":" + name;
    }

    private AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private String randomId() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(LOWER_ALNUM.charAt(RANDOM.nextInt(LOWER_ALNUM.length())));
        }
        return sb.toString();
    }
}
