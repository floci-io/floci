package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.efs.EfsException;
import io.floci.aws.services.efs.EfsService;
import io.floci.aws.services.efs.model.AccessPointDescription;
import io.floci.aws.services.efs.model.BackupPolicy;
import io.floci.aws.services.efs.model.CreateAccessPointRequest;
import io.floci.aws.services.efs.model.CreateFileSystemRequest;
import io.floci.aws.services.efs.model.CreateMountTargetRequest;
import io.floci.aws.services.efs.model.DescribeMountTargetsResponse;
import io.floci.aws.services.efs.model.FileSystem;
import io.floci.aws.services.efs.model.FileSystemProtectionDescription;
import io.floci.aws.services.efs.model.LifecyclePolicy;
import io.floci.aws.services.efs.model.ModifyMountTargetSecurityGroupsRequest;
import io.floci.aws.services.efs.model.MountTarget;
import io.floci.aws.services.efs.model.ReplicationOverwriteProtection;
import io.floci.aws.services.efs.model.Tag;
import io.floci.aws.services.efs.model.TagResourceRequest;
import io.floci.aws.services.efs.model.ThroughputMode;
import io.floci.aws.services.efs.model.UpdateFileSystemRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EfsCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String FS_ID = "fs-0123456789abcdef0";

    private final EfsService efs = mock(EfsService.class);
    private final EfsCfnProvisioner provisioner = new EfsCfnProvisioner(efs);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId, JsonNode priorProperties) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            List<String> values = new ArrayList<>();
            node.forEach(item -> values.add(item.asText()));
            return values;
        });
        when(engine.resolveJsonAttribute(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node.isTextual() ? node.asText() : node.toString();
        });
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId,
                event -> { }, priorProperties);
    }

    private ProvisionContext createCtx() {
        return ctx(null, null);
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Files");
        r.setResourceType("AWS::EFS::FileSystem");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private FileSystem fileSystem(String id) {
        FileSystem fs = new FileSystem();
        fs.setFileSystemId(id);
        fs.setFileSystemArn("arn:aws:elasticfilesystem:us-east-1:000000000000:file-system/" + id);
        fs.setEncrypted(false);
        fs.setPerformanceMode("generalPurpose");
        fs.setThroughputMode("bursting");
        fs.setTags(new ArrayList<>());
        FileSystemProtectionDescription protection = new FileSystemProtectionDescription();
        protection.setReplicationOverwriteProtection(ReplicationOverwriteProtection.ENABLED);
        fs.setFileSystemProtection(protection);
        return fs;
    }

    private void stubExisting(FileSystem fs) {
        when(efs.getFileSystem(REGION, fs.getFileSystemId())).thenReturn(fs);
        BackupPolicy backup = new BackupPolicy();
        backup.setStatus("ENABLED");
        when(efs.getBackupPolicy(REGION, fs.getFileSystemId())).thenReturn(backup);
        when(efs.getLifecycleConfiguration(REGION, fs.getFileSystemId())).thenReturn(new ArrayList<>());
        when(efs.getFileSystemPolicy(REGION, fs.getFileSystemId()))
                .thenThrow(EfsException.policyNotFound(fs.getFileSystemId()));
    }

    private ObjectNode props() {
        return mapper.createObjectNode();
    }

    @Test
    void createSetsPhysicalIdAndGetAttAttributes() {
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem(FS_ID));
        StackResource r = resource();

        provisioner.provision(r, props(), createCtx());

        assertEquals(FS_ID, r.getPhysicalId());
        assertEquals("arn:aws:elasticfilesystem:us-east-1:000000000000:file-system/" + FS_ID,
                r.getAttributes().get("Arn"));
        assertEquals(FS_ID, r.getAttributes().get("FileSystemId"));
    }

    @Test
    void createMapsPropertiesOntoTheRequest() throws Exception {
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem(FS_ID));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"Encrypted": true, "KmsKeyId": "key-1", "PerformanceMode": "maxIO",
                 "ThroughputMode": "provisioned", "ProvisionedThroughputInMibps": 128,
                 "BackupPolicy": {"Status": "DISABLED"},
                 "FileSystemTags": [{"Key": "env", "Value": "dev"}]}""");

        provisioner.provision(resource(), props, createCtx());

        ArgumentCaptor<CreateFileSystemRequest> captor = ArgumentCaptor.forClass(CreateFileSystemRequest.class);
        verify(efs).createFileSystem(captor.capture(), eq(REGION));
        CreateFileSystemRequest request = captor.getValue();
        assertEquals(Boolean.TRUE, request.getEncrypted());
        assertEquals("key-1", request.getKmsKeyId());
        assertEquals("maxIO", request.getPerformanceMode().name());
        assertEquals(ThroughputMode.provisioned, request.getThroughputMode());
        assertEquals(128.0, request.getProvisionedThroughputInMibps());
        assertEquals(Boolean.FALSE, request.getBackup());
        assertEquals(1, request.getTags().size());
        assertEquals("env", request.getTags().get(0).getKey());
        assertTrue(request.getCreationToken().startsWith("my-stack-Files-"));
    }

    @Test
    void createRejectsProvisionedThroughputOutsideProvisionedMode() throws Exception {
        for (String mode : List.of("", "\"ThroughputMode\": \"bursting\",", "\"ThroughputMode\": \"elastic\",")) {
            ObjectNode props = (ObjectNode) mapper.readTree(
                    "{" + mode + " \"ProvisionedThroughputInMibps\": 128}");

            AwsException e = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource(), props, createCtx()));

            assertEquals("ValidationError", e.getErrorCode());
            assertEquals("ProvisionedThroughputInMibps is only valid when ThroughputMode is provisioned.",
                    e.getMessage());
        }
        verify(efs, never()).createFileSystem(any(), any());
    }

    @Test
    void createWithoutABackupPolicyLeavesBackupsOffForARegionalFileSystem() {
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem(FS_ID));

        provisioner.provision(resource(), props(), createCtx());

        ArgumentCaptor<CreateFileSystemRequest> captor = ArgumentCaptor.forClass(CreateFileSystemRequest.class);
        verify(efs).createFileSystem(captor.capture(), eq(REGION));
        assertEquals(Boolean.FALSE, captor.getValue().getBackup());
    }

    @Test
    void createWithoutABackupPolicyTurnsBackupsOnForAOneZoneFileSystem() throws Exception {
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem(FS_ID));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"AvailabilityZoneName": "us-east-1a"}""");

        provisioner.provision(resource(), props, createCtx());

        ArgumentCaptor<CreateFileSystemRequest> captor = ArgumentCaptor.forClass(CreateFileSystemRequest.class);
        verify(efs).createFileSystem(captor.capture(), eq(REGION));
        assertEquals(Boolean.TRUE, captor.getValue().getBackup());
    }

    @Test
    void createHonorsAnEnabledBackupPolicyOnARegionalFileSystem() throws Exception {
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem(FS_ID));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"BackupPolicy": {"Status": "ENABLED"}}""");

        provisioner.provision(resource(), props, createCtx());

        ArgumentCaptor<CreateFileSystemRequest> captor = ArgumentCaptor.forClass(CreateFileSystemRequest.class);
        verify(efs).createFileSystem(captor.capture(), eq(REGION));
        assertEquals(Boolean.TRUE, captor.getValue().getBackup());
    }

    @Test
    void createHonorsADisabledBackupPolicyOnAOneZoneFileSystem() throws Exception {
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem(FS_ID));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"AvailabilityZoneName": "us-east-1a", "BackupPolicy": {"Status": "DISABLED"}}""");

        provisioner.provision(resource(), props, createCtx());

        ArgumentCaptor<CreateFileSystemRequest> captor = ArgumentCaptor.forClass(CreateFileSystemRequest.class);
        verify(efs).createFileSystem(captor.capture(), eq(REGION));
        assertEquals(Boolean.FALSE, captor.getValue().getBackup());
    }

    @Test
    void provisionedThroughputModeWithoutAThroughputIsRejectedOnCreateAndUpdate() throws Exception {
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"ThroughputMode": "provisioned"}""");
        stubExisting(fileSystem(FS_ID));

        AwsException onCreate = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, createCtx()));
        AwsException onUpdate = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, ctx(FS_ID, mapper.createObjectNode())));

        for (AwsException e : List.of(onCreate, onUpdate)) {
            assertEquals("ValidationError", e.getErrorCode());
            assertEquals("ProvisionedThroughputInMibps is required when ThroughputMode is provisioned.",
                    e.getMessage());
        }
        verify(efs, never()).createFileSystem(any(), any());
        verify(efs, never()).updateFileSystem(any(), any(), any());
    }

    @Test
    void provisionedThroughputModeWithAThroughputIsAccepted() throws Exception {
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem(FS_ID));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"ThroughputMode": "provisioned", "ProvisionedThroughputInMibps": 64}""");

        provisioner.provision(resource(), props, createCtx());

        verify(efs).createFileSystem(any(), eq(REGION));
    }

    @Test
    void createAppliesLifecyclePolicyAndProtectionAfterCreation() throws Exception {
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem(FS_ID));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"LifecyclePolicies": [{"TransitionToIA": "AFTER_30_DAYS"}],
                 "FileSystemPolicy": {"Version": "2012-10-17", "Statement": []},
                 "FileSystemProtection": {"ReplicationOverwriteProtection": "DISABLED"}}""");

        provisioner.provision(resource(), props, createCtx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LifecyclePolicy>> lifecycle = ArgumentCaptor.forClass(List.class);
        verify(efs).putLifecycleConfiguration(eq(REGION), eq(FS_ID), lifecycle.capture());
        assertEquals("AFTER_30_DAYS", lifecycle.getValue().get(0).getTransitionToIA());
        verify(efs).putFileSystemPolicy(REGION, FS_ID, "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        verify(efs).updateFileSystemProtection(eq(REGION), eq(FS_ID), any());
    }

    @Test
    void createWithoutOptionalPropertiesTouchesNothingElse() {
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem(FS_ID));

        provisioner.provision(resource(), props(), createCtx());

        verify(efs, never()).putLifecycleConfiguration(any(), any(), anyList());
        verify(efs, never()).putFileSystemPolicy(any(), any(), any());
        verify(efs, never()).deleteFileSystemPolicy(any(), any());
        verify(efs, never()).updateFileSystemProtection(any(), any(), any());
        verify(efs, never()).putBackupPolicy(any(), any(), any());
    }

    @Test
    void failedFollowUpStepDeletesTheFileSystemItCreated() throws Exception {
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem(FS_ID));
        doThrow(EfsException.badRequest("bad policy")).when(efs).putFileSystemPolicy(any(), any(), any());
        ObjectNode props = (ObjectNode) mapper.readTree("{\"FileSystemPolicy\": {\"Statement\": []}}");

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, createCtx()));

        verify(efs).deleteFileSystem(REGION, FS_ID);
    }

    @Test
    void updateKeepsTheFileSystemAndReconcilesMutableProperties() throws Exception {
        stubExisting(fileSystem(FS_ID));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"ThroughputMode": "provisioned", "ProvisionedThroughputInMibps": 64,
                 "FileSystemTags": [{"Key": "team", "Value": "core"}]}""");
        StackResource r = resource();

        provisioner.provision(r, props, ctx(FS_ID, null));

        verify(efs, never()).createFileSystem(any(), any());
        assertEquals(FS_ID, r.getPhysicalId());
        ArgumentCaptor<UpdateFileSystemRequest> update = ArgumentCaptor.forClass(UpdateFileSystemRequest.class);
        verify(efs).updateFileSystem(eq(REGION), eq(FS_ID), update.capture());
        assertEquals(ThroughputMode.provisioned, update.getValue().getThroughputMode());
        assertEquals(64.0, update.getValue().getProvisionedThroughputInMibps());
        ArgumentCaptor<TagResourceRequest> tags = ArgumentCaptor.forClass(TagResourceRequest.class);
        verify(efs).tagResource(eq(REGION), tags.capture());
        assertEquals(FS_ID, tags.getValue().getResourceId());
        assertFalse(ReplacementCleanup.hasReplacement(r));
    }

    @Test
    void updateUntagsKeysTheTemplateDropped() {
        FileSystem fs = fileSystem(FS_ID);
        Tag old = new Tag();
        old.setKey("old");
        old.setValue("v");
        fs.getTags().add(old);
        stubExisting(fs);

        provisioner.provision(resource(), props(), ctx(FS_ID, null));

        verify(efs).untagResource(REGION, FS_ID, List.of("old"));
    }

    @Test
    void updateResetsADroppedPolicyAndBackupPolicy() throws Exception {
        stubExisting(fileSystem(FS_ID));
        JsonNode prior = mapper.readTree("""
                {"FileSystemPolicy": {"Statement": []}, "BackupPolicy": {"Status": "DISABLED"}}""");

        provisioner.provision(resource(), props(), ctx(FS_ID, prior));

        verify(efs).deleteFileSystemPolicy(REGION, FS_ID);
        ArgumentCaptor<BackupPolicy> backup = ArgumentCaptor.forClass(BackupPolicy.class);
        verify(efs).putBackupPolicy(eq(REGION), eq(FS_ID), backup.capture());
        assertEquals("DISABLED", backup.getValue().getStatus());
    }

    @Test
    void updateResetsADroppedBackupPolicyToEnabledForAOneZoneFileSystem() throws Exception {
        FileSystem oneZone = fileSystem(FS_ID);
        oneZone.setAvailabilityZoneName("us-east-1a");
        stubExisting(oneZone);
        JsonNode prior = mapper.readTree("""
                {"AvailabilityZoneName": "us-east-1a", "BackupPolicy": {"Status": "DISABLED"}}""");
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"AvailabilityZoneName": "us-east-1a"}""");

        provisioner.provision(resource(), props, ctx(FS_ID, prior));

        ArgumentCaptor<BackupPolicy> backup = ArgumentCaptor.forClass(BackupPolicy.class);
        verify(efs).putBackupPolicy(eq(REGION), eq(FS_ID), backup.capture());
        assertEquals("ENABLED", backup.getValue().getStatus());
    }

    @Test
    void updateLeavesAPolicySetOutsideTheStackAlone() {
        stubExisting(fileSystem(FS_ID));

        provisioner.provision(resource(), props(), ctx(FS_ID, mapper.createObjectNode()));

        verify(efs, never()).deleteFileSystemPolicy(any(), any());
        verify(efs, never()).putBackupPolicy(any(), any(), any());
    }

    @Test
    void createOnlyChangeReplacesTheFileSystemAndOwesTheOldOneADelete() throws Exception {
        stubExisting(fileSystem(FS_ID));
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem("fs-new"));
        StackResource r = resource();
        r.setPhysicalId(FS_ID);
        ObjectNode props = (ObjectNode) mapper.readTree("{\"Encrypted\": true}");

        provisioner.provision(r, props, ctx(FS_ID, null));

        assertEquals("fs-new", r.getPhysicalId());
        assertNotEquals(FS_ID, r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(FS_ID, provisioner.updateCleanupPhysicalId(r));
        verify(efs, never()).deleteFileSystem(REGION, FS_ID);
    }

    @Test
    void missingPriorFileSystemIsCreatedAgain() {
        when(efs.getFileSystem(REGION, FS_ID)).thenThrow(EfsException.fileSystemNotFound(FS_ID));
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem("fs-new"));
        StackResource r = resource();

        provisioner.provision(r, props(), ctx(FS_ID, null));

        assertEquals("fs-new", r.getPhysicalId());
    }

    @Test
    void completingAReplacementDeletesTheDisplacedFileSystem() throws Exception {
        stubExisting(fileSystem(FS_ID));
        when(efs.createFileSystem(any(), eq(REGION))).thenReturn(fileSystem("fs-new"));
        StackResource r = resource();
        r.setPhysicalId(FS_ID);
        provisioner.provision(r, (ObjectNode) mapper.readTree("{\"Encrypted\": true}"), ctx(FS_ID, null));

        provisioner.completeUpdate(r);

        verify(efs).deleteFileSystem(REGION, FS_ID);
    }

    @Test
    void failedInPlaceUpdateRestoresTheSnapshot() throws Exception {
        FileSystem fs = fileSystem(FS_ID);
        stubExisting(fs);
        doThrow(EfsException.badRequest("nope")).doReturn(new FileSystemProtectionDescription())
                .when(efs).updateFileSystemProtection(any(), any(), any());
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"ThroughputMode": "provisioned", "ProvisionedThroughputInMibps": 64,
                 "FileSystemProtection": {"ReplicationOverwriteProtection": "DISABLED"}}""");
        StackResource r = resource();

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx(FS_ID, null)));

        ArgumentCaptor<UpdateFileSystemRequest> update = ArgumentCaptor.forClass(UpdateFileSystemRequest.class);
        verify(efs, times(2)).updateFileSystem(eq(REGION), eq(FS_ID), update.capture());
        assertEquals(ThroughputMode.bursting, update.getAllValues().get(1).getThroughputMode());
        assertNull(r.getAttributes().get(CfnRollback.EFS_FILE_SYSTEM_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackRestoresTheSnapshotAndSpendsIt() throws Exception {
        stubExisting(fileSystem(FS_ID));
        StackResource r = resource();
        provisioner.provision(r, props(), ctx(FS_ID, null));
        assertTrue(provisioner.retainsFailedUpdateState(r));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(efs).putBackupPolicy(eq(REGION), eq(FS_ID), any());
        assertFalse(provisioner.retainsFailedUpdateState(r));
    }

    @Test
    void rollbackWithNothingRecordedReportsNotRolledBack() {
        assertFalse(provisioner.rollbackUpdate(resource()));
    }

    @Test
    void deleteToleratesAFileSystemThatIsAlreadyGone() {
        doThrow(EfsException.fileSystemNotFound(FS_ID)).when(efs).deleteFileSystem(REGION, FS_ID);

        provisioner.delete("AWS::EFS::FileSystem", FS_ID, REGION);
    }

    @Test
    void deleteFailsWhileMountTargetsRemain() {
        doThrow(EfsException.fileSystemInUse(FS_ID)).when(efs).deleteFileSystem(REGION, FS_ID);

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::EFS::FileSystem", FS_ID, REGION));

        assertEquals("FileSystemInUse", e.getErrorCode());
    }

    private static final String MT_ID = "fsmt-0123456789abcdef0";
    private static final String AP_ID = "fsap-0123456789abcdef0";

    private MountTarget mountTarget(String id, String subnet, List<String> groups) {
        MountTarget mt = new MountTarget();
        mt.setMountTargetId(id);
        mt.setFileSystemId(FS_ID);
        mt.setSubnetId(subnet);
        mt.setIpAddress("10.0.0.10");
        mt.setSecurityGroups(new ArrayList<>(groups));
        return mt;
    }

    private void stubMountTargetLookup(MountTarget mt) {
        DescribeMountTargetsResponse response = new DescribeMountTargetsResponse();
        response.setMountTargets(List.of(mt));
        when(efs.describeMountTargets(eq(REGION), any())).thenReturn(response);
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private AccessPointDescription accessPoint(String id) {
        AccessPointDescription ap = new AccessPointDescription();
        ap.setAccessPointId(id);
        ap.setAccessPointArn("arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/" + id);
        ap.setFileSystemId(FS_ID);
        ap.setClientToken("token-1");
        ap.setTags(new ArrayList<>());
        return ap;
    }

    @Test
    void mountTargetCreateSetsPhysicalIdAndGetAttAttributes() throws Exception {
        when(efs.createMountTarget(any(), eq(REGION))).thenReturn(mountTarget(MT_ID, "subnet-1", List.of("sg-1")));
        StackResource r = resource("AWS::EFS::MountTarget", "Mount");
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-1"]}""");

        provisioner.provision(r, props, createCtx());

        assertEquals(MT_ID, r.getPhysicalId());
        assertEquals(MT_ID, r.getAttributes().get("Id"));
        assertEquals("10.0.0.10", r.getAttributes().get("IpAddress"));
        ArgumentCaptor<CreateMountTargetRequest> request = ArgumentCaptor.forClass(CreateMountTargetRequest.class);
        verify(efs).createMountTarget(request.capture(), eq(REGION));
        assertEquals(FS_ID, request.getValue().getFileSystemId());
        assertEquals("subnet-1", request.getValue().getSubnetId());
        assertEquals(List.of("sg-1"), request.getValue().getSecurityGroups());
    }

    @Test
    void mountTargetUpdateChangesSecurityGroupsInPlace() throws Exception {
        MountTarget prior = mountTarget(MT_ID, "subnet-1", List.of("sg-1"));
        stubMountTargetLookup(prior);
        StackResource r = resource("AWS::EFS::MountTarget", "Mount");
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-2"]}""");

        provisioner.provision(r, props, ctx(MT_ID, null));

        verify(efs, never()).createMountTarget(any(), any());
        ArgumentCaptor<ModifyMountTargetSecurityGroupsRequest> request =
                ArgumentCaptor.forClass(ModifyMountTargetSecurityGroupsRequest.class);
        verify(efs).modifyMountTargetSecurityGroups(eq(REGION), eq(MT_ID), request.capture());
        assertEquals(List.of("sg-2"), request.getValue().getSecurityGroups());
        assertEquals(MT_ID, r.getPhysicalId());
        assertFalse(ReplacementCleanup.hasReplacement(r));
    }

    @Test
    void mountTargetSubnetChangeReplacesTheMountTarget() throws Exception {
        stubMountTargetLookup(mountTarget(MT_ID, "subnet-1", List.of()));
        when(efs.createMountTarget(any(), eq(REGION))).thenReturn(mountTarget("fsmt-new", "subnet-2", List.of()));
        StackResource r = resource("AWS::EFS::MountTarget", "Mount");
        r.setPhysicalId(MT_ID);
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-2", "SecurityGroups": []}""");

        provisioner.provision(r, props, ctx(MT_ID, null));

        assertEquals("fsmt-new", r.getPhysicalId());
        assertEquals(MT_ID, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void mountTargetReplacementRefusedByEfsLeavesTheOldTargetUntouched() throws Exception {
        stubMountTargetLookup(mountTarget(MT_ID, "subnet-1", List.of("sg-1")));
        when(efs.createMountTarget(any(), eq(REGION)))
                .thenThrow(EfsException.mountTargetConflict("Mount target already exists in this subnet"));
        StackResource r = resource("AWS::EFS::MountTarget", "Mount");
        r.setPhysicalId(MT_ID);
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-1"],
                 "IpAddress": "10.0.0.99"}""");

        EfsException e = assertThrows(EfsException.class, () -> provisioner.provision(r, props, ctx(MT_ID, null)));

        assertEquals("MountTargetConflict", e.getErrorCode());
        assertEquals(MT_ID, r.getPhysicalId());
        verify(efs, never()).deleteMountTarget(any(), any());
    }

    @Test
    void mountTargetWithoutARequiredPropertyIsRejected() throws Exception {
        for (String missing : List.of("FileSystemId", "SubnetId", "SecurityGroups")) {
            ObjectNode props = (ObjectNode) mapper.readTree("""
                    {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-1"]}""");
            props.remove(missing);

            AwsException e = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource("AWS::EFS::MountTarget", "Mount"), props, createCtx()));

            assertEquals("ValidationError", e.getErrorCode());
            assertEquals(missing + " is required for AWS::EFS::MountTarget.", e.getMessage());
        }
        verify(efs, never()).createMountTarget(any(), any());
    }

    @Test
    void mountTargetWithoutAnIpAddressDoesNotPublishTheAttribute() throws Exception {
        MountTarget created = mountTarget(MT_ID, "subnet-1", List.of("sg-1"));
        created.setIpAddress(null);
        when(efs.createMountTarget(any(), eq(REGION))).thenReturn(created);
        StackResource r = resource("AWS::EFS::MountTarget", "Mount");
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-1"]}""");

        provisioner.provision(r, props, createCtx());

        assertFalse(r.getAttributes().containsKey("IpAddress"));
    }

    @Test
    void mountTargetUpdateKeepsTheTargetWhenAnAddressWasNeverDeclared() throws Exception {
        stubMountTargetLookup(mountTarget(MT_ID, "subnet-1", List.of("sg-1")));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-1"]}""");

        provisioner.provision(resource("AWS::EFS::MountTarget", "Mount"), props, ctx(MT_ID, props));

        verify(efs, never()).createMountTarget(any(), any());
    }

    @Test
    void mountTargetUpdateThatDropsADeclaredAddressReplacesTheTarget() throws Exception {
        stubMountTargetLookup(mountTarget(MT_ID, "subnet-1", List.of("sg-1")));
        when(efs.createMountTarget(any(), eq(REGION))).thenReturn(mountTarget("fsmt-new", "subnet-1", List.of("sg-1")));
        ObjectNode before = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-1"],
                 "IpAddress": "10.0.0.10", "IpAddressType": "IPV4_ONLY"}""");
        ObjectNode after = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-1"]}""");
        StackResource r = resource("AWS::EFS::MountTarget", "Mount");
        r.setPhysicalId(MT_ID);

        provisioner.provision(r, after, ctx(MT_ID, before));

        assertEquals("fsmt-new", r.getPhysicalId());
        assertEquals(MT_ID, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void mountTargetRollbackRestoresSecurityGroups() throws Exception {
        stubMountTargetLookup(mountTarget(MT_ID, "subnet-1", List.of("sg-1")));
        StackResource r = resource("AWS::EFS::MountTarget", "Mount");
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-2"]}""");
        provisioner.provision(r, props, ctx(MT_ID, null));

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<ModifyMountTargetSecurityGroupsRequest> request =
                ArgumentCaptor.forClass(ModifyMountTargetSecurityGroupsRequest.class);
        verify(efs, times(2)).modifyMountTargetSecurityGroups(eq(REGION), eq(MT_ID), request.capture());
        assertEquals(List.of("sg-1"), request.getAllValues().get(1).getSecurityGroups());
    }

    @Test
    void mountTargetDeleteToleratesAlreadyGoneButNotOtherFailures() {
        doThrow(EfsException.mountTargetNotFound(MT_ID)).when(efs).deleteMountTarget(REGION, MT_ID);
        provisioner.delete("AWS::EFS::MountTarget", MT_ID, REGION);

        doThrow(EfsException.badRequest("boom")).when(efs).deleteMountTarget(REGION, MT_ID);
        assertThrows(AwsException.class, () -> provisioner.delete("AWS::EFS::MountTarget", MT_ID, REGION));
    }

    @Test
    void accessPointCreateMapsPosixUserRootDirectoryAndTags() throws Exception {
        when(efs.createAccessPoint(eq(REGION), any())).thenReturn(accessPoint(AP_ID));
        StackResource r = resource("AWS::EFS::AccessPoint", "Ap");
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0",
                 "PosixUser": {"Uid": "1000", "Gid": "1001", "SecondaryGids": ["5", "6"]},
                 "RootDirectory": {"Path": "/data",
                   "CreationInfo": {"OwnerUid": "1000", "OwnerGid": "1001", "Permissions": "0755"}},
                 "AccessPointTags": [{"Key": "Name", "Value": "data"}]}""");

        provisioner.provision(r, props, createCtx());

        assertEquals(AP_ID, r.getPhysicalId());
        assertEquals(AP_ID, r.getAttributes().get("AccessPointId"));
        assertEquals("arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/" + AP_ID,
                r.getAttributes().get("Arn"));
        ArgumentCaptor<CreateAccessPointRequest> request = ArgumentCaptor.forClass(CreateAccessPointRequest.class);
        verify(efs).createAccessPoint(eq(REGION), request.capture());
        CreateAccessPointRequest sent = request.getValue();
        assertEquals(FS_ID, sent.getFileSystemId());
        assertEquals(1000L, sent.getPosixUser().getUid());
        assertEquals(1001L, sent.getPosixUser().getGid());
        assertEquals(List.of(5L, 6L), sent.getPosixUser().getSecondaryGids());
        assertEquals("/data", sent.getRootDirectory().getPath());
        assertEquals("0755", sent.getRootDirectory().getCreationInfo().getPermissions());
        assertEquals(1000L, sent.getRootDirectory().getCreationInfo().getOwnerUid());
        assertEquals("Name", sent.getTags().get(0).getKey());
        assertTrue(sent.getClientToken().startsWith("my-stack-Ap-"));
    }

    @Test
    void accessPointUpdateOfTagsOnlyKeepsTheAccessPoint() throws Exception {
        AccessPointDescription prior = accessPoint(AP_ID);
        when(efs.describeAccessPoints(REGION, null, AP_ID)).thenReturn(List.of(prior));
        StackResource r = resource("AWS::EFS::AccessPoint", "Ap");
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0",
                 "AccessPointTags": [{"Key": "team", "Value": "core"}]}""");

        provisioner.provision(r, props, ctx(AP_ID, null));

        verify(efs, never()).createAccessPoint(any(), any());
        verify(efs).tagResource(eq(REGION), any());
        assertEquals(AP_ID, r.getPhysicalId());
        assertFalse(ReplacementCleanup.hasReplacement(r));
    }

    @Test
    void accessPointUpdateRemovesATagAddedOutsideTheStack() throws Exception {
        AccessPointDescription prior = accessPoint(AP_ID);
        Tag outside = new Tag();
        outside.setKey("added-by-hand");
        outside.setValue("v");
        prior.getTags().add(outside);
        when(efs.describeAccessPoints(REGION, null, AP_ID)).thenReturn(List.of(prior));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0"}""");

        provisioner.provision(resource("AWS::EFS::AccessPoint", "Ap"), props, ctx(AP_ID, null));

        verify(efs).untagResource(REGION, AP_ID, List.of("added-by-hand"));
    }

    @Test
    void accessPointWithoutAFileSystemIdIsRejected() throws Exception {
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"PosixUser": {"Uid": "1", "Gid": "1"}}""");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::EFS::AccessPoint", "Ap"), props, createCtx()));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("FileSystemId is required for AWS::EFS::AccessPoint.", e.getMessage());
        verify(efs, never()).createAccessPoint(any(), any());
    }

    @Test
    void accessPointUpdateThatDropsADeclaredClientTokenReplacesTheAccessPoint() throws Exception {
        when(efs.describeAccessPoints(REGION, null, AP_ID)).thenReturn(List.of(accessPoint(AP_ID)));
        when(efs.createAccessPoint(eq(REGION), any())).thenReturn(accessPoint("fsap-new"));
        ObjectNode before = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "ClientToken": "token-1"}""");
        ObjectNode after = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0"}""");
        StackResource r = resource("AWS::EFS::AccessPoint", "Ap");
        r.setPhysicalId(AP_ID);

        provisioner.provision(r, after, ctx(AP_ID, before));

        assertEquals("fsap-new", r.getPhysicalId());
        assertEquals(AP_ID, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void accessPointUpdateKeepsTheAccessPointWhenAClientTokenWasNeverDeclared() throws Exception {
        when(efs.describeAccessPoints(REGION, null, AP_ID)).thenReturn(List.of(accessPoint(AP_ID)));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0"}""");

        provisioner.provision(resource("AWS::EFS::AccessPoint", "Ap"), props, ctx(AP_ID, props));

        verify(efs, never()).createAccessPoint(any(), any());
    }

    @Test
    void accessPointRollbackOfAnAccessPointThatIsGoneReportsNotFound() throws Exception {
        when(efs.describeAccessPoints(REGION, null, AP_ID)).thenReturn(List.of(accessPoint(AP_ID)));
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "AccessPointTags": [{"Key": "a", "Value": "b"}]}""");
        StackResource r = resource("AWS::EFS::AccessPoint", "Ap");
        provisioner.provision(r, props, ctx(AP_ID, null));
        when(efs.describeAccessPoints(REGION, null, AP_ID)).thenReturn(List.of());

        AwsException e = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertEquals("AccessPointNotFound", e.getErrorCode());
    }

    @Test
    void accessPointReplacementThatKeepsItsClientTokenLeavesTheOldAccessPointUntouched() throws Exception {
        when(efs.describeAccessPoints(REGION, null, AP_ID)).thenReturn(List.of(accessPoint(AP_ID)));
        when(efs.createAccessPoint(eq(REGION), any()))
                .thenThrow(EfsException.accessPointAlreadyExists("token-1", AP_ID));
        StackResource r = resource("AWS::EFS::AccessPoint", "Ap");
        r.setPhysicalId(AP_ID);
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "ClientToken": "token-1",
                 "RootDirectory": {"Path": "/other"}}""");

        EfsException e = assertThrows(EfsException.class, () -> provisioner.provision(r, props, ctx(AP_ID, null)));

        assertEquals("AccessPointAlreadyExists", e.getErrorCode());
        assertEquals(AP_ID, r.getPhysicalId());
        verify(efs, never()).deleteAccessPoint(any(), any());
    }

    @Test
    void accessPointPosixUserChangeReplacesTheAccessPoint() throws Exception {
        AccessPointDescription prior = accessPoint(AP_ID);
        when(efs.describeAccessPoints(REGION, null, AP_ID)).thenReturn(List.of(prior));
        when(efs.createAccessPoint(eq(REGION), any())).thenReturn(accessPoint("fsap-new"));
        StackResource r = resource("AWS::EFS::AccessPoint", "Ap");
        r.setPhysicalId(AP_ID);
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"FileSystemId": "fs-0123456789abcdef0", "PosixUser": {"Uid": "1", "Gid": "1"}}""");

        provisioner.provision(r, props, ctx(AP_ID, null));

        assertEquals("fsap-new", r.getPhysicalId());
        assertEquals(AP_ID, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void accessPointDeleteToleratesAlreadyGone() {
        doThrow(EfsException.accessPointNotFound(AP_ID)).when(efs).deleteAccessPoint(REGION, AP_ID);

        provisioner.delete("AWS::EFS::AccessPoint", AP_ID, REGION);
    }

    @Test
    void servesTheThreeEfsTypes() {
        assertEquals(Set.of("AWS::EFS::FileSystem", "AWS::EFS::MountTarget", "AWS::EFS::AccessPoint"),
                provisioner.resourceTypes());
    }
}
