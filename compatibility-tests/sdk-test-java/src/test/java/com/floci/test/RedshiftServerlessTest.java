package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.StatusString;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;
import software.amazon.awssdk.services.redshiftserverless.model.ConflictException;
import software.amazon.awssdk.services.redshiftserverless.model.CreateSnapshotScheduleActionParameters;
import software.amazon.awssdk.services.redshiftserverless.model.EndpointAccess;
import software.amazon.awssdk.services.redshiftserverless.model.GetCredentialsResponse;
import software.amazon.awssdk.services.redshiftserverless.model.InvalidPaginationException;
import software.amazon.awssdk.services.redshiftserverless.model.Namespace;
import software.amazon.awssdk.services.redshiftserverless.model.NamespaceStatus;
import software.amazon.awssdk.services.redshiftserverless.model.ResourceNotFoundException;
import software.amazon.awssdk.services.redshiftserverless.model.Schedule;
import software.amazon.awssdk.services.redshiftserverless.model.ScheduledActionResponse;
import software.amazon.awssdk.services.redshiftserverless.model.ServerlessTrack;
import software.amazon.awssdk.services.redshiftserverless.model.Snapshot;
import software.amazon.awssdk.services.redshiftserverless.model.Tag;
import software.amazon.awssdk.services.redshiftserverless.model.TargetAction;
import software.amazon.awssdk.services.redshiftserverless.model.UsageLimit;
import software.amazon.awssdk.services.redshiftserverless.model.UsageLimitBreachAction;
import software.amazon.awssdk.services.redshiftserverless.model.UsageLimitPeriod;
import software.amazon.awssdk.services.redshiftserverless.model.UsageLimitUsageType;
import software.amazon.awssdk.services.redshiftserverless.model.ValidationException;
import software.amazon.awssdk.services.redshiftserverless.model.Workgroup;
import software.amazon.awssdk.services.redshiftserverless.model.WorkgroupStatus;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Redshift Serverless namespace and workgroup lifecycle")
class RedshiftServerlessTest {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessTest.class);

    @Test
    void managedNamespaceSecretAuthenticatesDataApiAndIsRemovedWhenManagementIsDisabled() throws Exception {
        assumeFalse(TestFixtures.isRealAws(), "Creates local resources and verifies immediate secret cleanup");

        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient();
             SecretsManagerClient secrets = TestFixtures.secretsManagerClient();
             RedshiftDataClient data = TestFixtures.redshiftDataClient()) {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String namespaceName = "floci-managed-ns-" + suffix;
            String workgroupName = "floci-managed-wg-" + suffix;
            try {
                Namespace created = client.createNamespace(request -> request.namespaceName(namespaceName)
                        .adminUsername("admin").dbName("analytics").manageAdminPassword(true)).namespace();
                String secretArn = created.adminPasswordSecretArn();
                assertThat(secretArn).isNotBlank();
                assertThat(client.getNamespace(request -> request.namespaceName(namespaceName))
                        .namespace().adminPasswordSecretArn()).isEqualTo(secretArn);
                JsonNode credentials = new ObjectMapper().readTree(secrets.getSecretValue(request -> request
                        .secretId(secretArn)).secretString());
                assertThat(credentials.path("username").asText()).isEqualTo("admin");
                assertThat(credentials.path("password").asText()).isNotBlank();
                assertThat(credentials.path("dbname").asText()).isEqualTo("analytics");
                assertThat(credentials.path("engine").asText()).isEqualTo("redshift");
                assertThat(secrets.describeSecret(request -> request.secretId(secretArn)).owningService())
                        .isEqualTo("redshift");

                client.createWorkgroup(request -> request.workgroupName(workgroupName).namespaceName(namespaceName));
                ExecuteStatementResponse executed = data.executeStatement(request -> request
                        .workgroupName(workgroupName).secretArn(secretArn).database("analytics")
                        .sql("SELECT 1").waitTimeSeconds(30));
                DescribeStatementResponse described = data.describeStatement(request -> request.id(executed.id()));
                assertThat(described.status()).as(described.error()).isEqualTo(StatusString.FINISHED);
                assertThat(data.getStatementResult(request -> request.id(executed.id()))
                        .records().get(0).get(0).longValue()).isEqualTo(1L);

                Namespace updated = client.updateNamespace(request -> request.namespaceName(namespaceName)
                        .manageAdminPassword(false).adminUserPassword("Replacement123!")).namespace();
                assertThat(updated.adminPasswordSecretArn()).isNull();
                assertThat(updated.adminPasswordSecretKmsKeyId()).isNull();
                assertThat(client.getNamespace(request -> request.namespaceName(namespaceName))
                        .namespace().adminPasswordSecretArn()).isNull();
                assertThatThrownBy(() -> secrets.getSecretValue(request -> request.secretId(secretArn)))
                        .isInstanceOfSatisfying(SecretsManagerException.class,
                                error -> assertThat(error.awsErrorDetails().errorCode())
                                        .isEqualTo("ResourceNotFoundException"));
            } finally {
                deleteWorkgroupBestEffort(client, workgroupName);
                deleteBestEffort(client, namespaceName);
            }
        }
    }

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
                        .sql("select 1")
                        .waitTimeSeconds(30));
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

    @Test
    void snapshotLifecycleUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates snapshots instantly and asserts emulator-local behavior");

        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            String namespaceName = "floci-compat-snap-ns";
            String workgroupName = "floci-compat-snap-wg";
            String snapshotName = "floci-compat-snap";
            try {
                client.createNamespace(request -> request.namespaceName(namespaceName).adminUsername("admin")
                        .adminUserPassword("Secret123!"));
                client.createWorkgroup(request -> request.workgroupName(workgroupName).namespaceName(namespaceName));

                Snapshot created = client.createSnapshot(request -> request
                        .snapshotName(snapshotName).namespaceName(namespaceName)).snapshot();
                assertThat(created.snapshotName()).isEqualTo(snapshotName);
                assertThat(created.status().toString()).isEqualTo("AVAILABLE");
                assertThat(created.snapshotCreateTime()).isNotNull();

                assertThat(client.getSnapshot(request -> request.snapshotArn(created.snapshotArn())).snapshot()
                        .snapshotName()).isEqualTo(snapshotName);
                assertThat(client.updateSnapshot(request -> request
                        .snapshotName(snapshotName).retentionPeriod(30)).snapshot().snapshotRetentionPeriod())
                        .isEqualTo(30);
                assertThat(client.listSnapshots(request -> request.namespaceName(namespaceName)).snapshots())
                        .extracting(Snapshot::snapshotName)
                        .containsExactly(snapshotName);

                assertThat(client.restoreFromSnapshot(request -> request
                        .namespaceName(namespaceName).workgroupName(workgroupName).snapshotName(snapshotName))
                        .snapshotName()).isEqualTo(snapshotName);

                client.deleteSnapshot(request -> request.snapshotName(snapshotName));
                assertThatThrownBy(() -> client.getSnapshot(request -> request.snapshotName(snapshotName)))
                        .isInstanceOf(ResourceNotFoundException.class);
            } finally {
                deleteSnapshotBestEffort(client, snapshotName);
                deleteWorkgroupBestEffort(client, workgroupName);
                deleteBestEffort(client, namespaceName);
            }
        }
    }

    @Test
    void usageLimitLifecycleUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates a workgroup and asserts emulator-local behavior");

        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            String namespaceName = "floci-compat-limit-ns";
            String workgroupName = "floci-compat-limit-wg";
            try {
                client.createNamespace(request -> request.namespaceName(namespaceName).adminUsername("admin")
                        .adminUserPassword("Secret123!"));
                String workgroupArn = client.createWorkgroup(request -> request
                        .workgroupName(workgroupName).namespaceName(namespaceName)).workgroup().workgroupArn();

                UsageLimit created = client.createUsageLimit(request -> request
                        .resourceArn(workgroupArn)
                        .usageType(UsageLimitUsageType.SERVERLESS_COMPUTE)
                        .amount(60L)).usageLimit();
                assertThat(created.amount()).isEqualTo(60L);
                assertThat(created.period()).isEqualTo(UsageLimitPeriod.MONTHLY);
                assertThat(created.breachAction()).isEqualTo(UsageLimitBreachAction.LOG);
                assertThat(created.resourceArn()).isEqualTo(workgroupArn);
                assertThat(created.usageLimitArn()).contains(":usagelimit/");

                UsageLimit second = client.createUsageLimit(request -> request
                        .resourceArn(workgroupArn)
                        .usageType(UsageLimitUsageType.SERVERLESS_COMPUTE)
                        .period(UsageLimitPeriod.DAILY)
                        .amount(10L)).usageLimit();
                assertThat(second.period()).isEqualTo(UsageLimitPeriod.DAILY);

                UsageLimit updated = client.updateUsageLimit(request -> request
                        .usageLimitId(created.usageLimitId())
                        .amount(90L)
                        .breachAction(UsageLimitBreachAction.DEACTIVATE)).usageLimit();
                assertThat(updated.amount()).isEqualTo(90L);
                assertThat(updated.breachAction()).isEqualTo(UsageLimitBreachAction.DEACTIVATE);

                assertThat(client.getUsageLimit(request -> request.usageLimitId(created.usageLimitId()))
                        .usageLimit().amount()).isEqualTo(90L);
                assertThat(client.listUsageLimits(request -> request.resourceArn(workgroupArn)).usageLimits())
                        .extracting(UsageLimit::usageLimitId)
                        .containsExactlyInAnyOrder(created.usageLimitId(), second.usageLimitId());

                client.deleteUsageLimit(request -> request.usageLimitId(second.usageLimitId()));
                client.deleteUsageLimit(request -> request.usageLimitId(created.usageLimitId()));
                assertThatThrownBy(() -> client.getUsageLimit(request -> request.usageLimitId(created.usageLimitId())))
                        .isInstanceOf(ResourceNotFoundException.class);
            } finally {
                deleteWorkgroupBestEffort(client, workgroupName);
                deleteBestEffort(client, namespaceName);
            }
        }
    }

    @Test
    void releaseTracksUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Asserts the emulator's static release tracks");
        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            assertThat(client.listTracks(request -> {}).tracks())
                    .extracting(ServerlessTrack::trackName).containsExactly("current", "trailing");
            assertThatThrownBy(() -> client.getTrack(request -> request.trackName("preview")))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void reservationsAndManagedWorkgroupsAreEmptyUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Asserts that the emulator offers no reservations");
        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            assertThat(client.listManagedWorkgroups(request -> {}).managedWorkgroups()).isEmpty();
            assertThat(client.listReservations(request -> {}).reservationsList()).isEmpty();
            assertThat(client.listReservationOfferings(request -> {}).reservationOfferingsList()).isEmpty();
            assertThatThrownBy(() -> client.getReservation(request -> request.reservationId("r-1")))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void resourcePolicyUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates resources and asserts emulator-local behavior");

        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            String namespaceName = "floci-compat-policy-ns";
            String snapshotName = "floci-compat-policy-snap";
            try {
                client.createNamespace(request -> request.namespaceName(namespaceName).adminUsername("admin")
                        .adminUserPassword("Secret123!"));
                String snapshotArn = client.createSnapshot(request -> request
                        .snapshotName(snapshotName).namespaceName(namespaceName)).snapshot().snapshotArn();

                client.putResourcePolicy(request -> request.resourceArn(snapshotArn).policy("{\"Version\":\"2012-10-17\"}"));
                assertThat(client.getResourcePolicy(request -> request.resourceArn(snapshotArn))
                        .resourcePolicy().policy()).contains("2012-10-17");
                client.deleteResourcePolicy(request -> request.resourceArn(snapshotArn));
                assertThatThrownBy(() -> client.getResourcePolicy(request -> request.resourceArn(snapshotArn)))
                        .isInstanceOf(ResourceNotFoundException.class);
            } finally {
                deleteSnapshotBestEffort(client, snapshotName);
                deleteBestEffort(client, namespaceName);
            }
        }
    }

    @Test
    void endpointAccessUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates resources and asserts emulator-local behavior");

        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            String namespaceName = "floci-compat-endpoint-ns";
            String workgroupName = "floci-compat-endpoint-wg";
            try {
                client.createNamespace(request -> request.namespaceName(namespaceName).adminUsername("admin")
                        .adminUserPassword("Secret123!"));
                client.createWorkgroup(request -> request.workgroupName(workgroupName).namespaceName(namespaceName));

                EndpointAccess endpoint = client.createEndpointAccess(request -> request
                        .endpointName("floci-compat-endpoint-ep").workgroupName(workgroupName)
                        .subnetIds("subnet-a")).endpoint();
                assertThat(endpoint.endpointStatus()).isEqualTo("ACTIVE");
                assertThat(endpoint.endpointCreateTime()).isNotNull();
                assertThat(endpoint.vpcSecurityGroups()).isEmpty();
                assertThatThrownBy(() -> client.createEndpointAccess(request -> request
                        .endpointName("invalid--endpoint").workgroupName(workgroupName).subnetIds("subnet-a")))
                        .isInstanceOf(ValidationException.class);
                assertThat(client.listEndpointAccess(request -> request.workgroupName(workgroupName)).endpoints())
                        .extracting(EndpointAccess::endpointName).containsExactly("floci-compat-endpoint-ep");
                client.deleteEndpointAccess(request -> request.endpointName("floci-compat-endpoint-ep"));
            } finally {
                deleteWorkgroupBestEffort(client, workgroupName);
                deleteBestEffort(client, namespaceName);
            }
        }
    }

    @Test
    void customDomainAssociationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates resources and asserts emulator-local behavior");

        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            String namespaceName = "floci-compat-domain-ns";
            String workgroupName = "floci-compat-domain-wg";
            String certificate = "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";
            try {
                client.createNamespace(request -> request.namespaceName(namespaceName).adminUsername("admin")
                        .adminUserPassword("Secret123!"));
                client.createWorkgroup(request -> request.workgroupName(workgroupName).namespaceName(namespaceName));

                client.createCustomDomainAssociation(request -> request.workgroupName(workgroupName)
                        .customDomainName("db.example.com").customDomainCertificateArn(certificate));
                assertThat(client.listCustomDomainAssociations(request -> request.customDomainName("db.example.com"))
                        .associations()).hasSize(1);

                GetCredentialsResponse credentials = client.getCredentials(request -> request
                        .customDomainName("db.example.com")
                        .dbName("dev"));
                assertThat(credentials.dbUser()).isNotBlank();
                assertThat(credentials.dbPassword()).isNotBlank();
                assertThat(credentials.expiration()).isAfter(Instant.now());

                assertThatThrownBy(() -> client.getCredentials(request -> request
                        .customDomainName("db.example.com")
                        .workgroupName(workgroupName)))
                        .isInstanceOf(ValidationException.class);

                client.deleteCustomDomainAssociation(request -> request.workgroupName(workgroupName)
                        .customDomainName("db.example.com"));

                assertThatThrownBy(() -> client.getCredentials(request -> request
                        .customDomainName("db.example.com")))
                        .isInstanceOf(ResourceNotFoundException.class);
            } finally {
                deleteWorkgroupBestEffort(client, workgroupName);
                deleteBestEffort(client, namespaceName);
            }
        }
    }

    @Test
    void snapshotCopyDestinationCanChangeOnlyAfterDeletion() {
        assumeFalse(TestFixtures.isRealAws(), "Asserts metadata-only snapshot copying");
        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            String namespaceName = "floci-compat-copy-ns";
            try {
                client.createNamespace(request -> request.namespaceName(namespaceName).adminUsername("admin"));
                String copyId = client.createSnapshotCopyConfiguration(request -> request
                        .namespaceName(namespaceName).destinationRegion("us-west-2"))
                        .snapshotCopyConfiguration().snapshotCopyConfigurationId();
                assertThatThrownBy(() -> client.createSnapshotCopyConfiguration(request -> request
                        .namespaceName(namespaceName).destinationRegion("eu-west-1")))
                        .isInstanceOf(ConflictException.class);
                client.deleteSnapshotCopyConfiguration(request -> request.snapshotCopyConfigurationId(copyId));
                String replacementId = client.createSnapshotCopyConfiguration(request -> request
                        .namespaceName(namespaceName).destinationRegion("eu-west-1"))
                        .snapshotCopyConfiguration().snapshotCopyConfigurationId();
                assertThat(client.listSnapshotCopyConfigurations(request -> request.namespaceName(namespaceName))
                        .snapshotCopyConfigurations()).extracting(configuration -> configuration.destinationRegion())
                        .containsExactly("eu-west-1");
                assertThatThrownBy(() -> client.listSnapshotCopyConfigurations(request -> request
                        .namespaceName(namespaceName).maxResults(0)))
                        .isInstanceOf(ValidationException.class);
                assertThatThrownBy(() -> client.listSnapshotCopyConfigurations(request -> request
                        .namespaceName(namespaceName).nextToken("bad-token")))
                        .isInstanceOf(InvalidPaginationException.class);
                client.deleteSnapshotCopyConfiguration(request -> request.snapshotCopyConfigurationId(replacementId));
            } finally {
                deleteBestEffort(client, namespaceName);
            }
        }
    }

    @Test
    void scheduledActionUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates resources and asserts emulator-local behavior");

        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            String namespaceName = "floci-compat-sched-ns";
            try {
                client.createNamespace(request -> request.namespaceName(namespaceName).adminUsername("admin")
                        .adminUserPassword("Secret123!"));

                ScheduledActionResponse action = client.createScheduledAction(request -> request
                        .scheduledActionName("floci-compat-sched").namespaceName(namespaceName)
                        .roleArn("arn:aws:iam::000000000000:role/r")
                        .schedule(Schedule.builder().cron("cron(0 3 * * ? *)").build())
                        .targetAction(TargetAction.builder().createSnapshot(CreateSnapshotScheduleActionParameters
                                .builder().namespaceName(namespaceName).snapshotNamePrefix("nightly").build())
                                .build())).scheduledAction();
                assertThat(action.state().toString()).isEqualTo("ACTIVE");
                assertThat(action.schedule().cron()).isEqualTo("cron(0 3 * * ? *)");
                client.deleteScheduledAction(request -> request.scheduledActionName("floci-compat-sched"));
            } finally {
                deleteBestEffort(client, namespaceName);
            }
        }
    }
    private static void deleteSnapshotBestEffort(RedshiftServerlessClient client, String snapshotName) {
        try {
            client.deleteSnapshot(request -> request.snapshotName(snapshotName));
        } catch (Exception cleanupError) {
            LOG.warnf(cleanupError, "Best-effort cleanup failed for snapshotName=%s", snapshotName);
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
