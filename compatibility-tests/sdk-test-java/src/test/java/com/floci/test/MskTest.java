package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.kafka.KafkaClient;
import software.amazon.awssdk.services.kafka.model.*;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyRequest;
import software.amazon.awssdk.services.kms.model.NotFoundException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MSK (Managed Streaming for Kafka)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MskTest {

    private static final Logger LOG = Logger.getLogger(MskTest.class);
    private static KafkaClient kafka;
    private static String clusterArn;
    private static String scramSecretArn;
    private static String scramKeyId;
    private static final String CLUSTER_NAME = TestFixtures.uniqueName("msk-cluster");

    @BeforeAll
    static void setup() {
        kafka = TestFixtures.kafkaClient();
    }

    @AfterAll
    static void cleanup() {
        if (kafka != null) {
            if (clusterArn != null) {
                try {
                    kafka.deleteCluster(DeleteClusterRequest.builder().clusterArn(clusterArn).build());
                } catch (Exception ignored) {}
            }
            kafka.close();
        }
        if (scramSecretArn != null) {
            try (SecretsManagerClient secrets = TestFixtures.secretsManagerClient()) {
                secrets.deleteSecret(request -> request.secretId(scramSecretArn).forceDeleteWithoutRecovery(true));
            } catch (ResourceNotFoundException expected) {
                // An already-absent secret needs no further cleanup.
            } catch (Exception e) {
                LOG.warnv(e, "Failed to delete SCRAM secret {0} during test cleanup", scramSecretArn);
            }
        }
        if (scramKeyId != null) {
            try (KmsClient kms = TestFixtures.kmsClient()) {
                kms.scheduleKeyDeletion(request -> request.keyId(scramKeyId).pendingWindowInDays(7));
            } catch (NotFoundException expected) {
                // An already-absent key needs no further cleanup.
            } catch (Exception e) {
                LOG.warnv(e, "Failed to schedule deletion of SCRAM KMS key {0} during test cleanup", scramKeyId);
            }
        }
    }

    @Test
    @Order(1)
    void createCluster() {
        CreateClusterResponse response = kafka.createCluster(CreateClusterRequest.builder()
                .clusterName(CLUSTER_NAME)
                .kafkaVersion("3.6.1")
                .numberOfBrokerNodes(1)
                .brokerNodeGroupInfo(BrokerNodeGroupInfo.builder()
                        .instanceType("kafka.m5.large")
                        .clientSubnets("subnet-12345")
                        .build())
                .build());

        assertThat(response.clusterArn()).isNotNull();
        assertThat(response.clusterName()).isEqualTo(CLUSTER_NAME);
        assertThat(response.state()).isIn(ClusterState.CREATING, ClusterState.ACTIVE);
        clusterArn = response.clusterArn();
    }

    @Test
    @Order(2)
    void describeCluster() {
        DescribeClusterResponse response = kafka.describeCluster(DescribeClusterRequest.builder()
                .clusterArn(clusterArn)
                .build());

        assertThat(response.clusterInfo()).isNotNull();
        assertThat(response.clusterInfo().clusterArn()).isEqualTo(clusterArn);
        assertThat(response.clusterInfo().clusterName()).isEqualTo(CLUSTER_NAME);
    }

    @Test
    @Order(3)
    void listClusters() {
        ListClustersResponse response = kafka.listClusters(ListClustersRequest.builder().build());

        assertThat(response.clusterInfoList()).anyMatch(c -> c.clusterArn().equals(clusterArn));
    }

    @Test
    @Order(4)
    void getBootstrapBrokers() {
        GetBootstrapBrokersResponse response = kafka.getBootstrapBrokers(GetBootstrapBrokersRequest.builder()
                .clusterArn(clusterArn)
                .build());

        // In mock mode it's immediate, in real mode it might be null while CREATING
        // but our MskService handles mock=true by setting it ACTIVE immediately.
        assertThat(response.bootstrapBrokerString()).isNotNull();
    }

    // Exercised through the real AWS SDK, which is the point: the v2 Cluster shape nests
    // everything provisioned-specific under Provisioned. If the emulator returns those members
    // flat under clusterInfo, the SDK simply does not see them and provisioned() comes back
    // null - the same way terraform-provider-aws would fail to read them.
    @Test
    @Order(5)
    void createAndDescribeClusterV2() {
        String v2ClusterName = TestFixtures.uniqueName("msk-cluster-v2");
        String v2ClusterArn = null;
        try {
            CreateClusterV2Response created = kafka.createClusterV2(CreateClusterV2Request.builder()
                    .clusterName(v2ClusterName)
                    .tags(Map.of("Environment", "compat"))
                    .provisioned(ProvisionedRequest.builder()
                            .kafkaVersion("3.6.1")
                            .numberOfBrokerNodes(2)
                            .brokerNodeGroupInfo(BrokerNodeGroupInfo.builder()
                                    .instanceType("kafka.m5.large")
                                    .clientSubnets("subnet-12345")
                                    .build())
                            .build())
                    .build());

            assertThat(created.clusterArn()).isNotNull();
            assertThat(created.clusterType()).isEqualTo(ClusterType.PROVISIONED);
            v2ClusterArn = created.clusterArn();

            DescribeClusterV2Response described = kafka.describeClusterV2(DescribeClusterV2Request.builder()
                    .clusterArn(v2ClusterArn)
                    .build());

            Cluster info = described.clusterInfo();
            assertThat(info).isNotNull();
            assertThat(info.clusterArn()).isEqualTo(v2ClusterArn);
            assertThat(info.clusterName()).isEqualTo(v2ClusterName);
            assertThat(info.clusterType()).isEqualTo(ClusterType.PROVISIONED);
            assertThat(info.tags()).containsEntry("Environment", "compat");

            assertThat(info.provisioned()).isNotNull();
            assertThat(info.provisioned().numberOfBrokerNodes()).isEqualTo(2);
            assertThat(info.provisioned().currentBrokerSoftwareInfo().kafkaVersion()).isEqualTo("3.6.1");
            assertThat(info.provisioned().brokerNodeGroupInfo().instanceType()).isEqualTo("kafka.m5.large");
            assertThat(info.provisioned().brokerNodeGroupInfo().clientSubnets()).contains("subnet-12345");

            assertThat(kafka.listClustersV2(ListClustersV2Request.builder().build()).clusterInfoList())
                    .anyMatch(c -> c.provisioned() != null
                            && c.clusterName().equals(v2ClusterName));
        } finally {
            if (v2ClusterArn != null) {
                try {
                    kafka.deleteCluster(DeleteClusterRequest.builder().clusterArn(v2ClusterArn).build());
                } catch (Exception ignored) {}
            }
        }
    }

    @Test
    @Order(6)
    void batchAssociateScramSecret() {
        try (SecretsManagerClient secrets = TestFixtures.secretsManagerClient();
             KmsClient kms = TestFixtures.kmsClient()) {
            scramKeyId = kms.createKey(CreateKeyRequest.builder().build()).keyMetadata().keyId();
            CreateSecretResponse secret = secrets.createSecret(request -> request
                    .name("AmazonMSK_" + TestFixtures.uniqueName("scram"))
                    .kmsKeyId(scramKeyId)
                    .secretString("{\"username\":\"alice\",\"password\":\"password\"}"));
            scramSecretArn = secret.arn();

            BatchAssociateScramSecretResponse response = kafka.batchAssociateScramSecret(request -> request
                    .clusterArn(clusterArn).secretArnList(scramSecretArn));

            assertThat(response.clusterArn()).isEqualTo(clusterArn);
            assertThat(response.sdkHttpResponse().statusCode()).isEqualTo(200);
            assertThat(response.unprocessedScramSecrets()).isEmpty();
        }
    }

    @Test
    @Order(7)
    void listScramSecrets() {
        ListScramSecretsResponse response = kafka.listScramSecrets(request -> request.clusterArn(clusterArn));

        assertThat(response.sdkHttpResponse().statusCode()).isEqualTo(200);
        assertThat(response.secretArnList()).containsExactly(scramSecretArn);
    }

    @Test
    @Order(8)
    void batchDisassociateScramSecret() {
        BatchDisassociateScramSecretResponse response = kafka.batchDisassociateScramSecret(request -> request
                .clusterArn(clusterArn).secretArnList(scramSecretArn));

        assertThat(response.clusterArn()).isEqualTo(clusterArn);
        assertThat(response.sdkHttpResponse().statusCode()).isEqualTo(200);
        assertThat(response.unprocessedScramSecrets()).isEmpty();
    }

    @Test
    @Order(9)
    void deleteCluster() {
        DeleteClusterResponse response = kafka.deleteCluster(DeleteClusterRequest.builder()
                .clusterArn(clusterArn)
                .build());

        assertThat(response.clusterArn()).isEqualTo(clusterArn);
        assertThat(response.state()).isEqualTo(ClusterState.DELETING);
    }
}
