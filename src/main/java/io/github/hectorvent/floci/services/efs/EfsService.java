package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.efs.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.github.hectorvent.floci.core.common.RegionResolver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
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
    private final RegionResolver regionResolver;

    private Object lockFor(String key) {
        return syncLocks.computeIfAbsent(key, k -> new Object());
    }

    @Inject
    public EfsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.regionResolver = regionResolver;
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
                throw EfsException.fileSystemAlreadyExists(existing.getCreationToken(), existing.getFileSystemId());
            }
        }

        String fsId = "fs-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        String regionKey = regionKey(region, fsId);
        
        FileSystem fs = new FileSystem();
        fs.setFileSystemId(fsId);
        fs.setCreationToken(token);
        fs.setCreationTime(Instant.now().getEpochSecond());
        fs.setLifeCycleState(LifeCycleState.available.name());
        fs.setFileSystemArn(regionResolver.buildArn("elasticfilesystem", region, "file-system/" + fsId));
        fs.setOwnerId(regionResolver.getAccountId());
        fs.setNumberOfMountTargets(0);
        fs.setPerformanceMode(request.getPerformanceMode() != null ? request.getPerformanceMode().name() : "generalPurpose");
        fs.setThroughputMode(request.getThroughputMode() != null ? request.getThroughputMode().name() : "bursting");
        if ("PROVISIONED".equalsIgnoreCase(fs.getThroughputMode()) && request.getProvisionedThroughputInMibps() != null) {
            fs.setProvisionedThroughputInMibps(request.getProvisionedThroughputInMibps());
        }
        fs.setEncrypted(request.getEncrypted() != null ? request.getEncrypted() : false);
        fs.setKmsKeyId(request.getKmsKeyId());
        if (request.getAvailabilityZoneName() != null) {
            fs.setAvailabilityZoneName(request.getAvailabilityZoneName());
            fs.setAvailabilityZoneId(generateAzId(request.getAvailabilityZoneName()));
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
        
        BackupPolicy bp = new BackupPolicy();
        bp.setStatus(request.getBackup() != null && !request.getBackup() ? "DISABLED" : "ENABLED");
        backupPolicyStore.put(regionKey, bp);
        
        return fs;
        }
    }

    public DescribeFileSystemsResponse describeFileSystems(String region, DescribeFileSystemsRequest request) {
        List<FileSystem> results = fileSystemStore.scan(k -> k.startsWith(region + "::")).stream()
            .filter(fs -> request.getFileSystemId() == null
                    || request.getFileSystemId().equals(fs.getFileSystemId()))
            .filter(fs -> request.getCreationToken() == null
                    || request.getCreationToken().equals(fs.getCreationToken()))
            .map(fs -> {
                if (fs.getTags() != null) {
                    fs.getTags().stream().filter(t -> "Name".equals(t.getKey())).findFirst().ifPresent(tag -> {
                        fs.setName(tag.getValue());
                    });
                }
                return fs;
            })
            .sorted(Comparator.comparing(FileSystem::getFileSystemId))
            .collect(Collectors.toList());

        if (results.isEmpty()) {
            if (request.getFileSystemId() != null) {
                throw EfsException.fileSystemNotFound(request.getFileSystemId());
            }
            if (request.getCreationToken() != null) {
                throw EfsException.fileSystemNotFound(request.getCreationToken());
            }
        }

        int maxItems = request.getMaxItems() != null ? request.getMaxItems() : 100;
        int startIndex = 0;
        if (request.getMarker() != null && !request.getMarker().isEmpty()) {
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).getFileSystemId().equals(request.getMarker())) {
                    startIndex = i + 1;
                    break;
                }
            }
        }
        
        List<FileSystem> paginated = new ArrayList<>();
        String nextMarker = null;
        for (int i = startIndex; i < results.size(); i++) {
            if (paginated.size() >= maxItems) {
                nextMarker = results.get(i - 1).getFileSystemId();
                break;
            }
            paginated.add(results.get(i));
        }

        DescribeFileSystemsResponse response = new DescribeFileSystemsResponse();
        response.setFileSystems(paginated);
        response.setMarker(request.getMarker());
        response.setNextMarker(nextMarker);
        return response;
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
                String mode = request.getThroughputMode().name();
                fs.setThroughputMode(mode);
                if (!"PROVISIONED".equalsIgnoreCase(mode)) {
                    fs.setProvisionedThroughputInMibps(null);
                }
            }
            if (request.getProvisionedThroughputInMibps() != null) {
                if (!"PROVISIONED".equalsIgnoreCase(fs.getThroughputMode())) {
                    throw EfsException.badRequest("ProvisionedThroughputInMibps is only valid with PROVISIONED throughput mode.");
                }
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

        DescribeMountTargetsRequest descReq = new DescribeMountTargetsRequest();
        descReq.setFileSystemId(fileSystemId);
        List<MountTarget> mountTargets = describeMountTargets(region, descReq).getMountTargets();
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
    
    public void tagResource(String region, TagResourceRequest request) {
        synchronized (lockFor(regionKey(region, request.getResourceId()))) {
            List<Tag> existing = getResourceTags(region, request.getResourceId());
            if (request.getTags() != null) {
                for (Tag newTag : request.getTags()) {
                    existing.removeIf(t -> t.getKey().equals(newTag.getKey()));
                    existing.add(newTag);
                }
            }
            saveResourceTags(region, request.getResourceId(), existing);
        }
    }

    public void untagResource(String region, String resourceId, List<String> tagKeys) {
        synchronized (lockFor(regionKey(region, resourceId))) {
            List<Tag> existing = getResourceTags(region, resourceId);
            if (tagKeys != null) {
                existing.removeIf(t -> tagKeys.contains(t.getKey()));
            }
            saveResourceTags(region, resourceId, existing);
        }
    }

    public ListTagsForResourceResponse listTagsForResource(String region, String resourceId) {
        List<Tag> tags = getResourceTags(region, resourceId);
        ListTagsForResourceResponse res = new ListTagsForResourceResponse();
        res.setTags(tags);
        return res;
    }

    private List<Tag> getResourceTags(String region, String resourceId) {
        if (resourceId.startsWith("fs-")) {
            return getFileSystem(region, resourceId).getTags();
        } else if (resourceId.startsWith("fsap-")) {
            AccessPointDescription ap = accessPointStore.get(regionKey(region, resourceId)).orElse(null);
            if (ap == null) throw EfsException.accessPointNotFound(resourceId);
            if (ap.getTags() == null) ap.setTags(new ArrayList<>());
            return ap.getTags();
        } else if (resourceId.startsWith("fsmt-")) {
            MountTarget mt = mountTargetStore.get(regionKey(region, resourceId)).orElse(null);
            if (mt == null) throw EfsException.mountTargetNotFound(resourceId);
            // Mount targets don't natively have tags in the emulator currently, so we'll just ignore for now or return empty.
            return new ArrayList<>();
        }
        throw EfsException.badRequest("Invalid resource ID: " + resourceId);
    }

    private void saveResourceTags(String region, String resourceId, List<Tag> tags) {
        if (resourceId.startsWith("fs-")) {
            FileSystem fs = getFileSystem(region, resourceId);
            fs.setTags(tags);
            fileSystemStore.put(regionKey(region, resourceId), fs);
        } else if (resourceId.startsWith("fsap-")) {
            AccessPointDescription ap = accessPointStore.get(regionKey(region, resourceId)).orElse(null);
            if (ap != null) {
                ap.setTags(tags);
                accessPointStore.put(regionKey(region, resourceId), ap);
            }
        }
    }

    // --- Mount Targets ---

    public MountTarget createMountTarget(CreateMountTargetRequest request, String region) {
        synchronized (lockFor(regionKey(region, request.getFileSystemId()))) {
            FileSystem fs = getFileSystem(region, request.getFileSystemId());
            
            for (MountTarget existing : mountTargetStore.scan(k -> k.startsWith(region + "::"))) {
                if (existing.getFileSystemId().equals(request.getFileSystemId())) {
                    if (existing.getSubnetId().equals(request.getSubnetId())) {
                        throw EfsException.mountTargetConflict("Mount target already exists in this subnet");
                    }
                }
            }
            
            String mtId = "fsmt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        
            MountTarget mt = new MountTarget();
            mt.setMountTargetId(mtId);
            mt.setFileSystemId(request.getFileSystemId());
            mt.setSubnetId(request.getSubnetId());
            mt.setIpAddress(request.getIpAddress() != null ? request.getIpAddress() : "10.0.0.10");
            mt.setLifeCycleState(LifeCycleState.available);
            mt.setVpcId("vpc-12345678");
            mt.setAvailabilityZoneName(region + "a");
            mt.setAvailabilityZoneId(generateAzId(region + "a"));
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

    public DescribeMountTargetsResponse describeMountTargets(String region, DescribeMountTargetsRequest request) {
        if (request.getFileSystemId() == null && request.getMountTargetId() == null && request.getAccessPointId() == null) {
            throw EfsException.badRequest("One of FileSystemId, MountTargetId, or AccessPointId must be specified.");
        }
        
        List<MountTarget> results = mountTargetStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(mt -> request.getFileSystemId() == null || mt.getFileSystemId().equals(request.getFileSystemId()))
                .filter(mt -> request.getMountTargetId() == null || mt.getMountTargetId().equals(request.getMountTargetId()))
                .sorted(Comparator.comparing(MountTarget::getMountTargetId))
                .collect(Collectors.toList());
                
        // If accessPointId is specified, we would need to filter by access point file system ID, 
        // but EFS AccessPoints resolve to a FileSystemId first.
        if (request.getAccessPointId() != null) {
            AccessPointDescription ap = accessPointStore.get(regionKey(region, request.getAccessPointId())).orElse(null);
            if (ap != null) {
                results = results.stream().filter(mt -> mt.getFileSystemId().equals(ap.getFileSystemId())).collect(Collectors.toList());
            } else {
                results = new ArrayList<>();
            }
        }
        
        int maxItems = request.getMaxItems() != null ? request.getMaxItems() : 1000;
        int startIndex = 0;
        if (request.getMarker() != null && !request.getMarker().isEmpty()) {
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).getMountTargetId().equals(request.getMarker())) {
                    startIndex = i + 1;
                    break;
                }
            }
        }
        
        List<MountTarget> paginated = new ArrayList<>();
        String nextMarker = null;
        for (int i = startIndex; i < results.size(); i++) {
            if (paginated.size() >= maxItems) {
                nextMarker = results.get(i - 1).getMountTargetId();
                break;
            }
            paginated.add(results.get(i));
        }

        DescribeMountTargetsResponse response = new DescribeMountTargetsResponse();
        response.setMountTargets(paginated);
        response.setMarker(request.getMarker());
        response.setNextMarker(nextMarker);
        return response;
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
                    mountTargetStore.delete(key);
                }
            } catch (EfsException e) {
                LOG.debug("File system " + mt.getFileSystemId() + " already deleted, skipping parent count update during mount target deletion");
                mountTargetStore.delete(key);
            }
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
                        throw EfsException.accessPointAlreadyExists(existing.getClientToken(), existing.getAccessPointId());
                    }
                }

        String apId = "fsap-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        
        AccessPointDescription ap = new AccessPointDescription();
        ap.setAccessPointId(apId);
        ap.setAccessPointArn(regionResolver.buildArn("elasticfilesystem", region, "access-point/" + apId));
        ap.setClientToken(token);
        ap.setFileSystemId(request.getFileSystemId());
        ap.setPosixUser(request.getPosixUser());
        ap.setRootDirectory(request.getRootDirectory());
        if (request.getTags() != null) {
            ap.setTags(new ArrayList<>(request.getTags()));
            ap.getTags().stream().filter(t -> "Name".equals(t.getKey())).findFirst().ifPresent(tag -> {
                ap.setName(tag.getValue());
            });
        }
        ap.setLifeCycleState(LifeCycleState.available);
        ap.setOwnerId(regionResolver.getAccountId());
        
        accessPointStore.put(regionKey(region, apId), ap);
        return ap;
            }
        }
    }

    public List<AccessPointDescription> describeAccessPoints(String region, String fileSystemId, String accessPointId) {
        return accessPointStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(ap -> fileSystemId == null || ap.getFileSystemId().equals(fileSystemId))
                .filter(ap -> accessPointId == null || ap.getAccessPointId().equals(accessPointId))
                .map(ap -> {
                    if (ap.getTags() != null) {
                        ap.getTags().stream().filter(t -> "Name".equals(t.getKey())).findFirst().ifPresent(tag -> {
                            ap.setName(tag.getValue());
                        });
                    }
                    return ap;
                })
                .collect(Collectors.toList());
    }

    public void deleteAccessPoint(String region, String accessPointId) {
        String key = regionKey(region, accessPointId);
        AccessPointDescription ap = accessPointStore.get(key).orElse(null);
        if (ap == null) {
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

    private String generateAzId(String azName) {
        if (azName == null || azName.length() < 2) return azName + "-id";
        String region = azName.substring(0, azName.length() - 1);
        char letter = azName.charAt(azName.length() - 1);
        int num = (letter >= 'a' && letter <= 'z') ? (letter - 'a' + 1) : 1;
        
        String shortCode = region;
        String[] parts = region.split("-");
        if (parts.length == 3) {
            String mid = parts[1].replace("north", "n").replace("south", "s")
                                 .replace("east", "e").replace("west", "w")
                                 .replace("central", "c");
            shortCode = parts[0] + mid + parts[2];
        }
        return shortCode + "-az" + num;
    }
}
