package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.efs.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

@ApplicationScoped
public class EfsService implements Resettable {

    private static final Logger LOG = Logger.getLogger(EfsService.class);

    private final StorageBackend<String, FileSystem> fileSystemStore;
    private final StorageBackend<String, MountTarget> mountTargetStore;
    private final StorageBackend<String, AccessPointDescription> accessPointStore;
    private final StorageBackend<String, String> fileSystemPolicyStore;
    private final StorageBackend<String, BackupPolicy> backupPolicyStore;
    private final StorageBackend<String, List<LifecyclePolicy>> lifecycleConfigurationStore;
    private final ConcurrentHashMap<String, Object> syncLocks = new ConcurrentHashMap<>();

    private Object lockFor(String key) {
        return syncLocks.computeIfAbsent(key, k -> new Object());
    }

    @Inject
    public EfsService(StorageFactory storageFactory) {
        this.fileSystemStore = storageFactory.create("efs", "efs-filesystems.json",
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, FileSystem>>() {});
        this.mountTargetStore = storageFactory.create("efs", "efs-mounttargets.json",
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, MountTarget>>() {});
        this.accessPointStore = storageFactory.create("efs", "efs-accesspoints.json",
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, AccessPointDescription>>() {});
        this.fileSystemPolicyStore = storageFactory.create("efs", "efs-policies.json",
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {});
        this.backupPolicyStore = storageFactory.create("efs", "efs-backuppolicies.json",
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, BackupPolicy>>() {});
        this.lifecycleConfigurationStore = storageFactory.create("efs", "efs-lifecycle.json",
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, List<LifecyclePolicy>>>() {});
    }

    @Override
    public void clear() {
        fileSystemStore.clear();
        mountTargetStore.clear();
        accessPointStore.clear();
        fileSystemPolicyStore.clear();
        backupPolicyStore.clear();
        lifecycleConfigurationStore.clear();
        syncLocks.clear();
    }

    // --- File Systems ---

    public FileSystem createFileSystem(CreateFileSystemRequest request, String region) {
        String token = request.getCreationToken() != null ? request.getCreationToken() : UUID.randomUUID().toString();

        synchronized (lockFor(region + "::create::" + token)) {
        for (FileSystem existing : fileSystemStore.scan(k -> k.startsWith(region + "::"))) {
            if (token.equals(existing.getCreationToken())) {
                boolean match = true;
                
                String reqPerfMode = request.getPerformanceMode() != null ? request.getPerformanceMode().name() : "generalPurpose";
                String extPerfMode = existing.getPerformanceMode();
                if (!java.util.Objects.equals(reqPerfMode, extPerfMode)) match = false;
                
                String reqTpMode = request.getThroughputMode() != null ? request.getThroughputMode().name() : "bursting";
                String extTpMode = existing.getThroughputMode();
                if (!java.util.Objects.equals(reqTpMode, extTpMode)) match = false;

                Boolean reqEnc = request.getEncrypted() != null ? request.getEncrypted() : Boolean.FALSE;
                Boolean extEnc = existing.getEncrypted() != null ? existing.getEncrypted() : Boolean.FALSE;
                if (!java.util.Objects.equals(reqEnc, extEnc)) match = false;
                
                if (!java.util.Objects.equals(request.getKmsKeyId(), existing.getKmsKeyId())) match = false;
                if (!java.util.Objects.equals(request.getProvisionedThroughputInMibps(), existing.getProvisionedThroughputInMibps())) match = false;
                
                if (!java.util.Objects.equals(request.getAvailabilityZoneName(), existing.getAvailabilityZoneName())) match = false;
                
                Boolean reqBackup = request.getBackup() != null ? request.getBackup() : Boolean.TRUE;
                io.github.hectorvent.floci.services.efs.model.BackupPolicy extBackupPolicy = backupPolicyStore.get(regionKey(region, existing.getFileSystemId())).orElse(null);
                Boolean extBackup = extBackupPolicy != null && "ENABLED".equals(extBackupPolicy.getStatus());
                if (!java.util.Objects.equals(reqBackup, extBackup)) match = false;
                
                java.util.List<io.github.hectorvent.floci.services.efs.model.Tag> reqTags = request.getTags() != null ? request.getTags() : java.util.Collections.emptyList();
                java.util.List<io.github.hectorvent.floci.services.efs.model.Tag> extTags = existing.getTags() != null ? existing.getTags() : java.util.Collections.emptyList();
                if (reqTags.size() != extTags.size() || !reqTags.containsAll(extTags)) match = false;
                
                if (!match) {
                    throw EfsException.idempotentParameterMismatch();
                }
                return existing;
            }
        }

        String fsId = "fs-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        String regionKey = regionKey(region, fsId);
        
        FileSystem fs = new FileSystem();
        fs.setFileSystemId(fsId);
        fs.setCreationToken(token);
        fs.setCreationTime(Instant.now().getEpochSecond());
        fs.setLifeCycleState(LifeCycleState.available.name());
        fs.setFileSystemArn("arn:aws:elasticfilesystem:" + region + ":000000000000:file-system/" + fsId);
        fs.setOwnerId("000000000000");
        fs.setNumberOfMountTargets(0);
        fs.setPerformanceMode(request.getPerformanceMode() != null ? request.getPerformanceMode().name() : "generalPurpose");
        fs.setThroughputMode(request.getThroughputMode() != null ? request.getThroughputMode().name() : "bursting");
        if (request.getProvisionedThroughputInMibps() != null) {
            fs.setProvisionedThroughputInMibps(request.getProvisionedThroughputInMibps());
        }
        fs.setEncrypted(request.getEncrypted() != null ? request.getEncrypted() : false);
        fs.setKmsKeyId(request.getKmsKeyId());
        if (request.getAvailabilityZoneName() != null) {
            fs.setAvailabilityZoneName(request.getAvailabilityZoneName());
            fs.setAvailabilityZoneId(request.getAvailabilityZoneName() + "-id");
        }
        
        if (request.getTags() != null) {
            fs.setTags(new ArrayList<>(request.getTags()));
        } else {
            fs.setTags(new ArrayList<>());
        }

        FileSystemSize size = new FileSystemSize();
        size.setValue(0L);
        size.setValueInIA(0L);
        size.setValueInStandard(0L);
        fs.setSizeInBytes(size);

        fileSystemStore.put(regionKey, fs);
        
        io.github.hectorvent.floci.services.efs.model.BackupPolicy bp = new io.github.hectorvent.floci.services.efs.model.BackupPolicy();
        bp.setStatus(request.getBackup() != null && !request.getBackup() ? "DISABLED" : "ENABLED");
        backupPolicyStore.put(regionKey, bp);
        
        return fs;
        }
    }

    public List<FileSystem> describeFileSystems(String region) {
        return fileSystemStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(fs -> fs.getFileSystemArn().contains(":" + region + ":"))
                .collect(Collectors.toList());
    }

    public FileSystem getFileSystem(String region, String fileSystemId) {
        FileSystem fs = fileSystemStore.get(regionKey(region, fileSystemId)).orElse(null);
        if (fs == null) {
            throw EfsException.fileSystemNotFound(fileSystemId);
        }
        return fs;
    }

    public FileSystem updateFileSystem(String region, String fileSystemId, UpdateFileSystemRequest request) {
        synchronized (lockFor(regionKey(region, fileSystemId))) {
            FileSystem fs = getFileSystem(region, fileSystemId);
            if (request.getThroughputMode() != null) {
            fs.setThroughputMode(request.getThroughputMode().name());
        }
        if (request.getProvisionedThroughputInMibps() != null) {
            fs.setProvisionedThroughputInMibps(request.getProvisionedThroughputInMibps());
        }
        fileSystemStore.put(regionKey(region, fileSystemId), fs);
        return fs;
        }
    }

    public void deleteFileSystem(String region, String fileSystemId) {
        String key = regionKey(region, fileSystemId);
        synchronized (lockFor(key)) {
            if (fileSystemStore.get(key).isEmpty()) {
                throw EfsException.fileSystemNotFound(fileSystemId);
            }

        List<MountTarget> mountTargets = describeMountTargets(region, fileSystemId);
        if (!mountTargets.isEmpty()) {
            throw EfsException.fileSystemInUse(fileSystemId);
        }

        // Clean up access points
        List<AccessPointDescription> accessPoints = describeAccessPoints(region, fileSystemId, null);
        for (AccessPointDescription ap : accessPoints) {
            accessPointStore.delete(regionKey(region, ap.getAccessPointId()));
        }

        // Clean up policies
        fileSystemPolicyStore.delete(key);
        backupPolicyStore.delete(key);
        lifecycleConfigurationStore.delete(key);

        fileSystemStore.delete(key);
        }
    }

    // --- Tags ---

    public void createTags(String region, String fileSystemId, CreateTagsRequest request) {
        synchronized (lockFor(regionKey(region, fileSystemId))) {
            FileSystem fs = getFileSystem(region, fileSystemId);
            List<Tag> existing = fs.getTags();
        if (request.getTags() != null) {
            for (Tag newTag : request.getTags()) {
                existing.removeIf(t -> t.getKey().equals(newTag.getKey()));
                existing.add(newTag);
            }
        }
        fileSystemStore.put(regionKey(region, fileSystemId), fs);
        }
    }

    public void deleteTags(String region, String fileSystemId, DeleteTagsRequest request) {
        synchronized (lockFor(regionKey(region, fileSystemId))) {
            FileSystem fs = getFileSystem(region, fileSystemId);
            if (request.getTagKeys() != null) {
            fs.getTags().removeIf(t -> request.getTagKeys().contains(t.getKey()));
        }
        fileSystemStore.put(regionKey(region, fileSystemId), fs);
        }
    }

    // --- Mount Targets ---

    public MountTarget createMountTarget(CreateMountTargetRequest request, String region) {
        synchronized (lockFor(regionKey(region, request.getFileSystemId()))) {
            FileSystem fs = getFileSystem(region, request.getFileSystemId());
            String mtId = "fsmt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        
        MountTarget mt = new MountTarget();
        mt.setMountTargetId(mtId);
        mt.setFileSystemId(request.getFileSystemId());
        mt.setSubnetId(request.getSubnetId());
        mt.setIpAddress(request.getIpAddress() != null ? request.getIpAddress() : "10.0.0.10");
        mt.setLifeCycleState(LifeCycleState.available);
        mt.setVpcId("vpc-12345678");
        mt.setAvailabilityZoneId("use1-az1");
        mt.setAvailabilityZoneName("us-east-1a");
        mt.setNetworkInterfaceId("eni-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17));
        if (request.getSecurityGroups() != null) {
            mt.setSecurityGroups(new ArrayList<>(request.getSecurityGroups()));
        } else {
            mt.setSecurityGroups(new ArrayList<>());
        }

        fs.setNumberOfMountTargets(fs.getNumberOfMountTargets() + 1);
        fileSystemStore.put(regionKey(region, fs.getFileSystemId()), fs);
        
        mountTargetStore.put(regionKey(region, mtId), mt);
        return mt;
        }
    }

    public List<MountTarget> describeMountTargets(String region, String fileSystemId) {
        return mountTargetStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(mt -> fileSystemId == null || mt.getFileSystemId().equals(fileSystemId))
                .collect(Collectors.toList());
    }

    public void deleteMountTarget(String region, String mountTargetId) {
        String key = regionKey(region, mountTargetId);
        synchronized (lockFor(key)) {
            MountTarget mt = mountTargetStore.get(key).orElse(null);
            if (mt == null) {
                throw EfsException.mountTargetNotFound(mountTargetId);
            }
            
            try {
                synchronized (lockFor(regionKey(region, mt.getFileSystemId()))) {
                    FileSystem fs = getFileSystem(region, mt.getFileSystemId());
                fs.setNumberOfMountTargets(Math.max(0, fs.getNumberOfMountTargets() - 1));
                    fileSystemStore.put(regionKey(region, fs.getFileSystemId()), fs);
                }
            } catch (EfsException e) {
                LOG.debug("File system " + mt.getFileSystemId() + " already deleted, skipping parent count update during mount target deletion");
            } catch (Exception e) {
                LOG.error("Failed to update parent file system " + mt.getFileSystemId() + " count during mount target deletion", e);
            }
            
            mountTargetStore.delete(key);
        }
    }

    public DescribeMountTargetSecurityGroupsResponse describeMountTargetSecurityGroups(String region, String mountTargetId) {
        MountTarget mt = mountTargetStore.get(regionKey(region, mountTargetId)).orElse(null);
        if (mt == null) {
            throw EfsException.mountTargetNotFound(mountTargetId);
        }
        DescribeMountTargetSecurityGroupsResponse res = new DescribeMountTargetSecurityGroupsResponse();
        res.setSecurityGroups(mt.getSecurityGroups());
        return res;
    }

    public void modifyMountTargetSecurityGroups(String region, String mountTargetId, ModifyMountTargetSecurityGroupsRequest request) {
        synchronized (lockFor(regionKey(region, mountTargetId))) {
            MountTarget mt = mountTargetStore.get(regionKey(region, mountTargetId)).orElse(null);
            if (mt == null) {
                throw EfsException.mountTargetNotFound(mountTargetId);
            }
            if (request.getSecurityGroups() != null) {
                mt.setSecurityGroups(new ArrayList<>(request.getSecurityGroups()));
            }
            mountTargetStore.put(regionKey(region, mountTargetId), mt);
        }
    }

    // --- Access Points ---

    public AccessPointDescription createAccessPoint(String region, CreateAccessPointRequest request) {
        String token = request.getClientToken() != null ? request.getClientToken() : UUID.randomUUID().toString();
        synchronized (lockFor(regionKey(region, "ap-token::" + token))) {
            synchronized (lockFor(regionKey(region, request.getFileSystemId()))) {
                // Validate file system exists
                getFileSystem(region, request.getFileSystemId());

                for (AccessPointDescription existing : accessPointStore.scan(k -> k.startsWith(region + "::"))) {
                    if (token.equals(existing.getClientToken())) {
                        boolean match = true;
                        if (!request.getFileSystemId().equals(existing.getFileSystemId())) match = false;
                        
                        java.util.List<io.github.hectorvent.floci.services.efs.model.Tag> reqTags = request.getTags() != null ? request.getTags() : java.util.Collections.emptyList();
                        java.util.List<io.github.hectorvent.floci.services.efs.model.Tag> extTags = existing.getTags() != null ? existing.getTags() : java.util.Collections.emptyList();
                        if (reqTags.size() != extTags.size() || !reqTags.containsAll(extTags)) match = false;
                        
                        // Compare PosixUser
                        if (request.getPosixUser() != null && existing.getPosixUser() == null) match = false;
                        if (request.getPosixUser() == null && existing.getPosixUser() != null) match = false;
                        if (request.getPosixUser() != null && existing.getPosixUser() != null) {
                            if (!java.util.Objects.equals(request.getPosixUser().getUid(), existing.getPosixUser().getUid())) match = false;
                            if (!java.util.Objects.equals(request.getPosixUser().getGid(), existing.getPosixUser().getGid())) match = false;
                            
                            java.util.List<Long> reqGids = request.getPosixUser().getSecondaryGids();
                            java.util.List<Long> extGids = existing.getPosixUser().getSecondaryGids();
                            if (reqGids != null && extGids == null) match = false;
                            if (reqGids == null && extGids != null) match = false;
                            if (reqGids != null && extGids != null) {
                                if (reqGids.size() != extGids.size() || !reqGids.containsAll(extGids)) match = false;
                            }
                        }
                        
                        // Compare RootDirectory
                        if (request.getRootDirectory() != null && existing.getRootDirectory() == null) match = false;
                        if (request.getRootDirectory() == null && existing.getRootDirectory() != null) match = false;
                        if (request.getRootDirectory() != null && existing.getRootDirectory() != null) {
                            if (!java.util.Objects.equals(request.getRootDirectory().getPath(), existing.getRootDirectory().getPath())) match = false;
                            
                            io.github.hectorvent.floci.services.efs.model.CreationInfo reqCi = request.getRootDirectory().getCreationInfo();
                            io.github.hectorvent.floci.services.efs.model.CreationInfo extCi = existing.getRootDirectory().getCreationInfo();
                            if (reqCi != null && extCi == null) match = false;
                            if (reqCi == null && extCi != null) match = false;
                            if (reqCi != null && extCi != null) {
                                if (!java.util.Objects.equals(reqCi.getOwnerUid(), extCi.getOwnerUid())) match = false;
                                if (!java.util.Objects.equals(reqCi.getOwnerGid(), extCi.getOwnerGid())) match = false;
                                if (!java.util.Objects.equals(reqCi.getPermissions(), extCi.getPermissions())) match = false;
                            }
                        }
                        
                        if (!match) {
                            throw EfsException.idempotentParameterMismatch();
                        }
                        return existing;
                    }
                }

        String apId = "fsap-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        
        AccessPointDescription ap = new AccessPointDescription();
        ap.setAccessPointId(apId);
        ap.setAccessPointArn("arn:aws:elasticfilesystem:" + region + ":000000000000:access-point/" + apId);
        ap.setClientToken(token);
        ap.setFileSystemId(request.getFileSystemId());
        ap.setPosixUser(request.getPosixUser());
        ap.setRootDirectory(request.getRootDirectory());
        if (request.getTags() != null) {
            ap.setTags(new ArrayList<>(request.getTags()));
        }
        ap.setLifeCycleState(LifeCycleState.available);
        ap.setOwnerId("000000000000");
        
        accessPointStore.put(regionKey(region, apId), ap);
        return ap;
            }
        }
    }

    public List<AccessPointDescription> describeAccessPoints(String region, String fileSystemId, String accessPointId) {
        return accessPointStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(ap -> fileSystemId == null || ap.getFileSystemId().equals(fileSystemId))
                .filter(ap -> accessPointId == null || ap.getAccessPointId().equals(accessPointId))
                .collect(Collectors.toList());
    }

    public void deleteAccessPoint(String region, String accessPointId) {
        String key = regionKey(region, accessPointId);
        if (accessPointStore.get(key).isEmpty()) {
            throw EfsException.accessPointNotFound(accessPointId);
        }
        accessPointStore.delete(key);
    }

    // --- Policies ---

    public void putFileSystemPolicy(String region, String fileSystemId, String policy) {
        synchronized (lockFor(regionKey(region, fileSystemId))) {
            getFileSystem(region, fileSystemId); // check exists
            fileSystemPolicyStore.put(regionKey(region, fileSystemId), policy);
        }
    }

    public String getFileSystemPolicy(String region, String fileSystemId) {
        getFileSystem(region, fileSystemId);
        String policy = fileSystemPolicyStore.get(regionKey(region, fileSystemId)).orElse(null);
        if (policy == null) {
            throw EfsException.policyNotFound(fileSystemId);
        }
        return policy;
    }

    public void deleteFileSystemPolicy(String region, String fileSystemId) {
        synchronized (lockFor(regionKey(region, fileSystemId))) {
            getFileSystem(region, fileSystemId);
            fileSystemPolicyStore.delete(regionKey(region, fileSystemId));
        }
    }

    public void putBackupPolicy(String region, String fileSystemId, BackupPolicy policy) {
        synchronized (lockFor(regionKey(region, fileSystemId))) {
            getFileSystem(region, fileSystemId);
            backupPolicyStore.put(regionKey(region, fileSystemId), policy);
        }
    }

    public BackupPolicy getBackupPolicy(String region, String fileSystemId) {
        getFileSystem(region, fileSystemId);
        BackupPolicy policy = backupPolicyStore.get(regionKey(region, fileSystemId)).orElse(null);
        if (policy == null) {
            policy = new BackupPolicy();
            policy.setStatus("DISABLED");
        }
        return policy;
    }

    public void putLifecycleConfiguration(String region, String fileSystemId, List<LifecyclePolicy> policies) {
        synchronized (lockFor(regionKey(region, fileSystemId))) {
            getFileSystem(region, fileSystemId);
            lifecycleConfigurationStore.put(regionKey(region, fileSystemId), new ArrayList<>(policies));
        }
    }

    public List<LifecyclePolicy> getLifecycleConfiguration(String region, String fileSystemId) {
        getFileSystem(region, fileSystemId);
        List<LifecyclePolicy> policies = lifecycleConfigurationStore.get(regionKey(region, fileSystemId)).orElse(null);
        return policies == null ? new ArrayList<>() : policies;
    }

    private String regionKey(String region, String id) {
        return region + "::" + id;
    }
}
