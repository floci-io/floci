package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshift.model.ClusterParameterGroup;
import software.amazon.awssdk.services.redshift.model.CreateClusterParameterGroupRequest;
import software.amazon.awssdk.services.redshift.model.CreateClusterParameterGroupResponse;
import software.amazon.awssdk.services.redshift.model.CreateClusterRequest;
import software.amazon.awssdk.services.redshift.model.CreateClusterResponse;
import software.amazon.awssdk.services.redshift.model.CreateClusterSnapshotRequest;
import software.amazon.awssdk.services.redshift.model.CreateClusterSnapshotResponse;
import software.amazon.awssdk.services.redshift.model.CreateClusterSubnetGroupRequest;
import software.amazon.awssdk.services.redshift.model.CreateTagsRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterParameterGroupRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterSnapshotRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterSubnetGroupRequest;
import software.amazon.awssdk.services.redshift.model.DeleteTagsRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClusterParameterGroupsRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClusterParameterGroupsResponse;
import software.amazon.awssdk.services.redshift.model.DescribeClusterSnapshotsRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClusterSnapshotsResponse;
import software.amazon.awssdk.services.redshift.model.DescribeClusterSubnetGroupsRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClustersRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClustersResponse;
import software.amazon.awssdk.services.redshift.model.DescribeTagsRequest;
import software.amazon.awssdk.services.redshift.model.ModifyClusterRequest;
import software.amazon.awssdk.services.redshift.model.RebootClusterRequest;
import software.amazon.awssdk.services.redshift.model.RestoreFromClusterSnapshotRequest;
import software.amazon.awssdk.services.redshift.model.RestoreFromClusterSnapshotResponse;
import software.amazon.awssdk.services.redshift.model.Snapshot;
import software.amazon.awssdk.services.redshift.model.Tag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Redshift Operations")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedshiftOperationsTest {

    private static final Logger LOG = Logger.getLogger(RedshiftOperationsTest.class.getName());

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "password123";
    private static final String DATABASE = "dev";

    private static RedshiftClient client;
    private static final List<String> clustersToCleanup = new ArrayList<>();
    private static final List<String> snapshotsToCleanup = new ArrayList<>();
    private static final List<String> parameterGroupsToCleanup = new ArrayList<>();
    private static final List<String> subnetGroupsToCleanup = new ArrayList<>();

    @BeforeAll
    static void setup() {
        client = TestFixtures.redshiftClient();
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            for (String clusterId : clustersToCleanup) {
                try {
                    client.deleteCluster(DeleteClusterRequest.builder()
                            .clusterIdentifier(clusterId)
                            .build());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to clean up Redshift cluster " + clusterId, e);
                }
            }
            for (String snapId : snapshotsToCleanup) {
                try {
                    client.deleteClusterSnapshot(DeleteClusterSnapshotRequest.builder()
                            .snapshotIdentifier(snapId)
                            .build());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to clean up Redshift snapshot " + snapId, e);
                }
            }
            for (String pgName : parameterGroupsToCleanup) {
                try {
                    client.deleteClusterParameterGroup(DeleteClusterParameterGroupRequest.builder()
                            .parameterGroupName(pgName)
                            .build());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to clean up Redshift parameter group " + pgName, e);
                }
            }
            for (String subnetGroupName : subnetGroupsToCleanup) {
                try {
                    client.deleteClusterSubnetGroup(DeleteClusterSubnetGroupRequest.builder()
                            .clusterSubnetGroupName(subnetGroupName)
                            .build());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to clean up Redshift cluster subnet group " + subnetGroupName, e);
                }
            }
            client.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Create cluster, insert data, snapshot, restore to new cluster, verify data")
    void testSnapshotAndRestoreLifecycle() throws Exception {
        String sourceClusterId = TestFixtures.uniqueName("rs-source");
        String restoredClusterId = TestFixtures.uniqueName("rs-restored");
        String snapshotId = TestFixtures.uniqueName("rs-snap");

        clustersToCleanup.add(sourceClusterId);
        clustersToCleanup.add(restoredClusterId);
        snapshotsToCleanup.add(snapshotId);

        // 1. Create source cluster
        CreateClusterResponse createRes = client.createCluster(CreateClusterRequest.builder()
                .clusterIdentifier(sourceClusterId)
                .nodeType("dc2.large")
                .masterUsername(USERNAME)
                .masterUserPassword(PASSWORD)
                .build());

        assertThat(createRes.cluster()).isNotNull();
        assertThat(createRes.cluster().clusterIdentifier()).isEqualTo(sourceClusterId);

        DescribeClustersResponse descRes = client.describeClusters(DescribeClustersRequest.builder()
                .clusterIdentifier(sourceClusterId)
                .build());
        Cluster sourceCluster = descRes.clusters().get(0);
        assertThat(sourceCluster.endpoint()).isNotNull();
        String sourceHost = sourceCluster.endpoint().address();
        int sourcePort = sourceCluster.endpoint().port();

        // 2. Use JDBC to create a table and insert a row
        try (Connection conn = awaitPostgresConnection(sourceHost, sourcePort, USERNAME, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE test_data (id INT PRIMARY KEY, name VARCHAR(100));");
            stmt.execute("INSERT INTO test_data (id, name) VALUES (1, 'floci-redshift-snapshot-val');");
        }

        // 3. Call createClusterSnapshot via AWS SDK
        CreateClusterSnapshotResponse snapRes = client.createClusterSnapshot(CreateClusterSnapshotRequest.builder()
                .snapshotIdentifier(snapshotId)
                .clusterIdentifier(sourceClusterId)
                .build());

        Snapshot snapshot = snapRes.snapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.snapshotIdentifier()).isEqualTo(snapshotId);
        assertThat(snapshot.clusterIdentifier()).isEqualTo(sourceClusterId);
        assertThat(snapshot.status()).isEqualTo("available");

        DescribeClusterSnapshotsResponse descSnapRes = client.describeClusterSnapshots(DescribeClusterSnapshotsRequest.builder()
                .snapshotIdentifier(snapshotId)
                .build());
        assertThat(descSnapRes.snapshots()).isNotEmpty();
        assertThat(descSnapRes.snapshots().get(0).snapshotIdentifier()).isEqualTo(snapshotId);

        // 4. Delete source cluster
        client.deleteCluster(DeleteClusterRequest.builder()
                .clusterIdentifier(sourceClusterId)
                .build());
        clustersToCleanup.remove(sourceClusterId);

        // 5. Restore from snapshot to a new cluster identifier
        RestoreFromClusterSnapshotResponse restoreRes = client.restoreFromClusterSnapshot(
                RestoreFromClusterSnapshotRequest.builder()
                        .clusterIdentifier(restoredClusterId)
                        .snapshotIdentifier(snapshotId)
                        .nodeType("dc2.large")
                        .build());

        Cluster restoredCluster = restoreRes.cluster();
        assertThat(restoredCluster).isNotNull();
        assertThat(restoredCluster.clusterIdentifier()).isEqualTo(restoredClusterId);
        assertThat(restoredCluster.endpoint()).isNotNull();

        String restoredHost = restoredCluster.endpoint().address();
        int restoredPort = restoredCluster.endpoint().port();

        // 6. Use JDBC to query restored cluster and verify row exists
        try (Connection conn = awaitPostgresConnection(restoredHost, restoredPort, USERNAME, PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM test_data WHERE id = 1;")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("name")).isEqualTo("floci-redshift-snapshot-val");
        }

        // Cleanup restored cluster and snapshot
        client.deleteCluster(DeleteClusterRequest.builder()
                .clusterIdentifier(restoredClusterId)
                .build());
        clustersToCleanup.remove(restoredClusterId);

        client.deleteClusterSnapshot(DeleteClusterSnapshotRequest.builder()
                .snapshotIdentifier(snapshotId)
                .build());
        snapshotsToCleanup.remove(snapshotId);
    }

    @Test
    @Order(2)
    @DisplayName("Create, describe, and delete cluster parameter group")
    void testClusterParameterGroupLifecycle() {
        String pgName = TestFixtures.uniqueName("rs-pg");
        parameterGroupsToCleanup.add(pgName);

        // 1. Create cluster parameter group
        CreateClusterParameterGroupResponse createPgRes = client.createClusterParameterGroup(
                CreateClusterParameterGroupRequest.builder()
                        .parameterGroupName(pgName)
                        .parameterGroupFamily("redshift-1.0")
                        .description("Test parameter group for Redshift")
                        .build());

        ClusterParameterGroup pg = createPgRes.clusterParameterGroup();
        assertThat(pg).isNotNull();
        assertThat(pg.parameterGroupName()).isEqualTo(pgName);
        assertThat(pg.parameterGroupFamily()).isEqualTo("redshift-1.0");
        assertThat(pg.description()).isEqualTo("Test parameter group for Redshift");

        // 2. Describe cluster parameter groups
        DescribeClusterParameterGroupsResponse descPgRes = client.describeClusterParameterGroups(
                DescribeClusterParameterGroupsRequest.builder()
                        .parameterGroupName(pgName)
                        .build());

        assertThat(descPgRes.parameterGroups()).isNotEmpty();
        ClusterParameterGroup foundPg = descPgRes.parameterGroups().stream()
                .filter(g -> pgName.equals(g.parameterGroupName()))
                .findFirst()
                .orElse(null);
        assertThat(foundPg).isNotNull();
        assertThat(foundPg.parameterGroupFamily()).isEqualTo("redshift-1.0");
        assertThat(foundPg.description()).isEqualTo("Test parameter group for Redshift");

        // 3. Delete cluster parameter group
        client.deleteClusterParameterGroup(DeleteClusterParameterGroupRequest.builder()
                .parameterGroupName(pgName)
                .build());
        parameterGroupsToCleanup.remove(pgName);
    }

    @Test
    @Order(3)
    void testTaggingAndSubnetGroupAndModify() {
        // Cluster subnet group
        String subnetGroupName = TestFixtures.uniqueName("rs-subnet-group");
        client.createClusterSubnetGroup(CreateClusterSubnetGroupRequest.builder()
                .clusterSubnetGroupName(subnetGroupName)
                .description("SDK test subnet group")
                .subnetIds("subnet-aaa", "subnet-bbb")
                .build());
        subnetGroupsToCleanup.add(subnetGroupName);

        var describedGroups = client.describeClusterSubnetGroups(
                DescribeClusterSubnetGroupsRequest.builder()
                        .clusterSubnetGroupName(subnetGroupName)
                        .build());
        assertThat(describedGroups.clusterSubnetGroups()).hasSize(1);
        assertThat(describedGroups.clusterSubnetGroups().get(0).subnets()).hasSize(2);

        // Cluster + tagging + modify + reboot
        String clusterId = TestFixtures.uniqueName("rs-tag-cluster");
        CreateClusterResponse created = client.createCluster(CreateClusterRequest.builder()
                .clusterIdentifier(clusterId)
                .nodeType("dc2.large")
                .masterUsername(USERNAME)
                .masterUserPassword(PASSWORD)
                .build());
        clustersToCleanup.add(clusterId);
        String arn = "arn:aws:redshift:us-east-1:000000000000:cluster:" + clusterId;

        client.createTags(CreateTagsRequest.builder()
                .resourceName(arn)
                .tags(Tag.builder().key("env").value("test").build())
                .build());

        var described = client.describeTags(DescribeTagsRequest.builder()
                .resourceName(arn)
                .build());
        assertThat(described.taggedResources()).anyMatch(t -> "env".equals(t.tag().key()) && "test".equals(t.tag().value()));

        client.deleteTags(DeleteTagsRequest.builder()
                .resourceName(arn)
                .tagKeys("env")
                .build());

        var modified = client.modifyCluster(ModifyClusterRequest.builder()
                .clusterIdentifier(clusterId)
                .nodeType("ra3.xlplus")
                .build());
        assertThat(modified.cluster().nodeType()).isEqualTo("ra3.xlplus");

        var rebooted = client.rebootCluster(RebootClusterRequest.builder()
                .clusterIdentifier(clusterId)
                .build());
        assertThat(rebooted.cluster().clusterStatus()).isEqualTo("available");
    }

    private static Connection awaitPostgresConnection(String host, int port, String username, String password) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                Properties properties = new Properties();
                properties.setProperty("user", username);
                properties.setProperty("password", password);
                properties.setProperty("sslmode", "disable");
                properties.setProperty("connectTimeout", "5");
                return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + DATABASE, properties);
            } catch (SQLException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw last != null ? last : new SQLException("Timed out waiting for PostgreSQL connection at " + host + ":" + port);
    }
}
