package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.StatusString;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;
import software.amazon.awssdk.services.redshiftserverless.model.GetCredentialsResponse;
import software.amazon.awssdk.services.redshiftserverless.model.ConflictException;
import software.amazon.awssdk.services.redshiftserverless.model.Namespace;
import software.amazon.awssdk.services.redshiftserverless.model.NamespaceStatus;
import software.amazon.awssdk.services.redshiftserverless.model.ResourceNotFoundException;
import software.amazon.awssdk.services.redshiftserverless.model.Tag;
import software.amazon.awssdk.services.redshiftserverless.model.Workgroup;
import software.amazon.awssdk.services.redshiftserverless.model.WorkgroupStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Redshift Serverless namespace and workgroup lifecycle")
class RedshiftServerlessTest {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessTest.class);

    @Test
    void namespaceLifecycleUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates a namespace and asserts emulator-local defaults");

        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            String namespaceName = "floci-compat-ns";
            try {
                Namespace created = client.createNamespace(request -> request
                        .namespaceName(namespaceName)
                        .adminUsername("admin")
                        .adminUserPassword("Secret123!")).namespace();

                assertThat(created.namespaceName()).isEqualTo(namespaceName);
                assertThat(created.dbName()).isEqualTo("dev");
                assertThat(created.kmsKeyId()).isEqualTo("AWS_OWNED_KMS_KEY");
                assertThat(created.status()).isEqualTo(NamespaceStatus.AVAILABLE);
                assertThat(created.namespaceArn()).contains(":redshift-serverless:");

                Namespace fetched = client.getNamespace(request -> request.namespaceName(namespaceName)).namespace();
                assertThat(fetched.namespaceId()).isEqualTo(created.namespaceId());

                assertThat(client.listNamespaces(request -> {}).namespaces())
                        .extracting(Namespace::namespaceName)
                        .contains(namespaceName);

                assertThatThrownBy(() -> client.createNamespace(request -> request
                                .namespaceName(namespaceName)
                                .adminUsername("admin")))
                        .isInstanceOf(ConflictException.class);

                String arn = created.namespaceArn();
                client.tagResource(request -> request.resourceArn(arn)
                        .tags(Tag.builder().key("env").value("dev").build()));
                assertThat(client.listTagsForResource(request -> request.resourceArn(arn)).tags())
                        .extracting(Tag::key, Tag::value)
                        .contains(tuple("env", "dev"));

                client.untagResource(request -> request.resourceArn(arn).tagKeys("env"));
                assertThat(client.listTagsForResource(request -> request.resourceArn(arn)).tags())
                        .extracting(Tag::key)
                        .doesNotContain("env");
            } finally {
                deleteBestEffort(client, namespaceName);
            }

            assertThatThrownBy(() -> client.getNamespace(request -> request.namespaceName(namespaceName)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void workgroupLifecycleUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates a workgroup and asserts emulator-local defaults");

        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient();
             RedshiftDataClient dataClient = TestFixtures.redshiftDataClient()) {
            String namespaceName = "floci-compat-wg-ns";
            String workgroupName = "floci-compat-wg";
            try {
                client.createNamespace(request -> request
                        .namespaceName(namespaceName)
                        .adminUsername("admin")
                        .adminUserPassword("Secret123!"));

                Workgroup created = client.createWorkgroup(request -> request
                        .workgroupName(workgroupName)
                        .namespaceName(namespaceName)
                        .baseCapacity(32)
                        .tags(Tag.builder().key("env").value("dev").build())).workgroup();

                assertThat(created.workgroupName()).isEqualTo(workgroupName);
                assertThat(created.namespaceName()).isEqualTo(namespaceName);
                assertThat(created.status()).isEqualTo(WorkgroupStatus.AVAILABLE);
                assertThat(created.baseCapacity()).isEqualTo(32);
                assertThat(created.port()).isEqualTo(5439);
                assertThat(created.trackName()).isEqualTo("current");
                assertThat(created.workgroupArn()).contains(":redshift-serverless:").contains(":workgroup/");
                assertThat(created.creationDate()).isNotNull();
                assertThat(created.endpoint().address()).isNotBlank();
                assertThat(created.endpoint().port()).isPositive();

                Workgroup fetched = client.getWorkgroup(request -> request.workgroupName(workgroupName)).workgroup();
                assertThat(fetched.workgroupId()).isEqualTo(created.workgroupId());

                assertThat(client.listWorkgroups(request -> {}).workgroups())
                        .extracting(Workgroup::workgroupName)
                        .contains(workgroupName);

                Workgroup updated = client.updateWorkgroup(request -> request
                        .workgroupName(workgroupName)
                        .baseCapacity(64)).workgroup();
                assertThat(updated.baseCapacity()).isEqualTo(64);

                assertThatThrownBy(() -> client.createWorkgroup(request -> request
                                .workgroupName(workgroupName)
                                .namespaceName(namespaceName)))
                        .isInstanceOf(ConflictException.class);
                assertThatThrownBy(() -> client.deleteNamespace(request -> request.namespaceName(namespaceName)))
                        .isInstanceOf(ConflictException.class);

                GetCredentialsResponse credentials = client.getCredentials(request -> request
                        .workgroupName(workgroupName)
                        .dbName("dev"));
                assertThat(credentials.dbUser()).isNotBlank();
                assertThat(credentials.dbPassword()).isNotBlank();
                assertThat(credentials.expiration()).isAfter(Instant.now());

                ExecuteStatementResponse executed = dataClient.executeStatement(request -> request
                        .workgroupName(workgroupName)
                        .database("dev")
                        .sql("select 1"));
                assertThat(executed.workgroupName()).isEqualTo(workgroupName);
                assertThat(executed.clusterIdentifier()).isNull();
                DescribeStatementResponse described = dataClient.describeStatement(request -> request.id(executed.id()));
                assertThat(described.status()).isEqualTo(StatusString.FINISHED);

                String arn = created.workgroupArn();
                client.tagResource(request -> request.resourceArn(arn)
                        .tags(Tag.builder().key("team").value("data").build()));
                assertThat(client.listTagsForResource(request -> request.resourceArn(arn)).tags())
                        .extracting(Tag::key)
                        .contains("env", "team");
            } finally {
                deleteWorkgroupBestEffort(client, workgroupName);
                deleteBestEffort(client, namespaceName);
            }

            assertThatThrownBy(() -> client.getWorkgroup(request -> request.workgroupName(workgroupName)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    private static void deleteWorkgroupBestEffort(RedshiftServerlessClient client, String workgroupName) {
        try {
            client.deleteWorkgroup(request -> request.workgroupName(workgroupName));
        } catch (Exception cleanupError) {
            LOG.warnf(cleanupError, "Best-effort cleanup failed for workgroupName=%s", workgroupName);
        }
    }

    private static void deleteBestEffort(RedshiftServerlessClient client, String namespaceName) {
        try {
            client.deleteNamespace(request -> request.namespaceName(namespaceName));
        } catch (Exception cleanupError) {
            LOG.warnf(cleanupError, "Best-effort cleanup failed for namespaceName=%s", namespaceName);
        }
    }
}
