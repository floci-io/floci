package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.neptune.NeptuneClient;
import software.amazon.awssdk.services.neptune.model.CreateDbClusterRequest;
import software.amazon.awssdk.services.neptune.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.neptune.model.DeleteDbClusterRequest;
import software.amazon.awssdk.services.neptune.model.DeleteDbInstanceRequest;
import software.amazon.awssdk.services.neptune.model.DescribeDbClustersRequest;
import software.amazon.awssdk.services.neptune.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.neptune.model.InvalidDbClusterStateException;
import software.amazon.awssdk.services.neptune.model.ModifyDbClusterRequest;
import software.amazon.awssdk.services.neptune.model.ModifyDbInstanceRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Neptune Cluster and Instance Management")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NeptuneTest {

    private static NeptuneClient neptune;
    private static final String CLUSTER_ID = TestFixtures.uniqueName("neptune-cl");
    private static final String INSTANCE_ID = TestFixtures.uniqueName("neptune-inst");

    @BeforeAll
    static void setup() {
        neptune = TestFixtures.neptuneClient();
    }

    @AfterAll
    static void cleanup() {
        if (neptune == null) return;
        try {
            neptune.deleteDBInstance(DeleteDbInstanceRequest.builder()
                    .dbInstanceIdentifier(INSTANCE_ID)
                    .build());
        } catch (Exception ignored) {}
        try {
            neptune.deleteDBCluster(DeleteDbClusterRequest.builder()
                    .dbClusterIdentifier(CLUSTER_ID)
                    .skipFinalSnapshot(true)
                    .build());
        } catch (Exception ignored) {}
        neptune.close();
    }

    @Test
    @Order(1)
    @DisplayName("CreateDBCluster returns valid cluster descriptor")
    void createCluster() {
        var response = neptune.createDBCluster(CreateDbClusterRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID)
                .engine("neptune")
                .build());

        var cluster = response.dbCluster();
        assertThat(cluster.dbClusterIdentifier()).isEqualTo(CLUSTER_ID);
        assertThat(cluster.engine()).isEqualTo("neptune");
        assertThat(cluster.status()).isEqualTo("available");
        assertThat(cluster.dbClusterArn()).startsWith("arn:aws:neptune:");
        assertThat(cluster.port()).isGreaterThan(0);
    }

    @Test
    @Order(2)
    @DisplayName("CreateDBCluster fails with DBClusterAlreadyExistsFault on duplicate")
    void createClusterDuplicate() {
        assertThatThrownBy(() ->
                neptune.createDBCluster(CreateDbClusterRequest.builder()
                        .dbClusterIdentifier(CLUSTER_ID)
                        .engine("neptune")
                        .build()))
                .hasMessageContaining("already exists");
    }

    @Test
    @Order(3)
    @DisplayName("DescribeDBClusters returns created cluster")
    void describeClusters() {
        var response = neptune.describeDBClusters(DescribeDbClustersRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID)
                .build());

        assertThat(response.dbClusters()).hasSize(1);
        assertThat(response.dbClusters().get(0).dbClusterIdentifier()).isEqualTo(CLUSTER_ID);
    }

    @Test
    @Order(4)
    @DisplayName("ModifyDBCluster updates IAM auth flag")
    void modifyCluster() {
        var response = neptune.modifyDBCluster(ModifyDbClusterRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID)
                .enableIAMDatabaseAuthentication(true)
                .build());

        assertThat(response.dbCluster().iamDatabaseAuthenticationEnabled()).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("CreateDBInstance returns valid instance descriptor")
    void createInstance() {
        var response = neptune.createDBInstance(CreateDbInstanceRequest.builder()
                .dbInstanceIdentifier(INSTANCE_ID)
                .dbClusterIdentifier(CLUSTER_ID)
                .dbInstanceClass("db.r5.large")
                .engine("neptune")
                .build());

        var instance = response.dbInstance();
        assertThat(instance.dbInstanceIdentifier()).isEqualTo(INSTANCE_ID);
        assertThat(instance.dbClusterIdentifier()).isEqualTo(CLUSTER_ID);
        assertThat(instance.dbInstanceStatus()).isEqualTo("available");
        assertThat(instance.dbInstanceArn()).startsWith("arn:aws:neptune:");
    }

    @Test
    @Order(6)
    @DisplayName("DescribeDBInstances returns created instance")
    void describeInstances() {
        var response = neptune.describeDBInstances(DescribeDbInstancesRequest.builder()
                .dbInstanceIdentifier(INSTANCE_ID)
                .build());

        assertThat(response.dbInstances()).hasSize(1);
        assertThat(response.dbInstances().get(0).dbInstanceIdentifier()).isEqualTo(INSTANCE_ID);
    }

    @Test
    @Order(7)
    @DisplayName("ModifyDBInstance updates instance class")
    void modifyInstance() {
        var response = neptune.modifyDBInstance(ModifyDbInstanceRequest.builder()
                .dbInstanceIdentifier(INSTANCE_ID)
                .dbInstanceClass("db.r5.xlarge")
                .build());

        assertThat(response.dbInstance().dbInstanceClass()).isEqualTo("db.r5.xlarge");
    }

    @Test
    @Order(8)
    @DisplayName("DeleteDBCluster fails when instances still exist")
    void deleteClusterWithInstancesFails() {
        assertThatThrownBy(() ->
                neptune.deleteDBCluster(DeleteDbClusterRequest.builder()
                        .dbClusterIdentifier(CLUSTER_ID)
                        .skipFinalSnapshot(true)
                        .build()))
                .isInstanceOf(InvalidDbClusterStateException.class);
    }

    @Test
    @Order(9)
    @DisplayName("DeleteDBInstance removes instance")
    void deleteInstance() {
        neptune.deleteDBInstance(DeleteDbInstanceRequest.builder()
                .dbInstanceIdentifier(INSTANCE_ID)
                .build());
    }

    @Test
    @Order(10)
    @DisplayName("DeleteDBCluster removes cluster after instances are gone")
    void deleteCluster() {
        neptune.deleteDBCluster(DeleteDbClusterRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID)
                .skipFinalSnapshot(true)
                .build());
    }
}
