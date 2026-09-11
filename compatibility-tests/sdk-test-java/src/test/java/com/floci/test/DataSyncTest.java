package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.datasync.DataSyncClient;
import software.amazon.awssdk.services.datasync.model.AgentStatus;
import software.amazon.awssdk.services.datasync.model.CreateTaskResponse;
import software.amazon.awssdk.services.datasync.model.DescribeAgentResponse;
import software.amazon.awssdk.services.datasync.model.DescribeLocationNfsResponse;
import software.amazon.awssdk.services.datasync.model.DescribeLocationS3Response;
import software.amazon.awssdk.services.datasync.model.DescribeTaskResponse;
import software.amazon.awssdk.services.datasync.model.InvalidRequestException;
import software.amazon.awssdk.services.datasync.model.S3StorageClass;
import software.amazon.awssdk.services.datasync.model.TagListEntry;
import software.amazon.awssdk.services.datasync.model.TaskMode;
import software.amazon.awssdk.services.datasync.model.TaskStatus;
import software.amazon.awssdk.services.datasync.model.VerifyMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DataSync")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataSyncTest {

    private static DataSyncClient datasync;
    private static String suffix;
    private static String agentArn;
    private static String s3LocationArn;
    private static String nfsLocationArn;
    private static String taskArn;
    private static String enhancedTaskArn;

    @BeforeAll
    static void setup() {
        datasync = TestFixtures.dataSyncClient();
        suffix = String.valueOf(System.currentTimeMillis());
    }

    @AfterAll
    static void cleanup() {
        if (datasync == null) {
            return;
        }
        deleteQuietly("task", enhancedTaskArn, arn -> datasync.deleteTask(r -> r.taskArn(arn)));
        deleteQuietly("task", taskArn, arn -> datasync.deleteTask(r -> r.taskArn(arn)));
        deleteQuietly("location", nfsLocationArn, arn -> datasync.deleteLocation(r -> r.locationArn(arn)));
        deleteQuietly("location", s3LocationArn, arn -> datasync.deleteLocation(r -> r.locationArn(arn)));
        deleteQuietly("agent", agentArn, arn -> datasync.deleteAgent(r -> r.agentArn(arn)));
        datasync.close();
    }

    private static void deleteQuietly(String kind, String arn, java.util.function.Consumer<String> delete) {
        if (arn == null) {
            return;
        }
        try {
            delete.accept(arn);
        } catch (RuntimeException alreadyGone) {
            System.out.println("DataSync cleanup left a " + kind + " behind: " + arn
                    + " (" + alreadyGone.getMessage() + ")");
        }
    }

    @Test
    @Order(1)
    void createAgentIsOnlineOnTheFirstDescribe() {
        agentArn = datasync.createAgent(r -> r
                        .activationKey("AAAAA-1AAAA-BB1CC-DDDDD-EEEEE")
                        .agentName("sdk-test-agent-" + suffix)
                        .tags(TagListEntry.builder().key("team").value("platform").build()))
                .agentArn();

        assertThat(agentArn).contains(":datasync:").contains(":agent/agent-");

        DescribeAgentResponse described = datasync.describeAgent(r -> r.agentArn(agentArn));

        assertThat(described.agentArn()).isEqualTo(agentArn);
        assertThat(described.name()).isEqualTo("sdk-test-agent-" + suffix);
        assertThat(described.status()).isEqualTo(AgentStatus.ONLINE);
        assertThat(described.endpointType()).isNotNull();
        assertThat(described.creationTime()).isNotNull();
    }

    @Test
    @Order(2)
    void createS3LocationAppliesTheDocumentedStorageClassDefault() {
        s3LocationArn = datasync.createLocationS3(r -> r
                        .s3BucketArn("arn:aws:s3:::sdk-test-datasync-" + suffix)
                        .subdirectory("/backups")
                        .s3Config(c -> c.bucketAccessRoleArn("arn:aws:iam::000000000000:role/datasync")))
                .locationArn();

        assertThat(s3LocationArn).contains(":location/loc-");

        DescribeLocationS3Response described = datasync.describeLocationS3(r -> r.locationArn(s3LocationArn));

        assertThat(described.locationArn()).isEqualTo(s3LocationArn);
        assertThat(described.locationUri()).startsWith("s3://sdk-test-datasync-" + suffix);
        assertThat(described.s3StorageClass()).isEqualTo(S3StorageClass.STANDARD);
        assertThat(described.s3Config().bucketAccessRoleArn())
                .isEqualTo("arn:aws:iam::000000000000:role/datasync");
        assertThat(described.creationTime()).isNotNull();
    }

    @Test
    @Order(3)
    void createNfsLocationReadsBackItsAgentAndMountOptions() {
        nfsLocationArn = datasync.createLocationNfs(r -> r
                        .serverHostname("nfs-" + suffix + ".example.com")
                        .subdirectory("/export/home")
                        .onPremConfig(c -> c.agentArns(agentArn)))
                .locationArn();

        DescribeLocationNfsResponse described = datasync.describeLocationNfs(r -> r.locationArn(nfsLocationArn));

        assertThat(described.locationUri()).startsWith("nfs://nfs-" + suffix + ".example.com");
        assertThat(described.onPremConfig().agentArns()).containsExactly(agentArn);
        assertThat(described.mountOptions().versionAsString()).isEqualTo("AUTOMATIC");
    }

    @Test
    @Order(4)
    void basicTaskIsAvailableAndVerifiesPointInTime() {
        CreateTaskResponse created = datasync.createTask(r -> r
                .sourceLocationArn(nfsLocationArn)
                .destinationLocationArn(s3LocationArn)
                .name("sdk-test-task-" + suffix)
                .tags(TagListEntry.builder().key("env").value("test").build()));

        taskArn = created.taskArn();
        assertThat(taskArn).contains(":task/task-");

        DescribeTaskResponse described = datasync.describeTask(r -> r.taskArn(taskArn));

        assertThat(described.status()).isEqualTo(TaskStatus.AVAILABLE);
        assertThat(described.taskMode()).isEqualTo(TaskMode.BASIC);
        assertThat(described.sourceLocationArn()).isEqualTo(nfsLocationArn);
        assertThat(described.destinationLocationArn()).isEqualTo(s3LocationArn);
        assertThat(described.options().verifyMode()).isEqualTo(VerifyMode.POINT_IN_TIME_CONSISTENT);
    }

    @Test
    @Order(5)
    void enhancedTaskVerifiesOnlyFilesTransferred() {
        enhancedTaskArn = datasync.createTask(r -> r
                        .sourceLocationArn(nfsLocationArn)
                        .destinationLocationArn(s3LocationArn)
                        .name("sdk-test-task-enhanced-" + suffix)
                        .taskMode(TaskMode.ENHANCED))
                .taskArn();

        DescribeTaskResponse described = datasync.describeTask(r -> r.taskArn(enhancedTaskArn));

        assertThat(described.taskMode()).isEqualTo(TaskMode.ENHANCED);
        assertThat(described.options().verifyMode()).isEqualTo(VerifyMode.ONLY_FILES_TRANSFERRED);
    }

    @Test
    @Order(6)
    void enhancedTaskRejectsPointInTimeConsistentVerification() {
        assertThatThrownBy(() -> datasync.createTask(r -> r
                .sourceLocationArn(nfsLocationArn)
                .destinationLocationArn(s3LocationArn)
                .name("sdk-test-task-rejected-" + suffix)
                .taskMode(TaskMode.ENHANCED)
                .options(o -> o.verifyMode(VerifyMode.POINT_IN_TIME_CONSISTENT))))
                .isInstanceOf(InvalidRequestException.class);

        assertThatThrownBy(() -> datasync.updateTask(r -> r
                .taskArn(enhancedTaskArn)
                .options(o -> o.verifyMode(VerifyMode.POINT_IN_TIME_CONSISTENT))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @Order(7)
    void updateTaskKeepsTheModeItWasCreatedWith() {
        datasync.updateTask(r -> r
                .taskArn(enhancedTaskArn)
                .name("sdk-test-task-enhanced-renamed-" + suffix));

        DescribeTaskResponse described = datasync.describeTask(r -> r.taskArn(enhancedTaskArn));

        assertThat(described.name()).isEqualTo("sdk-test-task-enhanced-renamed-" + suffix);
        assertThat(described.taskMode()).isEqualTo(TaskMode.ENHANCED);
        assertThat(described.options().verifyMode()).isEqualTo(VerifyMode.ONLY_FILES_TRANSFERRED);
    }

    @Test
    @Order(8)
    void listTasksIncludesBothTasks() {
        assertThat(datasync.listTasks(r -> r.maxResults(100)).tasks())
                .extracting(t -> t.taskArn())
                .contains(taskArn, enhancedTaskArn);
    }

    @Test
    @Order(9)
    void tagRoundTripOnTheTask() {
        datasync.tagResource(r -> r
                .resourceArn(taskArn)
                .tags(TagListEntry.builder().key("owner").value("platform").build()));

        assertThat(datasync.listTagsForResource(r -> r.resourceArn(taskArn)).tags())
                .contains(TagListEntry.builder().key("env").value("test").build())
                .contains(TagListEntry.builder().key("owner").value("platform").build());

        datasync.untagResource(r -> r.resourceArn(taskArn).keys("env"));

        assertThat(datasync.listTagsForResource(r -> r.resourceArn(taskArn)).tags())
                .extracting(TagListEntry::key)
                .contains("owner")
                .doesNotContain("env");
    }

    @Test
    @Order(10)
    void deleteTaskThenDescribeIsAnInvalidRequest() {
        datasync.deleteTask(r -> r.taskArn(taskArn));
        String deleted = taskArn;
        taskArn = null;

        assertThatThrownBy(() -> datasync.describeTask(r -> r.taskArn(deleted)))
                .isInstanceOf(InvalidRequestException.class);
    }
}
