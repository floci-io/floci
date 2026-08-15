package io.github.hectorvent.floci.services.datasync;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsAction;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataSyncIntegrationTest {

    private static final String TARGET = "FmrsService";

    private static final String IAM_ROLE_ARN = "arn:aws:iam::000000000000:role/datasync-bucket-access";
    private static final String EFS_FILESYSTEM_ARN =
            "arn:aws:elasticfilesystem:us-east-1:000000000000:file-system/fs-0123456789abcdef0";
    private static final String FSX_FILESYSTEM_ARN =
            "arn:aws:fsx:us-east-1:000000000000:file-system/fs-0123456789abcdef0";
    private static final String STORAGE_VIRTUAL_MACHINE_ARN =
            "arn:aws:fsx:us-east-1:000000000000:storage-virtual-machine/fs-0123456789abcdef0/svm-0123456789abcdef0";
    private static final String SUBNET_ARN = "arn:aws:ec2:us-east-1:000000000000:subnet/subnet-0123456789abcdef0";
    private static final String SECURITY_GROUP_ARN =
            "arn:aws:ec2:us-east-1:000000000000:security-group/sg-0123456789abcdef0";

    private static String agentArn;
    private static String s3LocationArn;
    private static String efsLocationArn;
    private static String nfsLocationArn;
    private static String smbLocationArn;
    private static String objectStorageLocationArn;
    private static String hdfsLocationArn;
    private static String azureBlobLocationArn;
    private static String fsxLustreLocationArn;
    private static String fsxOpenZfsLocationArn;
    private static String fsxWindowsLocationArn;
    private static String fsxOntapLocationArn;
    private static String taskArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String createLocation(String action, String body) {
        return awsAction(TARGET, action, body)
                .then()
                .statusCode(200)
                .body("LocationArn", matchesPattern("^arn:aws:datasync:[^:]+:\\d{12}:location/loc-[0-9a-f]{17}$"))
                .extract().path("LocationArn");
    }

    private static io.restassured.response.Response describeLocation(String action, String locationArn) {
        return awsAction(TARGET, action, "{\"LocationArn\": \"" + locationArn + "\"}");
    }

    // ──────────────────────────── Agents ────────────────────────────

    @Test
    @Order(1)
    void createAgent() {
        agentArn = awsAction(TARGET, "CreateAgent", """
                {
                  "ActivationKey": "AAAAA-1AAAA-BB1CC-DDDDD-EEEEE",
                  "AgentName": "floci-agent",
                  "Tags": [{"Key": "team", "Value": "data-movement"}]
                }
                """)
                .then()
                .statusCode(200)
                .body("AgentArn", matchesPattern("^arn:aws:datasync:[^:]+:\\d{12}:agent/agent-[0-9a-f]{17}$"))
                .extract().path("AgentArn");
    }

    @Test
    @Order(2)
    void describeAgentReportsOnlineOnTheFirstRead() {
        awsAction(TARGET, "DescribeAgent", "{\"AgentArn\": \"" + agentArn + "\"}")
                .then()
                .statusCode(200)
                .body("AgentArn", equalTo(agentArn))
                .body("Name", equalTo("floci-agent"))
                .body("Status", equalTo("ONLINE"))
                .body("EndpointType", equalTo("PUBLIC"))
                .body("Platform.Version", equalTo(DataSyncService.AGENT_PLATFORM_VERSION))
                .body("CreationTime", greaterThan(0.0f))
                .body("LastConnectionTime", greaterThan(0.0f));
    }

    @Test
    @Order(3)
    void listAgentsIncludesTheCreatedAgent() {
        awsAction(TARGET, "ListAgents", "{}")
                .then()
                .statusCode(200)
                .body("Agents.AgentArn", hasItem(agentArn))
                .body("Agents.find { it.AgentArn == '" + agentArn + "' }.Status", equalTo("ONLINE"));
    }

    @Test
    @Order(4)
    void updateAgentRenamesTheAgent() {
        awsAction(TARGET, "UpdateAgent",
                "{\"AgentArn\": \"" + agentArn + "\", \"Name\": \"floci-agent-renamed\"}")
                .then()
                .statusCode(200);

        awsAction(TARGET, "DescribeAgent", "{\"AgentArn\": \"" + agentArn + "\"}")
                .then()
                .statusCode(200)
                .body("Name", equalTo("floci-agent-renamed"));
    }

    @Test
    @Order(5)
    void listTagsForAgentReturnsTheCreateTags() {
        awsAction(TARGET, "ListTagsForResource", "{\"ResourceArn\": \"" + agentArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags[0].Key", equalTo("team"))
                .body("Tags[0].Value", equalTo("data-movement"));
    }

    // ─────────────────────────── Locations ───────────────────────────

    @Test
    @Order(10)
    void s3LocationRoundTrip() {
        s3LocationArn = createLocation("CreateLocationS3", """
                {
                  "S3BucketArn": "arn:aws:s3:::floci-datasync-bucket",
                  "Subdirectory": "/backups",
                  "S3Config": {"BucketAccessRoleArn": "%s"},
                  "Tags": [{"Key": "env", "Value": "test"}]
                }
                """.formatted(IAM_ROLE_ARN));

        describeLocation("DescribeLocationS3", s3LocationArn)
                .then()
                .statusCode(200)
                .body("LocationArn", equalTo(s3LocationArn))
                .body("LocationUri", equalTo("s3://floci-datasync-bucket/backups"))
                .body("S3StorageClass", equalTo("STANDARD"))
                .body("S3Config.BucketAccessRoleArn", equalTo(IAM_ROLE_ARN))
                .body("CreationTime", greaterThan(0.0f));
    }

    @Test
    @Order(11)
    void efsLocationRoundTrip() {
        efsLocationArn = createLocation("CreateLocationEfs", """
                {
                  "EfsFilesystemArn": "%s",
                  "Subdirectory": "/exports",
                  "Ec2Config": {"SubnetArn": "%s", "SecurityGroupArns": ["%s"]}
                }
                """.formatted(EFS_FILESYSTEM_ARN, SUBNET_ARN, SECURITY_GROUP_ARN));

        describeLocation("DescribeLocationEfs", efsLocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("efs://us-east-1.fs-0123456789abcdef0/exports"))
                .body("Ec2Config.SubnetArn", equalTo(SUBNET_ARN))
                .body("Ec2Config.SecurityGroupArns[0]", equalTo(SECURITY_GROUP_ARN))
                .body("InTransitEncryption", equalTo("NONE"));
    }

    @Test
    @Order(12)
    void nfsLocationRoundTrip() {
        nfsLocationArn = createLocation("CreateLocationNfs", """
                {
                  "Subdirectory": "/export/home",
                  "ServerHostname": "nfs.example.com",
                  "OnPremConfig": {"AgentArns": ["%s"]}
                }
                """.formatted(agentArn));

        describeLocation("DescribeLocationNfs", nfsLocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("nfs://nfs.example.com/export/home"))
                .body("OnPremConfig.AgentArns[0]", equalTo(agentArn))
                .body("MountOptions.Version", equalTo("AUTOMATIC"));
    }

    @Test
    @Order(13)
    void smbLocationRoundTripDoesNotEchoThePassword() {
        smbLocationArn = createLocation("CreateLocationSmb", """
                {
                  "Subdirectory": "/share",
                  "ServerHostname": "smb.example.com",
                  "User": "floci",
                  "Domain": "EXAMPLE",
                  "Password": "sup3rs3cret",
                  "AgentArns": ["%s"]
                }
                """.formatted(agentArn));

        describeLocation("DescribeLocationSmb", smbLocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("smb://smb.example.com/share"))
                .body("User", equalTo("floci"))
                .body("Domain", equalTo("EXAMPLE"))
                .body("AuthenticationType", equalTo("NTLM"))
                .body("MountOptions.Version", equalTo("AUTOMATIC"))
                .body("AgentArns[0]", equalTo(agentArn))
                .body("Password", nullValue());
    }

    @Test
    @Order(14)
    void objectStorageLocationRoundTripDoesNotEchoTheSecretKey() {
        objectStorageLocationArn = createLocation("CreateLocationObjectStorage", """
                {
                  "ServerHostname": "objects.example.com",
                  "BucketName": "floci-bucket",
                  "Subdirectory": "/incoming",
                  "AccessKey": "AKIAFLOCI",
                  "SecretKey": "sup3rs3cret",
                  "AgentArns": ["%s"]
                }
                """.formatted(agentArn));

        describeLocation("DescribeLocationObjectStorage", objectStorageLocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("object-storage://objects.example.com/floci-bucket/incoming"))
                .body("ServerProtocol", equalTo("HTTPS"))
                .body("ServerPort", equalTo(443))
                .body("AccessKey", equalTo("AKIAFLOCI"))
                .body("SecretKey", nullValue());
    }

    @Test
    @Order(15)
    void hdfsLocationRoundTripAppliesDocumentedDefaults() {
        hdfsLocationArn = createLocation("CreateLocationHdfs", """
                {
                  "Subdirectory": "/user/hadoop",
                  "NameNodes": [{"Hostname": "namenode.example.com", "Port": 8020}],
                  "AuthenticationType": "SIMPLE",
                  "SimpleUser": "hadoop",
                  "AgentArns": ["%s"]
                }
                """.formatted(agentArn));

        describeLocation("DescribeLocationHdfs", hdfsLocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("hdfs://namenode.example.com:8020/user/hadoop"))
                .body("NameNodes[0].Hostname", equalTo("namenode.example.com"))
                .body("NameNodes[0].Port", equalTo(8020))
                .body("BlockSize", equalTo(134217728))
                .body("ReplicationFactor", equalTo(3))
                .body("QopConfiguration.RpcProtection", equalTo("PRIVACY"))
                .body("QopConfiguration.DataTransferProtection", equalTo("PRIVACY"))
                .body("AuthenticationType", equalTo("SIMPLE"))
                .body("SimpleUser", equalTo("hadoop"));
    }

    @Test
    @Order(16)
    void azureBlobLocationRoundTrip() {
        azureBlobLocationArn = createLocation("CreateLocationAzureBlob", """
                {
                  "ContainerUrl": "https://flociaccount.blob.core.windows.net/flocicontainer",
                  "AuthenticationType": "SAS",
                  "SasConfiguration": {"Token": "sv=2021&sig=secret"},
                  "Subdirectory": "/data",
                  "AgentArns": ["%s"]
                }
                """.formatted(agentArn));

        describeLocation("DescribeLocationAzureBlob", azureBlobLocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri",
                        equalTo("azure-blob://flociaccount.blob.core.windows.net/flocicontainer/data"))
                .body("AuthenticationType", equalTo("SAS"))
                .body("BlobType", equalTo("BLOCK"))
                .body("AccessTier", equalTo("HOT"))
                .body("AgentArns[0]", equalTo(agentArn))
                .body("SasConfiguration", nullValue());
    }

    @Test
    @Order(17)
    void fsxLustreLocationRoundTrip() {
        fsxLustreLocationArn = createLocation("CreateLocationFsxLustre", """
                {
                  "FsxFilesystemArn": "%s",
                  "SecurityGroupArns": ["%s"],
                  "Subdirectory": "/scratch"
                }
                """.formatted(FSX_FILESYSTEM_ARN, SECURITY_GROUP_ARN));

        describeLocation("DescribeLocationFsxLustre", fsxLustreLocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("fsxl://us-east-1.fs-0123456789abcdef0/scratch"))
                .body("SecurityGroupArns[0]", equalTo(SECURITY_GROUP_ARN));
    }

    @Test
    @Order(18)
    void fsxOpenZfsLocationRoundTrip() {
        fsxOpenZfsLocationArn = createLocation("CreateLocationFsxOpenZfs", """
                {
                  "FsxFilesystemArn": "%s",
                  "SecurityGroupArns": ["%s"],
                  "Protocol": {"NFS": {"MountOptions": {"Version": "AUTOMATIC"}}},
                  "Subdirectory": "/fsx/folderA"
                }
                """.formatted(FSX_FILESYSTEM_ARN, SECURITY_GROUP_ARN));

        describeLocation("DescribeLocationFsxOpenZfs", fsxOpenZfsLocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("fsxz://us-east-1.fs-0123456789abcdef0/fsx/folderA"))
                .body("Protocol.NFS.MountOptions.Version", equalTo("AUTOMATIC"));
    }

    @Test
    @Order(19)
    void fsxWindowsLocationRoundTrip() {
        fsxWindowsLocationArn = createLocation("CreateLocationFsxWindows", """
                {
                  "FsxFilesystemArn": "%s",
                  "SecurityGroupArns": ["%s"],
                  "User": "floci",
                  "Domain": "EXAMPLE",
                  "Password": "sup3rs3cret",
                  "Subdirectory": "/share"
                }
                """.formatted(FSX_FILESYSTEM_ARN, SECURITY_GROUP_ARN));

        describeLocation("DescribeLocationFsxWindows", fsxWindowsLocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("fsxw://us-east-1.fs-0123456789abcdef0/share"))
                .body("User", equalTo("floci"))
                .body("Domain", equalTo("EXAMPLE"))
                .body("Password", nullValue());
    }

    @Test
    @Order(20)
    void fsxOntapLocationDerivesTheFileSystemArn() {
        fsxOntapLocationArn = createLocation("CreateLocationFsxOntap", """
                {
                  "StorageVirtualMachineArn": "%s",
                  "SecurityGroupArns": ["%s"],
                  "Protocol": {"SMB": {"User": "floci", "Domain": "EXAMPLE", "Password": "sup3rs3cret"}},
                  "Subdirectory": "/vol1"
                }
                """.formatted(STORAGE_VIRTUAL_MACHINE_ARN, SECURITY_GROUP_ARN));

        describeLocation("DescribeLocationFsxOntap", fsxOntapLocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri",
                        equalTo("fsxn://us-east-1.fs-0123456789abcdef0.svm-0123456789abcdef0/vol1"))
                .body("StorageVirtualMachineArn", equalTo(STORAGE_VIRTUAL_MACHINE_ARN))
                .body("FsxFilesystemArn", equalTo(FSX_FILESYSTEM_ARN))
                .body("Protocol.SMB.User", equalTo("floci"))
                .body("Protocol.SMB.Password", nullValue());
    }

    @Test
    @Order(21)
    void updateLocationS3RebuildsTheLocationUri() {
        awsAction(TARGET, "UpdateLocationS3", """
                {
                  "LocationArn": "%s",
                  "Subdirectory": "/archive",
                  "S3StorageClass": "GLACIER"
                }
                """.formatted(s3LocationArn))
                .then()
                .statusCode(200);

        describeLocation("DescribeLocationS3", s3LocationArn)
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("s3://floci-datasync-bucket/archive"))
                .body("S3StorageClass", equalTo("GLACIER"))
                .body("S3Config.BucketAccessRoleArn", equalTo(IAM_ROLE_ARN));
    }

    @Test
    @Order(22)
    void listLocationsSeesEveryCreatedLocation() {
        awsAction(TARGET, "ListLocations", "{}")
                .then()
                .statusCode(200)
                .body("Locations.LocationArn", hasItem(s3LocationArn))
                .body("Locations.LocationArn", hasItem(efsLocationArn))
                .body("Locations.LocationArn", hasItem(nfsLocationArn))
                .body("Locations.LocationArn", hasItem(smbLocationArn))
                .body("Locations.LocationArn", hasItem(objectStorageLocationArn))
                .body("Locations.LocationArn", hasItem(hdfsLocationArn))
                .body("Locations.LocationArn", hasItem(azureBlobLocationArn))
                .body("Locations.LocationArn", hasItem(fsxLustreLocationArn))
                .body("Locations.LocationArn", hasItem(fsxOpenZfsLocationArn))
                .body("Locations.LocationArn", hasItem(fsxWindowsLocationArn))
                .body("Locations.LocationArn", hasItem(fsxOntapLocationArn));
    }

    @Test
    @Order(23)
    void listLocationsHonoursALocationTypeFilter() {
        awsAction(TARGET, "ListLocations", """
                {"Filters": [{"Name": "LocationType", "Values": ["Efs"], "Operator": "Equals"}]}
                """)
                .then()
                .statusCode(200)
                .body("Locations.LocationArn", hasItem(efsLocationArn))
                .body("Locations.LocationArn", not(hasItem(s3LocationArn)));
    }

    @Test
    @Order(24)
    void describeLocationRejectsAMismatchedLocationType() {
        describeLocation("DescribeLocationS3", efsLocationArn)
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(25)
    void describeMissingLocationIsAnInvalidRequest() {
        describeLocation("DescribeLocationS3",
                "arn:aws:datasync:us-east-1:000000000000:location/loc-00000000000000000")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("is not found"));
    }

    @Test
    @Order(26)
    void createLocationRejectsAMissingRequiredMember() {
        awsAction(TARGET, "CreateLocationS3",
                "{\"S3Config\": {\"BucketAccessRoleArn\": \"" + IAM_ROLE_ARN + "\"}}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("S3BucketArn"));
    }

    // ───────────────────────────── Tasks ─────────────────────────────

    @Test
    @Order(30)
    void createTaskReportsAvailableOnTheFirstRead() {
        taskArn = awsAction(TARGET, "CreateTask", """
                {
                  "SourceLocationArn": "%s",
                  "DestinationLocationArn": "%s",
                  "Name": "floci-task",
                  "Options": {"LogLevel": "TRANSFER", "VerifyMode": "NONE"},
                  "Tags": [{"Key": "env", "Value": "test"}]
                }
                """.formatted(nfsLocationArn, s3LocationArn))
                .then()
                .statusCode(200)
                .body("TaskArn", matchesPattern("^arn:aws:datasync:[^:]+:\\d{12}:task/task-[0-9a-f]{17}$"))
                .extract().path("TaskArn");

        awsAction(TARGET, "DescribeTask", "{\"TaskArn\": \"" + taskArn + "\"}")
                .then()
                .statusCode(200)
                .body("TaskArn", equalTo(taskArn))
                .body("Status", equalTo("AVAILABLE"))
                .body("Name", equalTo("floci-task"))
                .body("TaskMode", equalTo("BASIC"))
                .body("SourceLocationArn", equalTo(nfsLocationArn))
                .body("DestinationLocationArn", equalTo(s3LocationArn))
                .body("SourceNetworkInterfaceArns", emptyIterable())
                .body("DestinationNetworkInterfaceArns", emptyIterable())
                .body("Excludes", emptyIterable())
                .body("Includes", emptyIterable())
                .body("Options.LogLevel", equalTo("TRANSFER"))
                .body("Options.VerifyMode", equalTo("NONE"))
                .body("Options.OverwriteMode", equalTo("ALWAYS"))
                .body("Options.PreserveDeletedFiles", equalTo("PRESERVE"))
                .body("Options.TransferMode", equalTo("CHANGED"))
                .body("Options.BytesPerSecond", equalTo(-1))
                .body("CreationTime", greaterThan(0.0f));
    }

    @Test
    @Order(31)
    void createTaskRejectsAnUnknownLocation() {
        awsAction(TARGET, "CreateTask", """
                {
                  "SourceLocationArn": "arn:aws:datasync:us-east-1:000000000000:location/loc-00000000000000000",
                  "DestinationLocationArn": "%s"
                }
                """.formatted(s3LocationArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(32)
    void listTasksIncludesTheCreatedTask() {
        awsAction(TARGET, "ListTasks", "{}")
                .then()
                .statusCode(200)
                .body("Tasks.TaskArn", hasItem(taskArn))
                .body("Tasks.find { it.TaskArn == '" + taskArn + "' }.Status", equalTo("AVAILABLE"));
    }

    @Test
    @Order(33)
    void updateTaskRenamesAndReschedulesTheTask() {
        awsAction(TARGET, "UpdateTask", """
                {
                  "TaskArn": "%s",
                  "Name": "floci-task-renamed",
                  "Schedule": {"ScheduleExpression": "cron(0 12 ? * SUN *)", "Status": "ENABLED"},
                  "Excludes": [{"FilterType": "SIMPLE_PATTERN", "Value": "/tmp"}]
                }
                """.formatted(taskArn))
                .then()
                .statusCode(200);

        awsAction(TARGET, "DescribeTask", "{\"TaskArn\": \"" + taskArn + "\"}")
                .then()
                .statusCode(200)
                .body("Name", equalTo("floci-task-renamed"))
                .body("Schedule.ScheduleExpression", equalTo("cron(0 12 ? * SUN *)"))
                .body("Excludes[0].FilterType", equalTo("SIMPLE_PATTERN"))
                .body("Excludes[0].Value", equalTo("/tmp"));
    }

    @Test
    @Order(34)
    void taskTagRoundTrip() {
        awsAction(TARGET, "TagResource", """
                {"ResourceArn": "%s", "Tags": [{"Key": "owner", "Value": "platform"}]}
                """.formatted(taskArn))
                .then()
                .statusCode(200);

        awsAction(TARGET, "ListTagsForResource", "{\"ResourceArn\": \"" + taskArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("env"))
                .body("Tags.Key", hasItem("owner"))
                .body("Tags.find { it.Key == 'owner' }.Value", equalTo("platform"));

        awsAction(TARGET, "UntagResource", "{\"ResourceArn\": \"" + taskArn + "\", \"Keys\": [\"env\"]}")
                .then()
                .statusCode(200);

        awsAction(TARGET, "ListTagsForResource", "{\"ResourceArn\": \"" + taskArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", not(hasItem("env")))
                .body("Tags.Key", hasItem("owner"));
    }

    // ───────────────────── Data plane and teardown ─────────────────────

    @Test
    @Order(40)
    void taskExecutionOperationsAreNotEmulated() {
        awsAction(TARGET, "StartTaskExecution", "{\"TaskArn\": \"" + taskArn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    @Order(41)
    void deleteTaskRemovesIt() {
        awsAction(TARGET, "DeleteTask", "{\"TaskArn\": \"" + taskArn + "\"}")
                .then()
                .statusCode(200);

        awsAction(TARGET, "DescribeTask", "{\"TaskArn\": \"" + taskArn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(42)
    void deleteLocationRemovesIt() {
        awsAction(TARGET, "DeleteLocation", "{\"LocationArn\": \"" + s3LocationArn + "\"}")
                .then()
                .statusCode(200);

        describeLocation("DescribeLocationS3", s3LocationArn)
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        awsAction(TARGET, "ListLocations", "{}")
                .then()
                .statusCode(200)
                .body("Locations.LocationArn", not(hasItem(s3LocationArn)));
    }

    @Test
    @Order(43)
    void deleteAgentRemovesIt() {
        awsAction(TARGET, "DeleteAgent", "{\"AgentArn\": \"" + agentArn + "\"}")
                .then()
                .statusCode(200);

        awsAction(TARGET, "DescribeAgent", "{\"AgentArn\": \"" + agentArn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }
}
