package io.github.hectorvent.floci.services.docdb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.docdb.container.DocDbContainerHandle;
import io.github.hectorvent.floci.services.docdb.container.DocDbContainerManager;
import io.github.hectorvent.floci.services.docdb.model.DocDbCluster;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import io.github.hectorvent.floci.core.common.BackupWindows;
import io.github.hectorvent.floci.services.docdb.model.DocDbClusterSettings;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstanceSettings;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@ApplicationScoped
public class DocDbService {

    private static final Logger LOG = Logger.getLogger(DocDbService.class);
    private static final String ENGINE_VERSION_DEFAULT = "5.0.0";

    /** The engine versions a live account lists for docdb, each with its parameter group family. */
    static final Map<String, String> ENGINE_VERSION_FAMILIES = Map.of(
            "3.6.0", "docdb3.6",
            "4.0.0", "docdb4.0",
            "5.0.0", "docdb5.0",
            "5.0.1", "docdb5.0",
            "8.0.0", "docdb8.0",
            "8.0.1", "docdb8.0");
    private static final int MONGO_PORT = 27017;

    private final StorageBackend<String, DocDbCluster> clusters;
    private final StorageBackend<String, DocDbInstance> instances;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final DocDbContainerManager containerManager;
    // DocumentDB subnet groups and cluster parameter groups are the RDS records reached through
    // the rds scope; security groups are EC2's; a KMS key is resolved to its ARN as on AWS
    private final RdsService rdsService;
    private final Ec2Service ec2Service;
    private final KmsService kmsService;
    /**
     * One monitor per stored record, taken by everything that writes it. A tag update is a
     * read-modify-write, so without a lock shared with delete it can put a deleted cluster back.
     */
    /**
     * One monitor per record, taken by everything that writes it, and retired with the record.
     *
     * <p>Retiring one means a straggler can hold the old monitor while a later caller takes a new
     * one for the same key, so a write must not depend on the monitor alone for its safety: the
     * writes that can outlive a delete check the record under the key is still theirs.
     */
    private final ConcurrentHashMap<String, Object> writeLocks = new ConcurrentHashMap<>();

    /**
     * Storage key for a record: AWS scopes these identifiers per region, so the region is part of
     * the key rather than something read back off the record. The shape matches
     * {@code RdsService.dbResourceKey}, which solves the same problem for the same identifiers.
     */
    private String key(String id) {
        return key(regionResolver.getRegion(), id);
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    /**
     * A cluster in one region, migrating a record written before regions were part of the key.
     *
     * <p>Such a record was created when every ARN came from the configured default region, so that
     * is the region it belongs to — the same assumption the ARN backfill makes. It is moved under
     * its regional key on first read, so the unscoped key is not consulted again.
     */
    private Optional<DocDbCluster> findCluster(String region, String id) {
        Optional<DocDbCluster> scoped = clusters.get(key(region, id));
        if (scoped.isPresent() || !region.equals(regionResolver.getDefaultRegion())) {
            return scoped;
        }
        // Under the record's monitor, and read again inside it: the move is a read-modify-write,
        // so a delete landing between the two would otherwise be undone by writing the record
        // back under its new key. Monitors are reentrant, so callers already holding this one —
        // delete among them — pass straight through.
        synchronized (lockFor("cluster:" + key(region, id))) {
            Optional<DocDbCluster> legacy = clusters.get(id);
            legacy.ifPresent(cluster -> {
                clusters.put(key(region, id), cluster);
                clusters.delete(id);
                LOG.debugv("Moved DocDB cluster {0} under its {1} key", id, region);
            });
            return legacy;
        }
    }

    private Optional<DocDbInstance> findInstance(String region, String id) {
        Optional<DocDbInstance> scoped = instances.get(key(region, id));
        if (scoped.isPresent() || !region.equals(regionResolver.getDefaultRegion())) {
            return scoped;
        }
        synchronized (lockFor("instance:" + key(region, id))) {
            Optional<DocDbInstance> legacy = instances.get(id);
            legacy.ifPresent(instance -> {
                instances.put(key(region, id), instance);
                instances.delete(id);
                LOG.debugv("Moved DocDB instance {0} under its {1} key", id, region);
            });
            return legacy;
        }
    }

    /**
     * Writes a record filled in on read, unless it has been deleted meanwhile.
     *
     * <p>Filling a field in on read is still a write, and a read is not otherwise serialised
     * against a delete — so without the record's monitor and a second look inside it, a reader
     * that started before a delete puts the record back after it.
     */
    private <T> void persistIfStillPresent(StorageBackend<String, T> store, String monitor,
                                           String key, T record) {
        // Two things, because a field filled in on read is still a write. The monitor is the one
        // every other writer of this record takes — naming it differently would be no mutual
        // exclusion at all — and the record stored under the key has to still be this record, so
        // that a delete, or a delete and a re-create under the same name, is not written over.
        synchronized (lockFor(monitor)) {
            if (store.get(key).orElse(null) == record) {
                store.put(key, record);
            }
        }
    }

    /**
     * Every record of one kind in one region, including any not yet moved under a regional key.
     *
     * <p>Two scans of a store a migration is moving records within can each miss what the other
     * sees, so the unscoped keys are read first and the regional ones second: a record the first
     * scan misses has been moved already, which the second scan therefore sees, and one the second
     * misses had not been moved when the first ran. Anything seen twice is one record caught
     * mid-move, so the list is reduced by identifier.
     */
    private <T> List<T> inRegion(StorageBackend<String, T> store, String region,
                                 Function<T, String> identifier) {
        Map<String, T> found = new LinkedHashMap<>();
        if (region.equals(regionResolver.getDefaultRegion())) {
            store.scan(k -> !k.contains("::")).forEach(r -> found.put(identifier.apply(r), r));
        }
        store.scan(k -> k.startsWith(region + "::")).forEach(r -> found.put(identifier.apply(r), r));
        return List.copyOf(found.values());
    }

    @Inject
    public DocDbService(EmulatorConfig config,
                        RegionResolver regionResolver,
                        DocDbContainerManager containerManager,
                        StorageFactory storageFactory,
                        RdsService rdsService,
                        Ec2Service ec2Service,
                        KmsService kmsService) {
        this.config = config;
        this.regionResolver = regionResolver;
        this.containerManager = containerManager;
        this.rdsService = rdsService;
        this.ec2Service = ec2Service;
        this.kmsService = kmsService;
        this.clusters = storageFactory.create("docdb", "docdb-clusters.json",
                new TypeReference<Map<String, DocDbCluster>>() {});
        this.instances = storageFactory.create("docdb", "docdb-instances.json",
                new TypeReference<Map<String, DocDbInstance>>() {});
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    public DocDbCluster createDbCluster(String id, String engineVersion,
                                        String masterUsername, String masterPassword,
                                        boolean iamEnabled) {
        return createDbCluster(id, engineVersion, masterUsername, masterPassword, iamEnabled,
                DocDbClusterSettings.defaults(), Map.of());
    }

    public DocDbCluster createDbCluster(String id, String engineVersion,
                                        String masterUsername, String masterPassword,
                                        boolean iamEnabled, DocDbClusterSettings settings,
                                        Map<String, String> tags) {
        String region = regionResolver.getRegion();
        settings.validate();
        String effectiveEngineVersion = requireKnownEngineVersion(engineVersion);
        // every reference checked and every default chosen before a container is started
        DocDbClusterSettings resolved = resolveClusterSettings(settings, null, effectiveEngineVersion, region);
        synchronized (lockFor("cluster:" + key(region, id))) {
            if (findCluster(region, id).isPresent()) {
                throw new AwsException("DBClusterAlreadyExistsFault",
                        "DocDB cluster " + id + " already exists.", 400);
            }

            DocDbCluster cluster = new DocDbCluster();
            cluster.setDbClusterIdentifier(id);
            cluster.setStatus("available");
            cluster.setEngineVersion(effectiveEngineVersion);
            cluster.setMasterUsername(masterUsername);
            cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
            cluster.setDbClusterArn(regionResolver.buildArn("rds", region, "cluster:" + id));
            cluster.setDbClusterResourceId("cluster-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 24).toUpperCase());
            cluster.setCreatedAt(Instant.now());
            cluster.setDbClusterMembers(new ArrayList<>());
            resolved.applyTo(cluster);
            if (tags != null && !tags.isEmpty()) {
                cluster.setTags(new LinkedHashMap<>(tags));
            }

            if (config.services().docdb().mock()) {
                LOG.infov("Creating DocDB cluster {0} in mock mode (no container)", id);
                cluster.setEndpoint("localhost");
                cluster.setReaderEndpoint("localhost");
                cluster.setPort(MONGO_PORT);
            } else {
                String image = config.services().docdb().defaultImage();
                LOG.infov("Creating DocDB cluster {0}, image={1}", id, image);
                // A cluster record is metadata: its identifier, ARN and tags need no Docker, so the
                // cluster is created and reaches 'available' even when no daemon is reachable. Only
                // connecting to the database needs the container.
                DocDbContainerHandle handle = containerManager.tryStart(id, image, masterUsername, masterPassword);
                if (handle != null) {
                    cluster.setEndpoint(handle.getHost());
                    cluster.setReaderEndpoint(handle.getHost());
                    cluster.setPort(handle.getPort());
                    cluster.setContainerId(handle.getContainerId());
                    cluster.setContainerHost(handle.getHost());
                    cluster.setContainerPort(handle.getPort());
                } else {
                    cluster.setEndpoint(resolveEndpointHost());
                    cluster.setReaderEndpoint(resolveEndpointHost());
                    cluster.setPort(MONGO_PORT);
                    LOG.warnv("DocDB cluster {0} created without a backing MongoDB container: no "
                            + "Docker daemon is reachable. Metadata operations work; connections to "
                            + "the cluster do not until a daemon appears.", id);
                }
            }

            clusters.put(key(region, id), cluster);
            LOG.infov("DocDB cluster {0} created, endpoint={1}:{2}",
                    id, cluster.getEndpoint(), String.valueOf(cluster.getPort()));
            return cluster;
        }
    }

    /**
     * The settings that will be in effect after the request, with every reference checked the way
     * a live account checks it: the subnet group and cluster parameter group must exist (RDS
     * holds both), each security group must exist, a KMS key is resolved to its ARN. On create
     * ({@code current} null) an omitted value takes the AWS default — the default subnet group,
     * the family's default parameter group, the VPC's default security group, one day of backups
     * and windows clear of each other; on modify it keeps the cluster's.
     */
    private DocDbClusterSettings resolveClusterSettings(DocDbClusterSettings settings, DocDbCluster current,
                                                        String engineVersion, String region) {
        boolean create = current == null;
        String subnetGroup = settings.dbSubnetGroupName();
        if (subnetGroup != null && !subnetGroup.isBlank()) {
            if (rdsService == null) {
                throw new IllegalStateException("DocDbService was built without an RdsService");
            }
            try {
                rdsService.getDbSubnetGroup(subnetGroup, region);
            } catch (AwsException e) {
                throw new AwsException("DBSubnetGroupNotFoundFault",
                        "DB subnet group '" + subnetGroup + "' does not exist.", 404);
            }
        } else if (create) {
            subnetGroup = "default";
        }
        String parameterGroup = settings.dbClusterParameterGroupName();
        if (parameterGroup != null && !parameterGroup.isBlank()) {
            if (rdsService == null) {
                throw new IllegalStateException("DocDbService was built without an RdsService");
            }
            DbClusterParameterGroup group;
            try {
                group = rdsService.getDbClusterParameterGroup(parameterGroup, region);
            } catch (AwsException e) {
                throw new AwsException("DBClusterParameterGroupNotFound",
                        "DBClusterParameterGroup not found: " + parameterGroup, 404);
            }
            requireFamily(group, engineVersion);
        } else if (create) {
            parameterGroup = "default." + parameterGroupFamily(engineVersion);
        } else if (current.getDbClusterParameterGroupName() != null
                && !parameterGroupFamily(engineVersion).equals(parameterGroupFamily(current.getEngineVersion()))) {
            // judged by what RDS holds for the group, never by its name: a custom group may be called anything
            if (rdsService != null && rdsService.isManagedClusterParameterGroup(current.getDbClusterParameterGroupName())) {
                // an engine version change moves a cluster on a default group to the new family's default
                parameterGroup = "default." + parameterGroupFamily(engineVersion);
            } else if (rdsService != null && !parameterGroupFamily(engineVersion).equals(
                    currentParameterGroupFamily(current.getDbClusterParameterGroupName(), region))) {
                throw new AwsException("InvalidParameterCombination", "The current DB cluster parameter group "
                        + current.getDbClusterParameterGroupName() + " is custom. You must explicitly specify a "
                        + "new DB cluster parameter group, either default or custom, for the engine version upgrade.",
                        400);
            }
        }
        List<String> securityGroups = settings.vpcSecurityGroupIds();
        if (securityGroups != null && !securityGroups.isEmpty()) {
            if (ec2Service == null) {
                throw new IllegalStateException("DocDbService was built without an Ec2Service");
            }
            for (String groupId : securityGroups) {
                boolean known = ec2Service.describeSecurityGroups(region, List.of(groupId), null, Map.of())
                        .stream().anyMatch(sg -> groupId.equals(sg.getGroupId()));
                if (!known) {
                    throw new AwsException("InvalidParameterValue",
                            "Invalid security group , groupId= " + groupId + ", groupName=.", 400);
                }
            }
        } else if (create) {
            securityGroups = List.of(Ec2Service.defaultSecurityGroupId(region));
        }
        String kmsKeyArn = resolveKmsKeyArn(settings.kmsKeyId(), region);
        String backup = settings.preferredBackupWindow();
        String maintenance = settings.preferredMaintenanceWindow();
        boolean backupGiven = backup != null;
        boolean maintenanceGiven = maintenance != null;
        if (create) {
            if (!backupGiven) {
                backup = maintenanceGiven && BackupWindows.overlap(BackupWindows.DEFAULT_BACKUP_WINDOW, maintenance)
                        ? BackupWindows.backupWindowAfter(maintenance) : BackupWindows.DEFAULT_BACKUP_WINDOW;
                if (maintenanceGiven && BackupWindows.overlap(backup, maintenance)) {
                    // the given window leaves no daily half hour free
                    throw new AwsException("InvalidParameterValue", "The specified maintenance window overlaps "
                            + "all available default backup windows. Shrink the maintenance window or specify "
                            + "a non-overlapping backup window.", 400);
                }
            }
            if (!maintenanceGiven) {
                maintenance = backupGiven && BackupWindows.overlap(backup, BackupWindows.DEFAULT_MAINTENANCE_WINDOW)
                        ? BackupWindows.maintenanceWindowAfter(backup) : BackupWindows.DEFAULT_MAINTENANCE_WINDOW;
                if (BackupWindows.overlap(backup, maintenance)) {
                    throw new AwsException("InvalidParameterValue", "The specified backup window overlaps "
                            + "all available default maintenance windows. Shrink the backup window or specify "
                            + "a non-overlapping maintenance window.", 400);
                }
            }
        } else {
            if (!backupGiven) {
                backup = current.getPreferredBackupWindow() != null
                        ? current.getPreferredBackupWindow() : BackupWindows.DEFAULT_BACKUP_WINDOW;
            }
            if (!maintenanceGiven) {
                maintenance = current.getPreferredMaintenanceWindow() != null
                        ? current.getPreferredMaintenanceWindow() : BackupWindows.DEFAULT_MAINTENANCE_WINDOW;
            }
        }
        if (BackupWindows.overlap(backup, maintenance)) {
            throw BackupWindows.overlapping();
        }
        return new DocDbClusterSettings(subnetGroup, parameterGroup, securityGroups,
                settings.storageEncrypted(), kmsKeyArn, settings.backupRetentionPeriod(),
                backup, maintenance, settings.deletionProtection());
    }

    /** The family of the group a cluster is on; a group deleted since is judged by nothing. */
    private String currentParameterGroupFamily(String name, String region) {
        try {
            return rdsService.getDbClusterParameterGroup(name, region).getDbParameterGroupFamily();
        } catch (AwsException e) {
            return null;
        }
    }

    private static void requireFamily(DbClusterParameterGroup group, String engineVersion) {
        String family = parameterGroupFamily(engineVersion);
        if (group.getDbParameterGroupFamily() != null && !family.equals(group.getDbParameterGroupFamily())) {
            throw new AwsException("InvalidParameterCombination", "The Parameter Group "
                    + group.getDbClusterParameterGroupName() + " with DBParameterGroupFamily "
                    + group.getDbParameterGroupFamily() + " cannot be used for this instance. "
                    + "Please use a Parameter Group with DBParameterGroupFamily " + family, 400);
        }
    }

    /**
     * The version a request names, refused as a live account refuses one it does not list. The
     * {@code major.minor} form of a listed version is accepted as given.
     */
    static String requireKnownEngineVersion(String engineVersion) {
        if (engineVersion == null || engineVersion.isBlank()) {
            return ENGINE_VERSION_DEFAULT;
        }
        if (ENGINE_VERSION_FAMILIES.containsKey(engineVersion)
                || ENGINE_VERSION_FAMILIES.containsValue("docdb" + engineVersion)) {
            return engineVersion;
        }
        throw new AwsException("InvalidParameterCombination",
                "Cannot find version " + engineVersion + " for docdb", 400);
    }

    /**
     * The parameter group family an engine version belongs to: {@code 5.0.0} is {@code docdb5.0}.
     * A version outside the catalogue can only come from a record persisted before versions were
     * checked; its family is still derived so the record keeps describing the way it always has.
     */
    static String parameterGroupFamily(String engineVersion) {
        String version = engineVersion == null || engineVersion.isBlank() ? ENGINE_VERSION_DEFAULT : engineVersion;
        String known = ENGINE_VERSION_FAMILIES.get(version);
        if (known != null) {
            return known;
        }
        String[] parts = version.split("\\.");
        return "docdb" + parts[0] + "." + (parts.length > 1 ? parts[1] : "0");
    }

    /**
     * A live account takes the key as an ARN, a key id, an alias ARN or an alias name and reports
     * the key ARN on the cluster; a key it cannot use is one fault whatever the reason.
     */
    private String resolveKmsKeyArn(String kmsKeyId, String region) {
        if (kmsKeyId == null || kmsKeyId.isBlank()) {
            return null;
        }
        if (kmsService == null) {
            throw new IllegalStateException("DocDbService was built without a KmsService; a KmsKeyId cannot be resolved");
        }
        KmsKey key;
        try {
            key = kmsService.describeKey(kmsKeyId, region);
        } catch (AwsException e) {
            throw kmsKeyNotAccessible(kmsKeyId);
        }
        if (!key.isEnabled() || "PendingDeletion".equals(key.getKeyState())) {
            throw kmsKeyNotAccessible(kmsKeyId);
        }
        return key.getArn();
    }

    private static AwsException kmsKeyNotAccessible(String kmsKeyId) {
        return new AwsException("KMSKeyNotAccessibleFault", "The specified KMS key [" + kmsKeyId
                + "] does not exist, is not enabled or you do not have permissions to access it.", 400);
    }

    public DocDbCluster getDbCluster(String id) {
        String region = regionResolver.getRegion();
        DocDbCluster cluster = findCluster(region, id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "DocDB cluster " + id + " not found.", 404));
        return filled(cluster, region);
    }

    /**
     * A cluster with what a record written by an earlier version lacks: its ARN, and the settings
     * a live account gives a cluster that names none — the default subnet group, the engine's
     * default parameter group, the VPC's default security group and the default windows.
     */
    private DocDbCluster filled(DocDbCluster cluster, String region) {
        String id = cluster.getDbClusterIdentifier();
        boolean changed = false;
        if (cluster.getDbClusterArn() == null || cluster.getDbClusterArn().isBlank()) {
            cluster.setDbClusterArn(legacyArn(region, "cluster:" + id));
            changed = true;
        }
        if (cluster.getDbSubnetGroupName() == null) {
            cluster.setDbSubnetGroupName("default");
            changed = true;
        }
        if (cluster.getDbClusterParameterGroupName() == null) {
            cluster.setDbClusterParameterGroupName("default." + parameterGroupFamily(cluster.getEngineVersion()));
            changed = true;
        }
        if (cluster.getVpcSecurityGroupIds() == null || cluster.getVpcSecurityGroupIds().isEmpty()) {
            cluster.setVpcSecurityGroupIds(List.of(Ec2Service.defaultSecurityGroupId(region)));
            changed = true;
        }
        if (cluster.getPreferredBackupWindow() == null) {
            cluster.setPreferredBackupWindow(BackupWindows.DEFAULT_BACKUP_WINDOW);
            changed = true;
        }
        if (cluster.getPreferredMaintenanceWindow() == null) {
            cluster.setPreferredMaintenanceWindow(BackupWindows.DEFAULT_MAINTENANCE_WINDOW);
            changed = true;
        }
        if (changed) {
            persistIfStillPresent(clusters, "cluster:" + key(region, id), key(region, id), cluster);
        }
        return cluster;
    }

    /**
     * The ARN a record written before ARNs were stored should have had.
     *
     * <p>The region it is stored under, not the caller's: a record reaches a region's key either
     * by having been created there or, for one written before regions were part of the key, by
     * belonging to the default region. Taking the region of whoever happens to read it first would
     * let a caller elsewhere claim it, and a record with no ARN cannot be told apart from another
     * region's of the same identifier or tagged through an ARN at all.
     */
    private String legacyArn(String region, String resource) {
        return regionResolver.buildArn("rds", region, resource);
    }

    public boolean hasCluster(String id) {
        return hasCluster(id, regionResolver.getRegion());
    }

    /**
     * Whether DocumentDB holds this cluster <em>in this region</em>. An identifier belongs to one
     * region, so this is a lookup rather than a filter over every record of that name.
     */
    public boolean hasCluster(String id, String region) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return findCluster(region, id).isPresent();
    }

    /** Refuses a record whose own ARN is not the one that was asked for. */
    private static void requireArnNamesRecord(String requested, String stored,
                                              String errorCode, String message) {
        // No null allowance: a record read through getDbCluster or getDbInstance has been given
        // its ARN if it lacked one, so anything reaching here can be compared.
        if (!requested.equalsIgnoreCase(stored)) {
            throw new AwsException(errorCode, message, 404);
        }
    }

    /** The region a record was created in, taken from its ARN; older records carry the default. */
    private String regionOf(String arn) {
        return AwsArnUtils.regionOrDefault(arn, regionResolver.getDefaultRegion());
    }

    /**
     * Whether an ARN names a DocumentDB cluster or instance, matched against the stored ARN.
     *
     * <p>RDS and DocumentDB share the {@code arn:aws:rds:...} space, so the trailing identifier
     * alone does not identify a service: an RDS resource whose name a DocumentDB record happens to
     * share would be answered from the wrong store. The full ARN settles region, account, type and
     * name in one comparison, as the db-cluster-id filter already does.
     */
    public boolean hasResourceWithArn(String arn) {
        if (arn == null || !arn.startsWith("arn:")) {
            return false;
        }
        return clusters.scan(k -> true).stream()
                        .anyMatch(c -> arn.equalsIgnoreCase(c.getDbClusterArn()))
                || instances.scan(k -> true).stream()
                        .anyMatch(i -> arn.equalsIgnoreCase(i.getDbInstanceArn()));
    }

    public boolean hasInstance(String id) {
        return hasInstance(id, regionResolver.getRegion());
    }

    public boolean hasInstance(String id, String region) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return findInstance(region, id).isPresent();
    }

    public Collection<DocDbCluster> listDbClusters(String filterId) {
        String region = regionResolver.getRegion();
        if (filterId != null && !filterId.isBlank()) {
            // The db-cluster-id filter accepts ARNs as well as identifiers. Match the
            // full ARN against each cluster's stored ARN rather than reducing it to
            // the bare identifier, so a cross-account or cross-region ARN does not
            // resolve a same-named local cluster.
            if (filterId.startsWith("arn:")) {
                return inRegion(clusters, region, DocDbCluster::getDbClusterIdentifier).stream()
                        .map(c -> filled(c, region))
                        .filter(c -> filterId.equalsIgnoreCase(c.getDbClusterArn()))
                        .toList();
            }
            return findCluster(region, filterId).map(c -> List.of(filled(c, region))).orElseGet(List::of);
        }
        return inRegion(clusters, region, DocDbCluster::getDbClusterIdentifier).stream()
                .map(c -> filled(c, region))
                .toList();
    }

    public DocDbCluster modifyDbCluster(String id, String engineVersion, Boolean iamEnabled) {
        return modifyDbCluster(id, engineVersion, iamEnabled, DocDbClusterSettings.unchanged());
    }

    public DocDbCluster modifyDbCluster(String id, String engineVersion, Boolean iamEnabled,
                                        DocDbClusterSettings settings) {
        String region = regionResolver.getRegion();
        settings.validate();
        synchronized (lockFor("cluster:" + key(region, id))) {
            DocDbCluster cluster = getDbCluster(id);
            // every check before any change: the store hands out its own object
            String effectiveEngineVersion = engineVersion != null && !engineVersion.isBlank()
                    ? requireKnownEngineVersion(engineVersion) : cluster.getEngineVersion();
            DocDbClusterSettings resolved = resolveClusterSettings(settings, cluster, effectiveEngineVersion, region);
            cluster.setEngineVersion(effectiveEngineVersion);
            if (iamEnabled != null) {
                cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
            }
            resolved.applyTo(cluster);
            clusters.put(key(region, id), cluster);
            LOG.infov("DocDB cluster {0} modified", id);
            return cluster;
        }
    }

    public void deleteDbCluster(String id) {
        String region = regionResolver.getRegion();
        synchronized (lockFor("cluster:" + key(region, id))) {
            DocDbCluster cluster = findCluster(region, id).orElseThrow(() ->
                    new AwsException("DBClusterNotFoundFault",
                            "DocDB cluster " + id + " not found.", 404));

            if (cluster.isDeletionProtection()) {
                throw new AwsException("InvalidParameterCombination",
                        "Cannot delete protected Cluster, please disable deletion protection and try again.", 400);
            }
            if (cluster.getDbClusterMembers() != null && !cluster.getDbClusterMembers().isEmpty()) {
                throw new AwsException("InvalidDBClusterStateFault",
                        "Cannot delete DocDB cluster " + id + " — it still has DB instances.", 400);
            }

            cluster.setStatus("deleting");
            clusters.put(key(region, id), cluster);

            if (cluster.getContainerId() != null) {
                containerManager.stop(new DocDbContainerHandle(
                        cluster.getContainerId(), id,
                        cluster.getContainerHost(), cluster.getContainerPort()));
            }

            clusters.delete(key(region, id));
            writeLocks.remove("cluster:" + key(region, id));
            LOG.infov("DocDB cluster {0} deleted", id);
        }
    }

    // ── Instances ─────────────────────────────────────────────────────────────

    public DocDbInstance createDbInstance(String id, String dbClusterIdentifier,
                                          String dbInstanceClass, String engineVersion,
                                          boolean iamEnabled) {
        return createDbInstance(id, dbClusterIdentifier, dbInstanceClass, engineVersion, iamEnabled,
                DocDbInstanceSettings.defaults(), Map.of());
    }

    public DocDbInstance createDbInstance(String id, String dbClusterIdentifier,
                                          String dbInstanceClass, String engineVersion,
                                          boolean iamEnabled, DocDbInstanceSettings settings,
                                          Map<String, String> tags) {
        String region = regionResolver.getRegion();
        settings.validate();
        // Instance monitor before cluster monitor, the one order every path that holds both uses.
        synchronized (lockFor("instance:" + key(region, id))) {
            synchronized (lockFor("cluster:" + key(region, dbClusterIdentifier))) {
                if (findInstance(region, id).isPresent()) {
                    throw new AwsException("DBInstanceAlreadyExists",
                            "DocDB instance " + id + " already exists.", 400);
                }

                DocDbCluster cluster = getDbCluster(dbClusterIdentifier);

                DocDbInstance instance = new DocDbInstance();
                instance.setDbInstanceIdentifier(id);
                instance.setDbClusterIdentifier(dbClusterIdentifier);
                instance.setDbInstanceClass(dbInstanceClass != null ? dbInstanceClass : "db.r5.large");
                instance.setEngineVersion(engineVersion != null ? engineVersion : cluster.getEngineVersion());
                instance.setStatus("available");
                instance.setEndpoint(cluster.getEndpoint());
                instance.setPort(cluster.getPort());
                instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
                instance.setDbInstanceArn(regionResolver.buildArn("rds", region, "db:" + id));
                instance.setDbiResourceId("db-" + UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 24).toUpperCase());
                instance.setCreatedAt(Instant.now());
                instance.setPreferredMaintenanceWindow(cluster.getPreferredMaintenanceWindow() != null
                        ? cluster.getPreferredMaintenanceWindow() : BackupWindows.DEFAULT_MAINTENANCE_WINDOW);
                settings.applyTo(instance);
                if (tags != null && !tags.isEmpty()) {
                    instance.setTags(new LinkedHashMap<>(tags));
                }

                cluster.getDbClusterMembers().add(id);
                clusters.put(key(region, dbClusterIdentifier), cluster);

                instances.put(key(region, id), instance);
                LOG.infov("DocDB instance {0} created in cluster {1}", id, dbClusterIdentifier);
                        return instance;
            }
        }
    }

    public DocDbInstance getDbInstance(String id) {
        String region = regionResolver.getRegion();
        DocDbInstance instance = findInstance(region, id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "DocDB instance " + id + " not found.", 404));
        return filled(instance, region);
    }

    /** An instance with its ARN and, as on a live account, its cluster's maintenance window. */
    private DocDbInstance filled(DocDbInstance instance, String region) {
        String id = instance.getDbInstanceIdentifier();
        boolean changed = false;
        if (instance.getDbInstanceArn() == null || instance.getDbInstanceArn().isBlank()) {
            instance.setDbInstanceArn(legacyArn(region, "db:" + id));
            changed = true;
        }
        if (instance.getPreferredMaintenanceWindow() == null) {
            instance.setPreferredMaintenanceWindow(findCluster(region, instance.getDbClusterIdentifier())
                    .map(c -> filled(c, region).getPreferredMaintenanceWindow())
                    .orElse(BackupWindows.DEFAULT_MAINTENANCE_WINDOW));
            changed = true;
        }
        if (changed) {
            persistIfStillPresent(instances, "instance:" + key(region, id), key(region, id), instance);
        }
        return instance;
    }

    public Collection<DocDbInstance> listDbInstances(String filterId) {
        String region = regionResolver.getRegion();
        if (filterId != null && !filterId.isBlank()) {
            // The db-instance-id filter accepts ARNs as well as identifiers; see
            // listDbClusters for why the match is against the stored ARN.
            if (filterId.startsWith("arn:")) {
                return inRegion(instances, region, DocDbInstance::getDbInstanceIdentifier).stream()
                        .map(i -> filled(i, region))
                        .filter(i -> filterId.equalsIgnoreCase(i.getDbInstanceArn()))
                        .toList();
            }
            return findInstance(region, filterId).map(i -> List.of(filled(i, region))).orElseGet(List::of);
        }
        return inRegion(instances, region, DocDbInstance::getDbInstanceIdentifier).stream()
                .map(i -> filled(i, region))
                .toList();
    }

    public DocDbInstance modifyDbInstance(String id, String dbInstanceClass, Boolean iamEnabled) {
        return modifyDbInstance(id, dbInstanceClass, iamEnabled, DocDbInstanceSettings.defaults());
    }

    public DocDbInstance modifyDbInstance(String id, String dbInstanceClass, Boolean iamEnabled,
                                          DocDbInstanceSettings settings) {
        String region = regionResolver.getRegion();
        settings.validate();
        synchronized (lockFor("instance:" + key(region, id))) {
            DocDbInstance instance = getDbInstance(id);
            if (dbInstanceClass != null && !dbInstanceClass.isBlank()) {
                instance.setDbInstanceClass(dbInstanceClass);
            }
            if (iamEnabled != null) {
                instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
            }
            settings.applyTo(instance);
            instances.put(key(region, id), instance);
            LOG.infov("DocDB instance {0} modified", id);
            return instance;
        }
    }

    public void deleteDbInstance(String id) {
        String region = regionResolver.getRegion();
        synchronized (lockFor("instance:" + key(region, id))) {
            DocDbInstance instance = findInstance(region, id).orElseThrow(() ->
                    new AwsException("DBInstanceNotFound",
                            "DocDB instance " + id + " not found.", 404));

            String clusterId = instance.getDbClusterIdentifier();
            synchronized (lockFor("cluster:" + key(region, clusterId))) {
                DocDbCluster cluster = findCluster(region, clusterId).orElse(null);
                if (cluster != null) {
                    cluster.getDbClusterMembers().remove(id);
                    clusters.put(key(region, clusterId), cluster);
                }
            }

            instances.delete(key(region, id));
            writeLocks.remove("instance:" + key(region, id));
            LOG.infov("DocDB instance {0} deleted", id);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    /** A resolved tag target: the record's key, its tags, and a sink that persists an update. */
    private record TagTarget(String lockKey, Map<String, String> tags,
                             java.util.function.Consumer<Map<String, String>> save) {}

    public Map<String, String> listTagsForResource(String resourceName) {
        return Map.copyOf(resolveTagTarget(resourceName).tags());
    }

    public void addTagsToResource(String resourceName, Map<String, String> tags) {
        updateTags(resourceName, current -> current.putAll(tags));
    }

    public void removeTagsFromResource(String resourceName, Collection<String> tagKeys) {
        // A key that is not present is not an error on a live account; the ones that are get removed.
        updateTags(resourceName, current -> tagKeys.forEach(current::remove));
    }

    private void updateTags(String resourceName, java.util.function.Consumer<Map<String, String>> change) {
        // Resolve once to learn which record is meant, then do the read-modify-write under that
        // record's monitor and resolve again inside it: the record can be deleted in between, and
        // saving what the first read returned would put it back.
        String lockKey = resolveTagTarget(resourceName).lockKey();
        synchronized (lockFor(lockKey)) {
            TagTarget target = resolveTagTarget(resourceName);
            Map<String, String> updated = new LinkedHashMap<>(target.tags());
            change.accept(updated);
            target.save().accept(updated);
        }
    }

    private Object lockFor(String key) {
        return writeLocks.computeIfAbsent(key, k -> new Object());
    }

    /**
     * Resolves a tagging {@code ResourceName} to the DocumentDB record it names.
     *
     * <p>Only ARNs reach here: the router picks this service by looking the ARN's identifier up in
     * DocumentDB storage, so a bare name stays with RDS. The region and account are checked before
     * the identifier, as a live account checks them — storage is keyed by identifier alone, so an
     * ARN naming another region would otherwise resolve this caller's cluster.
     */
    private TagTarget resolveTagTarget(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceName);
        } catch (IllegalArgumentException malformed) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name:  " + resourceName, 400);
        }
        if (!"rds".equals(arn.service())
                || !regionResolver.getRegion().equals(arn.region())
                || !Objects.equals(regionResolver.getAccountId(), arn.accountId())) {
            // One message for both, which is what a live account answers.
            throw new AwsException("InvalidParameterValue",
                    "The specified resource name does not match an RDS resource in this region.", 400);
        }

        String resource = arn.resource();
        int separator = resource.indexOf(':');
        if (separator < 0) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name:  " + resourceName, 400);
        }
        String type = resource.substring(0, separator);
        String id = resource.substring(separator + 1);

        // The record has to be the one this ARN names, not merely one of that identifier: records
        // are keyed by identifier alone, so an ARN whose region matches the caller but not the
        // stored record would otherwise be answered — and mutated — from another region's
        // resource. Reachable on the docdb credential scope, which dispatches here directly
        // rather than through the routing that matches the whole ARN.
        return switch (type) {
            case "cluster" -> {
                DocDbCluster cluster = getDbCluster(id);
                requireArnNamesRecord(resourceName, cluster.getDbClusterArn(),
                        "DBClusterNotFoundFault", "DocDB cluster " + id + " not found.");
                yield new TagTarget("cluster:" + key(id), cluster.getTags(), updated -> {
                    cluster.setTags(updated);
                    clusters.put(key(id), cluster);
                });
            }
            case "db" -> {
                DocDbInstance instance = getDbInstance(id);
                requireArnNamesRecord(resourceName, instance.getDbInstanceArn(),
                        "DBInstanceNotFound", "DocDB instance " + id + " not found.");
                yield new TagTarget("instance:" + key(id), instance.getTags(), updated -> {
                    instance.setTags(updated);
                    instances.put(key(id), instance);
                });
            }
            default -> throw new AwsException("InvalidParameterValue",
                    "Tagging for resource type '" + type + "' is not yet implemented by Floci: " + resourceName, 400);
        };
    }
}
