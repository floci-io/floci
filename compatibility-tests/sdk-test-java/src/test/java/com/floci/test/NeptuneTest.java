package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.neptune.NeptuneClient;
import software.amazon.awssdk.services.neptune.model.AddRoleToDbClusterRequest;
import software.amazon.awssdk.services.neptune.model.AddTagsToResourceRequest;
import software.amazon.awssdk.services.neptune.model.CreateDbClusterRequest;
import software.amazon.awssdk.services.neptune.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.neptune.model.DeleteDbClusterRequest;
import software.amazon.awssdk.services.neptune.model.DeleteDbInstanceRequest;
import software.amazon.awssdk.services.neptune.model.DescribeDbClustersRequest;
import software.amazon.awssdk.services.neptune.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.neptune.model.InvalidDbClusterStateException;
import software.amazon.awssdk.services.neptune.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.neptune.model.ModifyDbClusterRequest;
import software.amazon.awssdk.services.neptune.model.ModifyDbInstanceRequest;
import software.amazon.awssdk.services.neptune.model.NeptuneException;
import software.amazon.awssdk.services.neptune.model.RemoveRoleFromDbClusterRequest;
import software.amazon.awssdk.services.neptune.model.RemoveTagsFromResourceRequest;
import software.amazon.awssdk.services.neptune.model.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
                .tags(Tag.builder().key("Name").value(CLUSTER_ID).build())
                .build());

        var cluster = response.dbCluster();
        assertThat(cluster.dbClusterIdentifier()).isEqualTo(CLUSTER_ID);
        assertThat(cluster.engine()).isEqualTo("neptune");
        assertThat(cluster.status()).isEqualTo("available");
        assertThat(cluster.dbClusterArn()).startsWith("arn:aws:neptune:");
        assertThat(cluster.port()).isGreaterThan(0);
        assertThat(cluster.storageEncrypted()).isFalse();
        assertThat(cluster.backupRetentionPeriod()).isEqualTo(1);
        assertThat(cluster.availabilityZones()).hasSize(1);
        assertThat(cluster.dbClusterParameterGroup()).isEqualTo("default.neptune1.3");
        assertThat(cluster.dbSubnetGroup()).isEqualTo("default");
        assertThat(cluster.deletionProtection()).isFalse();
        assertThat(cluster.preferredBackupWindow()).isNotBlank();
        assertThat(cluster.preferredMaintenanceWindow()).isNotBlank();
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
    @DisplayName("Tags round-trip through ListTagsForResource, AddTagsToResource and RemoveTagsFromResource")
    void tagsRoundTrip() {
        String arn = neptune.describeDBClusters(DescribeDbClustersRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID).build()).dbClusters().get(0).dbClusterArn();

        var created = neptune.listTagsForResource(ListTagsForResourceRequest.builder().resourceName(arn).build());
        assertThat(created.tagList()).containsExactly(Tag.builder().key("Name").value(CLUSTER_ID).build());

        neptune.addTagsToResource(AddTagsToResourceRequest.builder()
                .resourceName(arn)
                .tags(Tag.builder().key("team").value("graph").build())
                .build());
        neptune.removeTagsFromResource(RemoveTagsFromResourceRequest.builder()
                .resourceName(arn)
                .tagKeys("Name")
                .build());

        var updated = neptune.listTagsForResource(ListTagsForResourceRequest.builder().resourceName(arn).build());
        assertThat(updated.tagList()).containsExactly(Tag.builder().key("team").value("graph").build());
    }

    @Test
    @Order(6)
    @DisplayName("AddRoleToDBCluster and RemoveRoleFromDBCluster maintain AssociatedRoles")
    void rolesRoundTrip() {
        String roleArn = "arn:aws:iam::000000000000:role/" + CLUSTER_ID;

        neptune.addRoleToDBCluster(AddRoleToDbClusterRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID).roleArn(roleArn).build());
        var withRole = neptune.describeDBClusters(DescribeDbClustersRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID).build()).dbClusters().get(0);
        assertThat(withRole.associatedRoles()).hasSize(1);
        assertThat(withRole.associatedRoles().get(0).roleArn()).isEqualTo(roleArn);
        assertThat(withRole.associatedRoles().get(0).status()).isEqualTo("ACTIVE");

        neptune.removeRoleFromDBCluster(RemoveRoleFromDbClusterRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID).roleArn(roleArn).build());
        var withoutRole = neptune.describeDBClusters(DescribeDbClustersRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID).build()).dbClusters().get(0);
        assertThat(withoutRole.associatedRoles()).isEmpty();
    }

    @Test
    @Order(7)
    @DisplayName("ModifyDBCluster applies backup and protection settings and protection blocks delete")
    void modifySettingsAndDeletionProtection() {
        var modified = neptune.modifyDBCluster(ModifyDbClusterRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID)
                .backupRetentionPeriod(7)
                .deletionProtection(true)
                .build()).dbCluster();
        assertThat(modified.backupRetentionPeriod()).isEqualTo(7);
        assertThat(modified.deletionProtection()).isTrue();

        assertThatThrownBy(() -> neptune.deleteDBCluster(DeleteDbClusterRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID)
                .skipFinalSnapshot(true)
                .build()))
                .isInstanceOf(NeptuneException.class)
                .hasMessageContaining("deletion protection");

        var unprotected = neptune.modifyDBCluster(ModifyDbClusterRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID)
                .deletionProtection(false)
                .build()).dbCluster();
        assertThat(unprotected.deletionProtection()).isFalse();
        assertThat(unprotected.backupRetentionPeriod()).isEqualTo(7);
    }

    @Test
    @Order(15)
    @DisplayName("CreateDBInstance returns the descriptor Terraform's aws_neptune_cluster_instance reads back")
    void createInstance() {
        var response = neptune.createDBInstance(CreateDbInstanceRequest.builder()
                .dbInstanceIdentifier(INSTANCE_ID)
                .dbClusterIdentifier(CLUSTER_ID)
                .dbInstanceClass("db.r5.large")
                .engine("neptune")
                .autoMinorVersionUpgrade(true)
                .promotionTier(0)
                .publiclyAccessible(false)
                .tags(Tag.builder().key("Name").value(INSTANCE_ID).build())
                .build());

        var instance = response.dbInstance();
        assertThat(instance.dbInstanceIdentifier()).isEqualTo(INSTANCE_ID);
        assertThat(instance.dbClusterIdentifier()).isEqualTo(CLUSTER_ID);
        assertThat(instance.dbInstanceStatus()).isEqualTo("available");
        assertThat(instance.dbInstanceArn()).startsWith("arn:aws:neptune:");
        assertThat(instance.autoMinorVersionUpgrade()).isTrue();
        assertThat(instance.promotionTier()).isZero();
        assertThat(instance.publiclyAccessible()).isFalse();
        assertThat(instance.storageEncrypted()).isFalse();
        assertThat(instance.availabilityZone()).isNotBlank();
        assertThat(instance.preferredBackupWindow()).isNotBlank();
        assertThat(instance.preferredMaintenanceWindow()).isNotBlank();
        assertThat(instance.instanceCreateTime()).isNotNull();
        assertThat(instance.dbSubnetGroup().dbSubnetGroupName()).isEqualTo("default");
        assertThat(instance.dbParameterGroups()).hasSize(1);
        assertThat(instance.dbParameterGroups().get(0).dbParameterGroupName()).startsWith("default.neptune");
        assertThat(instance.endpoint().port()).isEqualTo(neptune.describeDBClusters(DescribeDbClustersRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID).build()).dbClusters().get(0).port());
    }

    @Test
    @Order(16)
    @DisplayName("DescribeDBInstances returns created instance")
    void describeInstances() {
        var response = neptune.describeDBInstances(DescribeDbInstancesRequest.builder()
                .dbInstanceIdentifier(INSTANCE_ID)
                .build());

        assertThat(response.dbInstances()).hasSize(1);
        assertThat(response.dbInstances().get(0).dbInstanceIdentifier()).isEqualTo(INSTANCE_ID);
        assertThat(response.dbInstances().get(0).promotionTier()).isZero();

        var cluster = neptune.describeDBClusters(DescribeDbClustersRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID).build()).dbClusters().get(0);
        assertThat(cluster.dbClusterMembers()).hasSize(1);
        assertThat(cluster.dbClusterMembers().get(0).dbInstanceIdentifier()).isEqualTo(INSTANCE_ID);
        assertThat(cluster.dbClusterMembers().get(0).isClusterWriter()).isTrue();

        var tags = neptune.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceName(response.dbInstances().get(0).dbInstanceArn()).build()).tagList();
        assertThat(tags).extracting(Tag::key, Tag::value).containsExactly(tuple("Name", INSTANCE_ID));
    }

    @Test
    @Order(17)
    @DisplayName("ModifyDBInstance updates the settings Terraform changes in place")
    void modifyInstance() {
        var response = neptune.modifyDBInstance(ModifyDbInstanceRequest.builder()
                .dbInstanceIdentifier(INSTANCE_ID)
                .dbInstanceClass("db.r5.xlarge")
                .autoMinorVersionUpgrade(false)
                .promotionTier(3)
                .preferredMaintenanceWindow("sun:05:00-sun:06:00")
                .applyImmediately(true)
                .build());

        var instance = response.dbInstance();
        assertThat(instance.dbInstanceClass()).isEqualTo("db.r5.xlarge");
        assertThat(instance.autoMinorVersionUpgrade()).isFalse();
        assertThat(instance.promotionTier()).isEqualTo(3);
        assertThat(instance.preferredMaintenanceWindow()).isEqualTo("sun:05:00-sun:06:00");
        assertThat(instance.publiclyAccessible()).isFalse();

        var described = neptune.describeDBInstances(DescribeDbInstancesRequest.builder()
                .dbInstanceIdentifier(INSTANCE_ID).build()).dbInstances().get(0);
        assertThat(described.promotionTier()).isEqualTo(3);
        assertThat(described.autoMinorVersionUpgrade()).isFalse();
    }

    @Test
    @Order(18)
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
    @Order(19)
    @DisplayName("DeleteDBInstance removes instance")
    void deleteInstance() {
        neptune.deleteDBInstance(DeleteDbInstanceRequest.builder()
                .dbInstanceIdentifier(INSTANCE_ID)
                .build());
    }

    @Test
    @Order(20)
    @DisplayName("DeleteDBCluster removes cluster after instances are gone")
    void deleteCluster() {
        neptune.deleteDBCluster(DeleteDbClusterRequest.builder()
                .dbClusterIdentifier(CLUSTER_ID)
                .skipFinalSnapshot(true)
                .build());
    }
}
