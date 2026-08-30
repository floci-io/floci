package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RedshiftOperationsTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260822/us-east-1/redshift/aws4_request";

    @InjectMock
    RedshiftContainerManager containerManager;

    @Test
    @Order(1)
    void testParameterGroupLifecycle() {
        // 1. CreateClusterParameterGroup
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateClusterParameterGroup")
            .formParam("ParameterGroupName", "pg-test-1")
            .formParam("ParameterGroupFamily", "redshift-1.0")
            .formParam("Description", "Test Redshift Parameter Group")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ParameterGroupName>pg-test-1</ParameterGroupName>"))
            .body(containsString("<ParameterGroupFamily>redshift-1.0</ParameterGroupFamily>"));

        // 2. DescribeClusterParameterGroups
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeClusterParameterGroups")
            .formParam("ParameterGroupName", "pg-test-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ParameterGroupName>pg-test-1</ParameterGroupName>"));

        // 2b. ModifyClusterParameterGroup
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "ModifyClusterParameterGroup")
            .formParam("ParameterGroupName", "pg-test-1")
            .formParam("Parameters.member.1.ParameterName", "statement_timeout")
            .formParam("Parameters.member.1.ParameterValue", "5000")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ParameterGroupName>pg-test-1</ParameterGroupName>"));

        // 3. DescribeClusterParameters
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeClusterParameters")
            .formParam("ParameterGroupName", "pg-test-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<DescribeClusterParametersResponse>"))
            .body(containsString("<ParameterName>statement_timeout</ParameterName>"))
            .body(containsString("<ParameterValue>5000</ParameterValue>"));

        // 4. DeleteClusterParameterGroup
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteClusterParameterGroup")
            .formParam("ParameterGroupName", "pg-test-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<DeleteClusterParameterGroupResponse>"));
    }

    @Test
    @Order(2)
    void testClusterAndSnapshotLifecycle() {
        when(containerManager.start(any(), eq("cluster-src"), any(), any()))
                .thenReturn(new RedshiftContainerHandle("c1", "cluster-src", "localhost", 5439));
        org.mockito.Mockito.doAnswer(invocation -> {
            Path p = invocation.getArgument(3);
            Files.writeString(p, "-- dump sql table test_data;");
            return null;
        }).when(containerManager).takeSnapshot(any(), eq("cluster-src"), eq("admin"), any(Path.class));
        when(containerManager.start(any(), eq("cluster-restored"), any(), any()))
                .thenReturn(new RedshiftContainerHandle("c2", "cluster-restored", "localhost", 5440));

        // 1. CreateCluster
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateCluster")
            .formParam("ClusterIdentifier", "cluster-src")
            .formParam("NodeType", "dc2.large")
            .formParam("MasterUsername", "admin")
            .formParam("MasterUserPassword", "password123")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterIdentifier>cluster-src</ClusterIdentifier>"))
            .body(containsString("<ClusterStatus>available</ClusterStatus>"));

        // 1b. RebootCluster — must preserve data (no Docker volume backs this container)
        when(containerManager.getContainer(any(), eq("cluster-src")))
                .thenReturn(Optional.of(new RedshiftContainerHandle("c1", "cluster-src", "localhost", 5439)));
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "RebootCluster")
            .formParam("ClusterIdentifier", "cluster-src")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterIdentifier>cluster-src</ClusterIdentifier>"))
            .body(containsString("<ClusterStatus>available</ClusterStatus>"));

        // 1c. ModifyCluster
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "ModifyCluster")
            .formParam("ClusterIdentifier", "cluster-src")
            .formParam("NodeType", "ra3.xlplus")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<NodeType>ra3.xlplus</NodeType>"));

        // 2. CreateClusterSnapshot
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateClusterSnapshot")
            .formParam("SnapshotIdentifier", "snap-test-1")
            .formParam("ClusterIdentifier", "cluster-src")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<SnapshotIdentifier>snap-test-1</SnapshotIdentifier>"))
            .body(containsString("<ClusterIdentifier>cluster-src</ClusterIdentifier>"));

        // 3. DescribeClusterSnapshots
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeClusterSnapshots")
            .formParam("SnapshotIdentifier", "snap-test-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<SnapshotIdentifier>snap-test-1</SnapshotIdentifier>"));

        // 4. DeleteCluster
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteCluster")
            .formParam("ClusterIdentifier", "cluster-src")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterIdentifier>cluster-src</ClusterIdentifier>"))
            .body(containsString("<ClusterStatus>deleting</ClusterStatus>"));

        // 5. RestoreFromClusterSnapshot
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "RestoreFromClusterSnapshot")
            .formParam("ClusterIdentifier", "cluster-restored")
            .formParam("SnapshotIdentifier", "snap-test-1")
            .formParam("NodeType", "dc2.large")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterIdentifier>cluster-restored</ClusterIdentifier>"))
            .body(containsString("<ClusterStatus>available</ClusterStatus>"));

        // 6. DeleteClusterSnapshot
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteClusterSnapshot")
            .formParam("SnapshotIdentifier", "snap-test-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<SnapshotIdentifier>snap-test-1</SnapshotIdentifier>"))
            .body(containsString("<Status>deleted</Status>"));

        // 7. Delete restored cluster
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteCluster")
            .formParam("ClusterIdentifier", "cluster-restored")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterIdentifier>cluster-restored</ClusterIdentifier>"));
    }

    @Test
    @Order(4)
    void testClusterSubnetGroupLifecycle() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateClusterSubnetGroup")
            .formParam("ClusterSubnetGroupName", "subnet-group-1")
            .formParam("Description", "Test subnet group")
            .formParam("SubnetIds.member.1", "subnet-aaa")
            .formParam("SubnetIds.member.2", "subnet-bbb")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterSubnetGroupName>subnet-group-1</ClusterSubnetGroupName>"))
            .body(containsString("<SubnetIdentifier>subnet-aaa</SubnetIdentifier>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeClusterSubnetGroups")
            .formParam("ClusterSubnetGroupName", "subnet-group-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ClusterSubnetGroupName>subnet-group-1</ClusterSubnetGroupName>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "ModifyClusterSubnetGroup")
            .formParam("ClusterSubnetGroupName", "subnet-group-1")
            .formParam("Description", "Updated")
            .formParam("SubnetIds.member.1", "subnet-ccc")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubnetIdentifier>subnet-ccc</SubnetIdentifier>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteClusterSubnetGroup")
            .formParam("ClusterSubnetGroupName", "subnet-group-1")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void testTagLifecycle() {
        when(containerManager.start(any(), eq("cluster-tags"), any(), any()))
                .thenReturn(new RedshiftContainerHandle("c3", "cluster-tags", "localhost", 5441));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateCluster")
            .formParam("ClusterIdentifier", "cluster-tags")
            .formParam("NodeType", "dc2.large")
            .formParam("MasterUsername", "admin")
            .formParam("MasterUserPassword", "password123")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String clusterArn = "arn:aws:redshift:us-east-1:000000000000:cluster:cluster-tags";

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateTags")
            .formParam("ResourceName", clusterArn)
            .formParam("Tags.member.1.Key", "env")
            .formParam("Tags.member.1.Value", "test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeTags")
            .formParam("ResourceName", clusterArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>env</Key>"))
            .body(containsString("<Value>test</Value>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteTags")
            .formParam("ResourceName", clusterArn)
            .formParam("TagKeys.member.1", "env")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteCluster")
            .formParam("ClusterIdentifier", "cluster-tags")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
