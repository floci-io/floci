package io.github.hectorvent.floci.services.datasync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datasync.model.DataSyncAgent;
import io.github.hectorvent.floci.services.datasync.model.DataSyncLocation;
import io.github.hectorvent.floci.services.datasync.model.DataSyncLocationType;
import io.github.hectorvent.floci.services.datasync.model.DataSyncTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class DataSyncServiceTest {

    private static final String REGION = "us-east-1";
    private static final String FSX_FILESYSTEM_ARN =
            "arn:aws:fsx:us-east-1:000000000000:file-system/fs-0123456789abcdef0";
    private static final String STORAGE_VIRTUAL_MACHINE_ARN =
            "arn:aws:fsx:us-east-1:000000000000:storage-virtual-machine/fs-0123456789abcdef0/svm-0123456789abcdef0";

    private final ObjectMapper mapper = new ObjectMapper();
    private DataSyncService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        // A fresh backend per call: agents, locations and tasks are three separate stores,
        // and sharing one would let a location ARN resolve out of the agent store.
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        service = new DataSyncService(storageFactory, new RegionResolver(REGION, "000000000000"), mapper);
    }

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("test fixture is not valid JSON: " + body, e);
        }
    }

    private DataSyncLocation createLocation(DataSyncLocationType type, String body) {
        return service.createLocation(type, json(body), REGION);
    }

    @Test
    void createAgentIsOnlineAndPublicWithoutAVpcEndpoint() {
        DataSyncAgent agent = service.createAgent(
                json("{\"ActivationKey\": \"AAAAA-1AAAA-BB1CC-DDDDD-EEEEE\", \"AgentName\": \"floci\"}"), REGION);

        assertTrue(agent.getAgentArn().startsWith("arn:aws:datasync:us-east-1:000000000000:agent/agent-"));
        assertEquals(DataSyncService.AGENT_STATUS_ONLINE, agent.getStatus());
        assertEquals("PUBLIC", agent.getEndpointType());
        assertEquals("floci", agent.getName());
    }

    @Test
    void createAgentWithAVpcEndpointReportsPrivateLink() {
        DataSyncAgent agent = service.createAgent(json("""
                {"ActivationKey": "AAAAA-1AAAA-BB1CC-DDDDD-EEEEE", "VpcEndpointId": "vpce-0123456789abcdef0"}
                """), REGION);

        assertEquals("PRIVATE_LINK", agent.getEndpointType());
        assertEquals("vpce-0123456789abcdef0", agent.getVpcEndpointId());
    }

    @Test
    void createAgentWithoutAnActivationKeyIsAnInvalidRequest() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.createAgent(json("{\"AgentName\": \"floci\"}"), REGION));

        assertEquals("InvalidRequestException", error.getErrorCode());
        assertTrue(error.getMessage().contains("ActivationKey"));
    }

    @Test
    void s3LocationDerivesItsUriFromTheBucketArnAndSubdirectory() {
        DataSyncLocation location = createLocation(DataSyncLocationType.S3, """
                {"S3BucketArn": "arn:aws:s3:::floci-bucket", "Subdirectory": "/backups/",
                 "S3Config": {"BucketAccessRoleArn": "arn:aws:iam::000000000000:role/datasync"}}
                """);

        assertEquals("s3://floci-bucket/backups", location.getLocationUri());
        assertEquals("STANDARD", location.getConfiguration().path("S3StorageClass").asText());
    }

    @Test
    void s3LocationWithoutASubdirectoryOmitsTheTrailingSlash() {
        DataSyncLocation location = createLocation(DataSyncLocationType.S3, """
                {"S3BucketArn": "arn:aws:s3:::floci-bucket",
                 "S3Config": {"BucketAccessRoleArn": "arn:aws:iam::000000000000:role/datasync"}}
                """);

        assertEquals("s3://floci-bucket", location.getLocationUri());
    }

    @Test
    void fsxOntapLocationDerivesTheFileSystemArnFromTheSvmArn() {
        DataSyncLocation location = createLocation(DataSyncLocationType.FSX_ONTAP, """
                {"StorageVirtualMachineArn": "%s", "SecurityGroupArns": ["sg"],
                 "Protocol": {"SMB": {"User": "floci", "Password": "sup3rs3cret"}}, "Subdirectory": "/vol1"}
                """.formatted(STORAGE_VIRTUAL_MACHINE_ARN));

        assertEquals("fsxn://us-east-1.fs-0123456789abcdef0.svm-0123456789abcdef0/vol1",
                location.getLocationUri());
        assertEquals(FSX_FILESYSTEM_ARN, location.getConfiguration().path("FsxFilesystemArn").asText());
    }

    @Test
    void nestedProtocolPasswordIsStrippedBeforeTheConfigurationIsStored() {
        DataSyncLocation location = createLocation(DataSyncLocationType.FSX_ONTAP, """
                {"StorageVirtualMachineArn": "%s", "SecurityGroupArns": ["sg"],
                 "Protocol": {"SMB": {"User": "floci", "Password": "sup3rs3cret"}}}
                """.formatted(STORAGE_VIRTUAL_MACHINE_ARN));

        JsonNode smb = location.getConfiguration().path("Protocol").path("SMB");
        assertEquals("floci", smb.path("User").asText());
        assertFalse(smb.has("Password"));
    }

    @Test
    void topLevelCredentialMembersAreStrippedBeforeTheConfigurationIsStored() {
        DataSyncLocation location = createLocation(DataSyncLocationType.OBJECT_STORAGE, """
                {"ServerHostname": "objects.example.com", "BucketName": "floci-bucket",
                 "AccessKey": "AKIAFLOCI", "SecretKey": "sup3rs3cret"}
                """);

        assertEquals("AKIAFLOCI", location.getConfiguration().path("AccessKey").asText());
        assertFalse(location.getConfiguration().has("SecretKey"));
    }

    @Test
    void objectStorageDefaultsToHttpsOnPort443() {
        DataSyncLocation location = createLocation(DataSyncLocationType.OBJECT_STORAGE, """
                {"ServerHostname": "objects.example.com", "BucketName": "floci-bucket", "Subdirectory": "/incoming"}
                """);

        assertEquals("object-storage://objects.example.com/floci-bucket/incoming", location.getLocationUri());
        assertEquals("HTTPS", location.getConfiguration().path("ServerProtocol").asText());
        assertEquals(443, location.getConfiguration().path("ServerPort").asInt());
    }

    @Test
    void objectStorageOverHttpDefaultsToPort80() {
        DataSyncLocation location = createLocation(DataSyncLocationType.OBJECT_STORAGE, """
                {"ServerHostname": "objects.example.com", "BucketName": "floci-bucket", "ServerProtocol": "HTTP"}
                """);

        assertEquals(80, location.getConfiguration().path("ServerPort").asInt());
    }

    @Test
    void hdfsAppliesTheDocumentedBlockSizeReplicationAndQopDefaults() {
        DataSyncLocation location = createLocation(DataSyncLocationType.HDFS, """
                {"NameNodes": [{"Hostname": "namenode.example.com", "Port": 8020}],
                 "AuthenticationType": "SIMPLE", "AgentArns": ["agent"], "Subdirectory": "/user/hadoop"}
                """);

        assertEquals("hdfs://namenode.example.com:8020/user/hadoop", location.getLocationUri());
        JsonNode configuration = location.getConfiguration();
        assertEquals(134217728, configuration.path("BlockSize").asInt());
        assertEquals(3, configuration.path("ReplicationFactor").asInt());
        assertEquals("PRIVACY", configuration.path("QopConfiguration").path("RpcProtection").asText());
        assertEquals("PRIVACY", configuration.path("QopConfiguration").path("DataTransferProtection").asText());
    }

    @Test
    void createLocationWithoutARequiredMemberIsAnInvalidRequest() {
        AwsException error = assertThrows(AwsException.class,
                () -> createLocation(DataSyncLocationType.S3,
                        "{\"S3Config\": {\"BucketAccessRoleArn\": \"arn:aws:iam::000000000000:role/datasync\"}}"));

        assertEquals("InvalidRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(error.getMessage().contains("S3BucketArn"));
    }

    @Test
    void createLocationFsxWindowsDoesNotRequireAPassword() {
        DataSyncLocation location = createLocation(DataSyncLocationType.FSX_WINDOWS, """
                {"FsxFilesystemArn": "%s", "SecurityGroupArns": ["sg"], "User": "floci", "Subdirectory": "/share"}
                """.formatted(FSX_FILESYSTEM_ARN));

        assertEquals("fsxw://us-east-1.fs-0123456789abcdef0/share", location.getLocationUri());
    }

    @Test
    void updateLocationMergesTheRequestAndRebuildsTheUri() {
        DataSyncLocation location = createLocation(DataSyncLocationType.S3, """
                {"S3BucketArn": "arn:aws:s3:::floci-bucket", "Subdirectory": "/backups",
                 "S3Config": {"BucketAccessRoleArn": "arn:aws:iam::000000000000:role/datasync"}}
                """);

        DataSyncLocation updated = service.updateLocation(DataSyncLocationType.S3, json("""
                {"LocationArn": "%s", "Subdirectory": "/archive", "S3StorageClass": "GLACIER"}
                """.formatted(location.getLocationArn())));

        assertEquals("s3://floci-bucket/archive", updated.getLocationUri());
        assertEquals("GLACIER", updated.getConfiguration().path("S3StorageClass").asText());
        assertEquals("arn:aws:iam::000000000000:role/datasync",
                updated.getConfiguration().path("S3Config").path("BucketAccessRoleArn").asText());
    }

    @Test
    void describingALocationAsTheWrongTypeIsAnInvalidRequest() {
        DataSyncLocation location = createLocation(DataSyncLocationType.NFS, """
                {"Subdirectory": "/export", "ServerHostname": "nfs.example.com",
                 "OnPremConfig": {"AgentArns": ["agent"]}}
                """);

        AwsException error = assertThrows(AwsException.class,
                () -> service.getLocation(location.getLocationArn(), DataSyncLocationType.S3));

        assertEquals("InvalidRequestException", error.getErrorCode());
    }

    @Test
    void createTaskIsAvailableAndMergesTheRequestedOptionsOverTheDefaults() {
        DataSyncLocation source = createLocation(DataSyncLocationType.NFS, """
                {"Subdirectory": "/export", "ServerHostname": "nfs.example.com",
                 "OnPremConfig": {"AgentArns": ["agent"]}}
                """);
        DataSyncLocation destination = createLocation(DataSyncLocationType.S3, """
                {"S3BucketArn": "arn:aws:s3:::floci-bucket",
                 "S3Config": {"BucketAccessRoleArn": "arn:aws:iam::000000000000:role/datasync"}}
                """);

        DataSyncTask task = service.createTask(json("""
                {"SourceLocationArn": "%s", "DestinationLocationArn": "%s", "Name": "nightly",
                 "Options": {"LogLevel": "TRANSFER", "VerifyMode": "NONE"}}
                """.formatted(source.getLocationArn(), destination.getLocationArn())), REGION);

        assertEquals(DataSyncService.TASK_STATUS_AVAILABLE, task.getStatus());
        assertEquals(DataSyncService.DEFAULT_TASK_MODE, task.getTaskMode());
        assertEquals("TRANSFER", task.getOptions().path("LogLevel").asText());
        assertEquals("NONE", task.getOptions().path("VerifyMode").asText());
        assertEquals("ALWAYS", task.getOptions().path("OverwriteMode").asText());
        assertEquals(-1L, task.getOptions().path("BytesPerSecond").asLong());
    }

    @Test
    void createTaskAgainstAnUnknownLocationIsAnInvalidRequest() {
        AwsException error = assertThrows(AwsException.class, () -> service.createTask(json("""
                {"SourceLocationArn": "arn:aws:datasync:us-east-1:000000000000:location/loc-00000000000000000",
                 "DestinationLocationArn": "arn:aws:datasync:us-east-1:000000000000:location/loc-00000000000000001"}
                """), REGION));

        assertEquals("InvalidRequestException", error.getErrorCode());
    }

    @Test
    void listLocationsFiltersByLocationType() {
        DataSyncLocation nfs = createLocation(DataSyncLocationType.NFS, """
                {"Subdirectory": "/export", "ServerHostname": "nfs.example.com",
                 "OnPremConfig": {"AgentArns": ["agent"]}}
                """);
        createLocation(DataSyncLocationType.S3, """
                {"S3BucketArn": "arn:aws:s3:::floci-bucket",
                 "S3Config": {"BucketAccessRoleArn": "arn:aws:iam::000000000000:role/datasync"}}
                """);

        var page = service.listLocations(
                json("[{\"Name\": \"LocationType\", \"Values\": [\"Nfs\"], \"Operator\": \"Equals\"}]"), null, 0);

        assertEquals(List.of(nfs.getLocationArn()),
                page.items().stream().map(DataSyncLocation::getLocationArn).toList());
        assertNull(page.nextToken());
    }

    @Test
    void listLocationsRejectsAnUnknownFilterName() {
        createLocation(DataSyncLocationType.S3, """
                {"S3BucketArn": "arn:aws:s3:::floci-bucket",
                 "S3Config": {"BucketAccessRoleArn": "arn:aws:iam::000000000000:role/datasync"}}
                """);

        AwsException error = assertThrows(AwsException.class, () -> service.listLocations(
                json("[{\"Name\": \"Nope\", \"Values\": [\"x\"]}]"), null, 0));

        assertEquals("InvalidRequestException", error.getErrorCode());
    }

    @Test
    void listAgentsPagesOnMaxResultsAndResumesFromTheNextToken() {
        for (int i = 0; i < 3; i++) {
            service.createAgent(json("{\"ActivationKey\": \"AAAAA-1AAAA-BB1CC-DDDDD-EEEEE\"}"), REGION);
        }

        var first = service.listAgents(null, 2);
        assertEquals(2, first.items().size());
        assertEquals(first.items().get(1).getAgentArn(), first.nextToken());

        var second = service.listAgents(first.nextToken(), 2);
        assertEquals(1, second.items().size());
        assertNull(second.nextToken());
    }

    @Test
    void tagsSurviveTheirCreateAndRoundTripThroughTagAndUntag() {
        DataSyncAgent agent = service.createAgent(json("""
                {"ActivationKey": "AAAAA-1AAAA-BB1CC-DDDDD-EEEEE",
                 "Tags": [{"Key": "env", "Value": "test"}]}
                """), REGION);

        assertEquals(Map.of("env", "test"), service.listTagsForResource(agent.getAgentArn()));

        service.tagResource(agent.getAgentArn(), Map.of("owner", "platform"));
        assertEquals("platform", service.listTagsForResource(agent.getAgentArn()).get("owner"));

        service.untagResource(agent.getAgentArn(), List.of("env"));
        assertEquals(Map.of("owner", "platform"), service.listTagsForResource(agent.getAgentArn()));
    }

    @Test
    void taggingAnUnknownResourceIsAnInvalidRequest() {
        AwsException error = assertThrows(AwsException.class, () -> service.listTagsForResource(
                "arn:aws:datasync:us-east-1:000000000000:task/task-00000000000000000"));

        assertEquals("InvalidRequestException", error.getErrorCode());
    }

    @Test
    void deleteAgentRemovesItFromTheStore() {
        DataSyncAgent agent = service.createAgent(
                json("{\"ActivationKey\": \"AAAAA-1AAAA-BB1CC-DDDDD-EEEEE\"}"), REGION);

        service.deleteAgent(agent.getAgentArn());

        assertThrows(AwsException.class, () -> service.getAgent(agent.getAgentArn()));
    }
}
