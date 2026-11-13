package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.efs.EfsException;
import io.floci.aws.services.efs.EfsService;
import io.floci.aws.services.efs.model.AccessPointDescription;
import io.floci.aws.services.efs.model.BackupPolicy;
import io.floci.aws.services.efs.model.CreateAccessPointRequest;
import io.floci.aws.services.efs.model.CreateFileSystemRequest;
import io.floci.aws.services.efs.model.CreateMountTargetRequest;
import io.floci.aws.services.efs.model.CreationInfo;
import io.floci.aws.services.efs.model.DescribeMountTargetsRequest;
import io.floci.aws.services.efs.model.FileSystem;
import io.floci.aws.services.efs.model.IpAddressType;
import io.floci.aws.services.efs.model.LifecyclePolicy;
import io.floci.aws.services.efs.model.ModifyMountTargetSecurityGroupsRequest;
import io.floci.aws.services.efs.model.MountTarget;
import io.floci.aws.services.efs.model.PerformanceMode;
import io.floci.aws.services.efs.model.PosixUser;
import io.floci.aws.services.efs.model.ReplicationOverwriteProtection;
import io.floci.aws.services.efs.model.RootDirectory;
import io.floci.aws.services.efs.model.Tag;
import io.floci.aws.services.efs.model.TagResourceRequest;
import io.floci.aws.services.efs.model.ThroughputMode;
import io.floci.aws.services.efs.model.UpdateFileSystemProtectionRequest;
import io.floci.aws.services.efs.model.UpdateFileSystemRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for EFS: {@code AWS::EFS::FileSystem}, {@code AWS::EFS::MountTarget}
 * and {@code AWS::EFS::AccessPoint}.
 *
 * <p>A file system is reconciled in place for the properties AWS updates without interruption
 * (throughput, backup policy, lifecycle policies, file system policy, replication overwrite
 * protection and tags). A mount target updates its security groups in place and an access point its
 * tags. Every other property is create-only, so a change replaces the resource. An in-place update
 * keeps a snapshot of what it changes on the resource, so a failed stack update can put it back.
 * The displaced entity of a replacement is left to the {@link ReplacementCleanup} record: deleted
 * once the update commits and named again on rollback.
 */
@ApplicationScoped
public class EfsCfnProvisioner implements CfnResourceProvisioner {

    static final String FILE_SYSTEM = "AWS::EFS::FileSystem";
    static final String MOUNT_TARGET = "AWS::EFS::MountTarget";
    static final String ACCESS_POINT = "AWS::EFS::AccessPoint";

    private static final int CREATION_TOKEN_MAX_LENGTH = 64;
    private static final String BACKUP_ENABLED = "ENABLED";
    private static final String BACKUP_DISABLED = "DISABLED";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<LifecyclePolicy>> LIFECYCLE_POLICIES =
            new TypeReference<>() { };

    private final EfsService efsService;

    @Inject
    public EfsCfnProvisioner(EfsService efsService) {
        this.efsService = efsService;
    }

    /**
     * The mutable configuration a template asks for. A null member means the template says nothing
     * and the stored value is left alone; a property the previous template declared and this one
     * dropped is carried as its AWS default, so it is reset rather than left standing.
     */
    private record Desired(ThroughputMode throughputMode, Double provisionedThroughput, String backupStatus,
                           List<LifecyclePolicy> lifecyclePolicies, String policy,
                           ReplicationOverwriteProtection protection, Map<String, String> tags) {
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(FILE_SYSTEM, MOUNT_TARGET, ACCESS_POINT);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(snapshotAttribute(r.getResourceType()));
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case FILE_SYSTEM -> provisionFileSystem(r, props, ctx);
            case MOUNT_TARGET -> provisionMountTarget(r, props, ctx);
            case ACCESS_POINT -> provisionAccessPoint(r, props, ctx);
            default -> throw new IllegalStateException("EfsCfnProvisioner cannot handle " + r.getResourceType());
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private static String snapshotAttribute(String resourceType) {
        return switch (resourceType) {
            case FILE_SYSTEM -> CfnRollback.EFS_FILE_SYSTEM_UPDATE_SNAPSHOT_ATTR;
            case MOUNT_TARGET -> CfnRollback.EFS_MOUNT_TARGET_UPDATE_SNAPSHOT_ATTR;
            case ACCESS_POINT -> CfnRollback.EFS_ACCESS_POINT_UPDATE_SNAPSHOT_ATTR;
            default -> throw new IllegalStateException("EfsCfnProvisioner cannot handle " + resourceType);
        };
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            // Only the service's own not-found code is tolerated: FileSystemInUse (mount targets
            // still exist) must fail the stack delete, not be reported as a clean deletion.
            case FILE_SYSTEM -> CfnDeletes.safeDelete("EFS file system", physicalId,
                    () -> efsService.deleteFileSystem(region, physicalId), "FileSystemNotFound");
            case MOUNT_TARGET -> CfnDeletes.safeDelete("EFS mount target", physicalId,
                    () -> efsService.deleteMountTarget(region, physicalId), "MountTargetNotFound");
            case ACCESS_POINT -> CfnDeletes.safeDelete("EFS access point", physicalId,
                    () -> efsService.deleteAccessPoint(region, physicalId), "AccessPointNotFound");
            default -> { }
        }
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(snapshotAttribute(resource.getResourceType()));
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(snapshotAttribute(resource.getResourceType()));
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(snapshotAttribute(resource.getResourceType()));
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record, and an update in place from the snapshot
     * taken before it. The snapshot is spent only once the restore succeeded, so a restore that
     * throws leaves it for ContinueUpdateRollback to retry.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String attribute = snapshotAttribute(resource.getResourceType());
        String raw = resource.getAttributes().get(attribute);
        if (raw == null) {
            return false;
        }
        restoreFrom(resource.getResourceType(), readSnapshot(raw, resource));
        resource.getAttributes().remove(attribute);
        return true;
    }

    private void provisionFileSystem(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        boolean encrypted = "true".equalsIgnoreCase(ctx.resolveOptional(props, "Encrypted"));
        String kmsKeyId = blankToNull(ctx.resolveOptional(props, "KmsKeyId"));
        PerformanceMode performanceMode = parseEnum(PerformanceMode.class,
                ctx.resolveOptional(props, "PerformanceMode"), "PerformanceMode");
        String availabilityZone = blankToNull(ctx.resolveOptional(props, "AvailabilityZoneName"));
        Desired desired = desired(props, ctx, availabilityZone != null);
        requireProvisionedThroughput(desired);

        // Encrypted, KmsKeyId, PerformanceMode and AvailabilityZoneName are create-only, so a change
        // to any of them, or a file system that is gone, is a replacement: a new file system is
        // created and the displaced one is deleted once the update commits.
        FileSystem prior = ctx.isUpdate() ? findFileSystem(ctx.priorPhysicalId(), region) : null;
        boolean reuse = prior != null
                && !createOnlyChanged(prior, encrypted, kmsKeyId, performanceMode, availabilityZone);

        FileSystem fileSystem;
        if (reuse) {
            fileSystem = prior;
            r.getAttributes().put(CfnRollback.EFS_FILE_SYSTEM_UPDATE_SNAPSHOT_ATTR,
                    snapshot(prior, region).toString());
        } else {
            if (desired.provisionedThroughput() != null && desired.throughputMode() != ThroughputMode.provisioned) {
                throw new AwsException("ValidationError",
                        "ProvisionedThroughputInMibps is only valid when ThroughputMode is provisioned.", 400);
            }
            CreateFileSystemRequest request = new CreateFileSystemRequest();
            request.setCreationToken(ctx.generatePhysicalName(r.getLogicalId(), CREATION_TOKEN_MAX_LENGTH, false));
            request.setEncrypted(encrypted);
            request.setKmsKeyId(kmsKeyId);
            request.setPerformanceMode(performanceMode);
            request.setAvailabilityZoneName(availabilityZone);
            request.setThroughputMode(desired.throughputMode());
            request.setProvisionedThroughputInMibps(desired.provisionedThroughput());
            request.setBackup(backupOnCreate(desired.backupStatus(), availabilityZone != null));
            request.setTags(toTags(desired.tags()));
            fileSystem = efsService.createFileSystem(request, region);
        }
        String fileSystemId = fileSystem.getFileSystemId();
        r.setPhysicalId(fileSystemId);
        r.getAttributes().put("Arn", fileSystem.getFileSystemArn());
        r.getAttributes().put("FileSystemId", fileSystemId);
        CfnRollback.guarded(r, ctx, !reuse,
                () -> efsService.deleteFileSystem(region, fileSystemId),
                () -> undoInPlace(r),
                () -> reconcile(fileSystemId, desired, reuse, region));
    }

    private void provisionMountTarget(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String fileSystemId = required(ctx.resolveOptional(props, "FileSystemId"), "FileSystemId", MOUNT_TARGET);
        String subnetId = required(ctx.resolveOptional(props, "SubnetId"), "SubnetId", MOUNT_TARGET);
        if (props == null || !props.hasNonNull("SecurityGroups")) {
            throw requiredProperty("SecurityGroups", MOUNT_TARGET);
        }
        String ipAddress = blankToNull(ctx.resolveOptional(props, "IpAddress"));
        IpAddressType ipAddressType = parseEnum(IpAddressType.class,
                ctx.resolveOptional(props, "IpAddressType"), "IpAddressType");
        String ipv6Address = blankToNull(ctx.resolveOptional(props, "Ipv6Address"));
        List<String> securityGroups = ctx.resolveStringList(props, "SecurityGroups");

        // FileSystemId, SubnetId, IpAddress, IpAddressType and Ipv6Address are create-only, so a
        // change to one, or a mount target that is gone, is a replacement. An address or address
        // type the template leaves out is assigned by EFS, and is never compared unless the
        // previous template declared it: removing a declared one is a change.
        MountTarget prior = ctx.isUpdate() ? findMountTarget(ctx.priorPhysicalId(), region) : null;
        boolean reuse = prior != null
                && Objects.equals(fileSystemId, prior.getFileSystemId())
                && Objects.equals(subnetId, prior.getSubnetId())
                && createOnlyUnchanged(ipAddress, prior.getIpAddress(), "IpAddress", ctx)
                && createOnlyUnchanged(ipAddressType, prior.getIpAddressType(), "IpAddressType", ctx)
                && createOnlyUnchanged(ipv6Address, prior.getIpv6Address(), "Ipv6Address", ctx);

        MountTarget mountTarget;
        if (reuse) {
            mountTarget = prior;
            ObjectNode snapshot = MAPPER.createObjectNode();
            snapshot.put("region", region);
            snapshot.put("mountTargetId", prior.getMountTargetId());
            ArrayNode priorGroups = snapshot.putArray("securityGroups");
            prior.getSecurityGroups().forEach(priorGroups::add);
            r.getAttributes().put(CfnRollback.EFS_MOUNT_TARGET_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
        } else {
            CreateMountTargetRequest request = new CreateMountTargetRequest();
            request.setFileSystemId(fileSystemId);
            request.setSubnetId(subnetId);
            request.setIpAddress(ipAddress);
            request.setIpAddressType(ipAddressType);
            request.setIpv6Address(ipv6Address);
            request.setSecurityGroups(securityGroups);
            mountTarget = efsService.createMountTarget(request, region);
        }
        String mountTargetId = mountTarget.getMountTargetId();
        r.setPhysicalId(mountTargetId);
        r.getAttributes().put("Id", mountTargetId);
        if (mountTarget.getIpAddress() != null) {
            r.getAttributes().put("IpAddress", mountTarget.getIpAddress());
        }
        CfnRollback.guarded(r, ctx, !reuse,
                () -> efsService.deleteMountTarget(region, mountTargetId),
                () -> undoInPlace(r),
                () -> {
                    if (reuse && !securityGroups.equals(mountTarget.getSecurityGroups())) {
                        modifySecurityGroups(region, mountTargetId, securityGroups);
                    }
                });
    }

    private void provisionAccessPoint(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String fileSystemId = required(ctx.resolveOptional(props, "FileSystemId"), "FileSystemId", ACCESS_POINT);
        String declaredToken = blankToNull(ctx.resolveOptional(props, "ClientToken"));
        PosixUser posixUser = posixUser(props, ctx);
        RootDirectory rootDirectory = rootDirectory(props, ctx);
        Map<String, String> tags = ctx.resolveTags(props, "AccessPointTags");

        // Everything but the tags is create-only. A ClientToken the template leaves out is generated
        // at create and never compared, unless the previous template declared one: removing it is
        // a change.
        AccessPointDescription prior = ctx.isUpdate() ? findAccessPoint(ctx.priorPhysicalId(), region) : null;
        boolean reuse = prior != null
                && Objects.equals(fileSystemId, prior.getFileSystemId())
                && createOnlyUnchanged(declaredToken, prior.getClientToken(), "ClientToken", ctx)
                && Objects.equals(MAPPER.valueToTree(posixUser), MAPPER.valueToTree(prior.getPosixUser()))
                && Objects.equals(MAPPER.valueToTree(rootDirectory), MAPPER.valueToTree(prior.getRootDirectory()));

        AccessPointDescription accessPoint;
        if (reuse) {
            accessPoint = prior;
            ObjectNode snapshot = MAPPER.createObjectNode();
            snapshot.put("region", region);
            snapshot.put("accessPointId", prior.getAccessPointId());
            ObjectNode priorTags = snapshot.putObject("tags");
            tagMap(prior.getTags()).forEach(priorTags::put);
            r.getAttributes().put(CfnRollback.EFS_ACCESS_POINT_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
        } else {
            CreateAccessPointRequest request = new CreateAccessPointRequest();
            request.setFileSystemId(fileSystemId);
            request.setClientToken(declaredToken != null ? declaredToken
                    : ctx.generatePhysicalName(r.getLogicalId(), CREATION_TOKEN_MAX_LENGTH, false));
            request.setPosixUser(posixUser);
            request.setRootDirectory(rootDirectory);
            request.setTags(toTags(tags));
            accessPoint = efsService.createAccessPoint(region, request);
        }
        String accessPointId = accessPoint.getAccessPointId();
        r.setPhysicalId(accessPointId);
        r.getAttributes().put("AccessPointId", accessPointId);
        r.getAttributes().put("Arn", accessPoint.getAccessPointArn());
        CfnRollback.guarded(r, ctx, !reuse,
                () -> efsService.deleteAccessPoint(region, accessPointId),
                () -> undoInPlace(r),
                () -> {
                    if (reuse) {
                        applyTags(accessPointId, tagMap(accessPoint.getTags()), tags, region);
                    }
                });
    }

    /**
     * Whether a create-only property is unchanged. A value the template declares must equal the
     * prior one. A value it leaves out matches whatever EFS assigned, unless the previous template
     * declared it, which makes the removal a change.
     */
    private static boolean createOnlyUnchanged(Object declared, Object prior, String property, ProvisionContext ctx) {
        if (declared == null) {
            return !ctx.previouslyDeclared(property);
        }
        return declared.equals(prior);
    }

    private static String required(String value, String property, String resourceType) {
        if (value == null || value.isBlank()) {
            throw requiredProperty(property, resourceType);
        }
        return value;
    }

    private static AwsException requiredProperty(String property, String resourceType) {
        return new AwsException("ValidationError", property + " is required for " + resourceType + ".", 400);
    }

    private MountTarget findMountTarget(String mountTargetId, String region) {
        DescribeMountTargetsRequest request = new DescribeMountTargetsRequest();
        request.setMountTargetId(mountTargetId);
        List<MountTarget> found = efsService.describeMountTargets(region, request).getMountTargets();
        return found.isEmpty() ? null : found.get(0);
    }

    private AccessPointDescription findAccessPoint(String accessPointId, String region) {
        List<AccessPointDescription> found = efsService.describeAccessPoints(region, null, accessPointId);
        return found.isEmpty() ? null : found.get(0);
    }

    private PosixUser posixUser(JsonNode props, ProvisionContext ctx) {
        JsonNode node = resolvedProperty(props, "PosixUser", ctx);
        if (node == null) {
            return null;
        }
        PosixUser user = new PosixUser();
        user.setUid(parseLong(node.path("Uid"), "PosixUser.Uid"));
        user.setGid(parseLong(node.path("Gid"), "PosixUser.Gid"));
        if (node.path("SecondaryGids").isArray()) {
            List<Long> secondary = new ArrayList<>();
            for (JsonNode gid : node.path("SecondaryGids")) {
                secondary.add(parseLong(gid, "PosixUser.SecondaryGids"));
            }
            user.setSecondaryGids(secondary);
        }
        return user;
    }

    private RootDirectory rootDirectory(JsonNode props, ProvisionContext ctx) {
        JsonNode node = resolvedProperty(props, "RootDirectory", ctx);
        if (node == null) {
            return null;
        }
        RootDirectory directory = new RootDirectory();
        directory.setPath(blankToNull(node.path("Path").asText(null)));
        JsonNode info = node.path("CreationInfo");
        if (info.isObject()) {
            CreationInfo creationInfo = new CreationInfo();
            creationInfo.setOwnerUid(parseLong(info.path("OwnerUid"), "RootDirectory.CreationInfo.OwnerUid"));
            creationInfo.setOwnerGid(parseLong(info.path("OwnerGid"), "RootDirectory.CreationInfo.OwnerGid"));
            creationInfo.setPermissions(blankToNull(info.path("Permissions").asText(null)));
            directory.setCreationInfo(creationInfo);
        }
        return directory;
    }

    private static Long parseLong(JsonNode value, String property) {
        if (value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.asText().trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError", "Value of property " + property + " must be a number.", 400);
        }
    }

    private static Map<String, String> tagMap(List<Tag> tags) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tags != null) {
            for (Tag tag : tags) {
                result.put(tag.getKey(), tag.getValue());
            }
        }
        return result;
    }

    private void modifySecurityGroups(String region, String mountTargetId, List<String> securityGroups) {
        ModifyMountTargetSecurityGroupsRequest request = new ModifyMountTargetSecurityGroupsRequest();
        request.setMountTargetId(mountTargetId);
        request.setSecurityGroups(securityGroups);
        efsService.modifyMountTargetSecurityGroups(region, mountTargetId, request);
    }

    private void restoreMountTarget(JsonNode snapshot) {
        List<String> securityGroups = new ArrayList<>();
        snapshot.path("securityGroups").forEach(group -> securityGroups.add(group.asText()));
        modifySecurityGroups(snapshot.path("region").asText(), snapshot.path("mountTargetId").asText(),
                securityGroups);
    }

    private void restoreAccessPoint(JsonNode snapshot) {
        String region = snapshot.path("region").asText();
        String accessPointId = snapshot.path("accessPointId").asText();
        Map<String, String> tags = new LinkedHashMap<>();
        snapshot.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        AccessPointDescription accessPoint = findAccessPoint(accessPointId, region);
        if (accessPoint == null) {
            throw EfsException.accessPointNotFound(accessPointId);
        }
        applyTags(accessPointId, tagMap(accessPoint.getTags()), tags, region);
    }

    /**
     * CreateFileSystem turns automatic backups on by default only for a One Zone file system, so
     * a template that leaves {@code BackupPolicy} out gets them on for one and off for a regional
     * one.
     */
    private static boolean backupOnCreate(String declaredStatus, boolean oneZone) {
        if (declaredStatus != null) {
            return BACKUP_ENABLED.equals(declaredStatus);
        }
        return oneZone;
    }

    /** AWS refuses {@code ThroughputMode: provisioned} with no throughput to provision. */
    private static void requireProvisionedThroughput(Desired desired) {
        if (desired.throughputMode() == ThroughputMode.provisioned && desired.provisionedThroughput() == null) {
            throw new AwsException("ValidationError",
                    "ProvisionedThroughputInMibps is required when ThroughputMode is provisioned.", 400);
        }
    }

    private Desired desired(JsonNode props, ProvisionContext ctx, boolean oneZone) {
        ThroughputMode throughputMode = parseEnum(ThroughputMode.class,
                ctx.resolveOptional(props, "ThroughputMode"), "ThroughputMode");
        if (throughputMode == null && ctx.previouslyDeclared("ThroughputMode")) {
            throughputMode = ThroughputMode.bursting;
        }
        Double provisionedThroughput = null;
        String provisionedText = ctx.resolveOptional(props, "ProvisionedThroughputInMibps");
        if (provisionedText != null && !provisionedText.isBlank()) {
            try {
                provisionedThroughput = Double.valueOf(provisionedText.trim());
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError",
                        "Value of property ProvisionedThroughputInMibps must be a number.", 400);
            }
        }

        JsonNode backup = resolvedProperty(props, "BackupPolicy", ctx);
        String backupStatus = backup == null ? null : blankToNull(backup.path("Status").asText(null));
        if (backupStatus != null) {
            backupStatus = backupStatus.toUpperCase();
        } else if (ctx.previouslyDeclared("BackupPolicy")) {
            backupStatus = oneZone ? BACKUP_ENABLED : BACKUP_DISABLED;
        }

        List<LifecyclePolicy> lifecyclePolicies = null;
        JsonNode lifecycle = resolvedProperty(props, "LifecyclePolicies", ctx);
        if (lifecycle != null && lifecycle.isArray()) {
            lifecyclePolicies = new ArrayList<>();
            for (JsonNode item : lifecycle) {
                if (item.isObject()) {
                    LifecyclePolicy policy = new LifecyclePolicy();
                    policy.setTransitionToIA(item.path("TransitionToIA").asText(null));
                    policy.setTransitionToPrimaryStorageClass(
                            item.path("TransitionToPrimaryStorageClass").asText(null));
                    policy.setTransitionToArchive(item.path("TransitionToArchive").asText(null));
                    lifecyclePolicies.add(policy);
                }
            }
        } else if (ctx.previouslyDeclared("LifecyclePolicies")) {
            lifecyclePolicies = new ArrayList<>();
        }

        // An empty string means "remove the policy", for a template that dropped a declared one.
        String policy = null;
        if (props != null && props.hasNonNull("FileSystemPolicy")) {
            policy = ctx.engine().resolveJsonAttribute(props.get("FileSystemPolicy"));
        }
        if (policy == null || policy.isBlank()) {
            policy = ctx.previouslyDeclared("FileSystemPolicy") ? "" : null;
        }

        ReplicationOverwriteProtection protection = null;
        JsonNode protectionNode = resolvedProperty(props, "FileSystemProtection", ctx);
        if (protectionNode != null) {
            protection = parseEnum(ReplicationOverwriteProtection.class,
                    protectionNode.path("ReplicationOverwriteProtection").asText(null),
                    "ReplicationOverwriteProtection");
        }
        if (protection == null && ctx.previouslyDeclared("FileSystemProtection")) {
            protection = ReplicationOverwriteProtection.ENABLED;
        }

        return new Desired(throughputMode, provisionedThroughput, backupStatus, lifecyclePolicies, policy,
                protection, ctx.resolveTags(props, "FileSystemTags"));
    }

    /** The property resolved through the template, or null when it is absent, null or AWS::NoValue. */
    private static JsonNode resolvedProperty(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull(name)) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(props.get(name));
        return resolved == null || resolved.isMissingNode() || resolved.isNull() ? null : resolved;
    }

    private FileSystem findFileSystem(String fileSystemId, String region) {
        try {
            return efsService.getFileSystem(region, fileSystemId);
        } catch (AwsException e) {
            if (!"FileSystemNotFound".equals(e.getErrorCode())) {
                throw e;
            }
            return null;
        }
    }

    private static boolean createOnlyChanged(FileSystem prior, boolean encrypted, String kmsKeyId,
                                             PerformanceMode performanceMode, String availabilityZone) {
        String desiredPerformance = performanceMode == null ? PerformanceMode.generalPurpose.name()
                : performanceMode.name();
        return Boolean.TRUE.equals(prior.getEncrypted()) != encrypted
                || !Objects.equals(prior.getKmsKeyId(), kmsKeyId)
                || !desiredPerformance.equals(prior.getPerformanceMode())
                || !Objects.equals(prior.getAvailabilityZoneName(), availabilityZone);
    }

    /**
     * Drives an existing file system to the template's desired state, and sets what creation does
     * not take (lifecycle, policy, protection) on a new one. A null member of {@code desired} is
     * left alone.
     */
    private void reconcile(String fileSystemId, Desired desired, boolean existing, String region) {
        if (existing && (desired.throughputMode() != null || desired.provisionedThroughput() != null)) {
            UpdateFileSystemRequest update = new UpdateFileSystemRequest();
            update.setThroughputMode(desired.throughputMode());
            update.setProvisionedThroughputInMibps(desired.provisionedThroughput());
            efsService.updateFileSystem(region, fileSystemId, update);
        }
        if (existing && desired.backupStatus() != null) {
            efsService.putBackupPolicy(region, fileSystemId, backupPolicy(desired.backupStatus()));
        }
        if (desired.lifecyclePolicies() != null
                && (existing || !desired.lifecyclePolicies().isEmpty())) {
            efsService.putLifecycleConfiguration(region, fileSystemId, desired.lifecyclePolicies());
        }
        if (desired.policy() != null) {
            if (desired.policy().isEmpty()) {
                efsService.deleteFileSystemPolicy(region, fileSystemId);
            } else {
                efsService.putFileSystemPolicy(region, fileSystemId, desired.policy());
            }
        }
        if (desired.protection() != null) {
            UpdateFileSystemProtectionRequest protection = new UpdateFileSystemProtectionRequest();
            protection.setReplicationOverwriteProtection(desired.protection());
            efsService.updateFileSystemProtection(region, fileSystemId, protection);
        }
        if (existing) {
            reconcileTags(fileSystemId, desired.tags(), region);
        }
    }

    /** Drives the tags to the template's set: a dropped key is untagged, as CloudFormation does. */
    private void reconcileTags(String fileSystemId, Map<String, String> desired, String region) {
        applyTags(fileSystemId, tagMap(efsService.getFileSystem(region, fileSystemId).getTags()), desired, region);
    }

    private void applyTags(String resourceId, Map<String, String> current, Map<String, String> desired,
                           String region) {
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            efsService.untagResource(region, resourceId, stale);
        }
        if (!desired.isEmpty()) {
            TagResourceRequest request = new TagResourceRequest();
            request.setResourceId(resourceId);
            request.setTags(toTags(desired));
            efsService.tagResource(region, request);
        }
    }

    private ObjectNode snapshot(FileSystem fileSystem, String region) {
        String fileSystemId = fileSystem.getFileSystemId();
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("fileSystemId", fileSystemId);
        snapshot.put("throughputMode", fileSystem.getThroughputMode());
        if (fileSystem.getProvisionedThroughputInMibps() != null) {
            snapshot.put("provisionedThroughputInMibps", fileSystem.getProvisionedThroughputInMibps());
        }
        snapshot.put("backupStatus", efsService.getBackupPolicy(region, fileSystemId).getStatus());
        snapshot.set("lifecyclePolicies",
                MAPPER.valueToTree(efsService.getLifecycleConfiguration(region, fileSystemId)));
        try {
            snapshot.put("policy", efsService.getFileSystemPolicy(region, fileSystemId));
        } catch (AwsException e) {
            if (!"PolicyNotFound".equals(e.getErrorCode())) {
                throw e;
            }
        }
        if (fileSystem.getFileSystemProtection() != null
                && fileSystem.getFileSystemProtection().getReplicationOverwriteProtection() != null) {
            snapshot.put("protection",
                    fileSystem.getFileSystemProtection().getReplicationOverwriteProtection().name());
        }
        ObjectNode tags = snapshot.putObject("tags");
        for (Tag tag : fileSystem.getTags()) {
            tags.put(tag.getKey(), tag.getValue());
        }
        return snapshot;
    }

    private JsonNode readSnapshot(String raw, StackResource resource) {
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the EFS file system update snapshot for "
                    + resource.getLogicalId(), e);
        }
    }

    private void restore(JsonNode snapshot) {
        String region = snapshot.path("region").asText();
        String fileSystemId = snapshot.path("fileSystemId").asText();
        UpdateFileSystemRequest throughput = new UpdateFileSystemRequest();
        throughput.setThroughputMode(parseEnum(ThroughputMode.class,
                snapshot.path("throughputMode").asText(null), "ThroughputMode"));
        if (snapshot.hasNonNull("provisionedThroughputInMibps")) {
            throughput.setProvisionedThroughputInMibps(snapshot.get("provisionedThroughputInMibps").asDouble());
        }
        efsService.updateFileSystem(region, fileSystemId, throughput);
        efsService.putBackupPolicy(region, fileSystemId, backupPolicy(snapshot.path("backupStatus").asText()));
        efsService.putLifecycleConfiguration(region, fileSystemId,
                MAPPER.convertValue(snapshot.path("lifecyclePolicies"), LIFECYCLE_POLICIES));
        if (snapshot.hasNonNull("policy")) {
            efsService.putFileSystemPolicy(region, fileSystemId, snapshot.get("policy").asText());
        } else {
            efsService.deleteFileSystemPolicy(region, fileSystemId);
        }
        if (snapshot.hasNonNull("protection")) {
            UpdateFileSystemProtectionRequest protection = new UpdateFileSystemProtectionRequest();
            protection.setReplicationOverwriteProtection(
                    ReplicationOverwriteProtection.valueOf(snapshot.get("protection").asText()));
            efsService.updateFileSystemProtection(region, fileSystemId, protection);
        }
        Map<String, String> tags = new LinkedHashMap<>();
        snapshot.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        reconcileTags(fileSystemId, tags, region);
    }

    private void undoInPlace(StackResource r) {
        String attribute = snapshotAttribute(r.getResourceType());
        String raw = r.getAttributes().get(attribute);
        if (raw == null) {
            return;
        }
        restoreFrom(r.getResourceType(), readSnapshot(raw, r));
        r.getAttributes().remove(attribute);
    }

    private void restoreFrom(String resourceType, JsonNode snapshot) {
        switch (resourceType) {
            case FILE_SYSTEM -> restore(snapshot);
            case MOUNT_TARGET -> restoreMountTarget(snapshot);
            case ACCESS_POINT -> restoreAccessPoint(snapshot);
            default -> throw new IllegalStateException("EfsCfnProvisioner cannot handle " + resourceType);
        }
    }

    private static BackupPolicy backupPolicy(String status) {
        BackupPolicy policy = new BackupPolicy();
        policy.setStatus(status);
        return policy;
    }

    private static List<Tag> toTags(Map<String, String> tags) {
        List<Tag> result = new ArrayList<>();
        tags.forEach((key, value) -> {
            Tag tag = new Tag();
            tag.setKey(key);
            tag.setValue(value);
            result.add(tag);
        });
        return result;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String property) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value.trim())) {
                return constant;
            }
        }
        throw new AwsException("ValidationError", "Value '" + value + "' is not valid for property "
                + property + ".", 400);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
