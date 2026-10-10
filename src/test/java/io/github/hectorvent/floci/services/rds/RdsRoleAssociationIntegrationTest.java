package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * IAM role associations over the Query protocol: add and remove for DB clusters and DB instances,
 * the {@code AssociatedRoles} the describe calls return, {@code CreateDBCluster}'s own
 * {@code AssociatedRoles}, and the API reference's errors.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsRoleAssociationIntegrationTest {

    private static final String CLUSTER = "roles-cluster";
    private static final String INSTANCE = "roles-instance";
    private static final String S3_ROLE = "arn:aws:iam::000000000000:role/rds-s3";
    private static final String LAMBDA_ROLE = "arn:aws:iam::000000000000:role/rds-lambda";
    private static final String CLUSTER_ROLES =
            "DescribeDBClustersResponse.DescribeDBClustersResult.DBClusters.DBCluster.AssociatedRoles.DBClusterRole";
    private static final String INSTANCE_ROLES =
            "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.AssociatedRoles.DBInstanceRole";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261009/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static RequestSpecification clusterRole(String action, String roleArn) {
        return query(action).formParam("DBClusterIdentifier", CLUSTER).formParam("RoleArn", roleArn);
    }

    private static RequestSpecification instanceRole(String action, String roleArn, String feature) {
        return query(action).formParam("DBInstanceIdentifier", INSTANCE)
                .formParam("RoleArn", roleArn).formParam("FeatureName", feature);
    }

    @Test
    @Order(1)
    void createsTheResources() {
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
        .when().post("/").then().statusCode(200)
                .body(containsString("<AssociatedRoles></AssociatedRoles>"));
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", INSTANCE)
                .formParam("Engine", "postgres")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("AllocatedStorage", "20")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(2)
    void addsRolesToAClusterAndDescribesThem() {
        clusterRole("AddRoleToDBCluster", S3_ROLE).formParam("FeatureName", "s3Import")
        .when().post("/").then().statusCode(200)
                .body(containsString("<AddRoleToDBClusterResponse"));
        // The feature name is optional for a cluster.
        clusterRole("AddRoleToDBCluster", LAMBDA_ROLE)
        .when().post("/").then().statusCode(200);

        query("DescribeDBClusters").formParam("DBClusterIdentifier", CLUSTER)
        .when().post("/").then().statusCode(200)
                .body(CLUSTER_ROLES + "[0].RoleArn", equalTo(S3_ROLE))
                .body(CLUSTER_ROLES + "[0].FeatureName", equalTo("s3Import"))
                .body(CLUSTER_ROLES + "[0].Status", equalTo("ACTIVE"))
                .body(CLUSTER_ROLES + "[1].RoleArn", equalTo(LAMBDA_ROLE))
                .body(CLUSTER_ROLES + "[1].Status", equalTo("ACTIVE"))
                .body(not(containsString("<FeatureName></FeatureName>")));
    }

    @Test
    @Order(3)
    void refusesAClusterRoleOrFeatureAlreadyAssociated() {
        clusterRole("AddRoleToDBCluster", S3_ROLE).formParam("FeatureName", "s3Export")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DBClusterRoleAlreadyExists"));
        clusterRole("AddRoleToDBCluster", "arn:aws:iam::000000000000:role/other").formParam("FeatureName", "s3Import")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DBClusterRoleAlreadyExists"));
    }

    @Test
    @Order(4)
    void removesAClusterRoleAndRefusesOneNotAssociated() {
        // A feature name that does not match the association is not that association.
        clusterRole("RemoveRoleFromDBCluster", S3_ROLE).formParam("FeatureName", "Lambda")
        .when().post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("DBClusterRoleNotFound"));

        clusterRole("RemoveRoleFromDBCluster", S3_ROLE)
        .when().post("/").then().statusCode(200)
                .body(containsString("<RemoveRoleFromDBClusterResponse"));
        clusterRole("RemoveRoleFromDBCluster", S3_ROLE)
        .when().post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("DBClusterRoleNotFound"));

        query("DescribeDBClusters").formParam("DBClusterIdentifier", CLUSTER)
        .when().post("/").then().statusCode(200)
                .body(CLUSTER_ROLES + ".RoleArn", equalTo(LAMBDA_ROLE))
                .body(not(containsString(S3_ROLE)));
    }

    @Test
    @Order(5)
    void addsAndRemovesInstanceRolesByFeature() {
        instanceRole("AddRoleToDBInstance", S3_ROLE, "s3Import")
        .when().post("/").then().statusCode(200)
                .body(containsString("<AddRoleToDBInstanceResponse"));
        instanceRole("AddRoleToDBInstance", LAMBDA_ROLE, "Lambda")
        .when().post("/").then().statusCode(200);

        query("DescribeDBInstances").formParam("DBInstanceIdentifier", INSTANCE)
        .when().post("/").then().statusCode(200)
                .body(INSTANCE_ROLES + "[0].RoleArn", equalTo(S3_ROLE))
                .body(INSTANCE_ROLES + "[0].FeatureName", equalTo("s3Import"))
                .body(INSTANCE_ROLES + "[0].Status", equalTo("ACTIVE"))
                .body(INSTANCE_ROLES + "[1].FeatureName", equalTo("Lambda"));

        // The same role for another feature, or another role for the same feature.
        instanceRole("AddRoleToDBInstance", S3_ROLE, "s3Export")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DBInstanceRoleAlreadyExists"));
        instanceRole("AddRoleToDBInstance", "arn:aws:iam::000000000000:role/other", "Lambda")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DBInstanceRoleAlreadyExists"));

        instanceRole("RemoveRoleFromDBInstance", S3_ROLE, "Lambda")
        .when().post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("DBInstanceRoleNotFound"));
        instanceRole("RemoveRoleFromDBInstance", S3_ROLE, "s3Import")
        .when().post("/").then().statusCode(200)
                .body(containsString("<RemoveRoleFromDBInstanceResponse"));

        query("DescribeDBInstances").formParam("DBInstanceIdentifier", INSTANCE)
        .when().post("/").then().statusCode(200)
                .body(INSTANCE_ROLES + ".RoleArn", equalTo(LAMBDA_ROLE));
    }

    @Test
    @Order(6)
    void requiresTheParametersTheModelRequires() {
        query("AddRoleToDBInstance").formParam("DBInstanceIdentifier", INSTANCE).formParam("RoleArn", S3_ROLE)
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingParameter"));
        query("AddRoleToDBCluster").formParam("DBClusterIdentifier", CLUSTER)
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    @Order(7)
    void refusesUnknownResources() {
        query("AddRoleToDBCluster").formParam("DBClusterIdentifier", "no-such-cluster").formParam("RoleArn", S3_ROLE)
        .when().post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("DBClusterNotFoundFault"));
        query("AddRoleToDBInstance").formParam("DBInstanceIdentifier", "no-such-instance")
                .formParam("RoleArn", S3_ROLE).formParam("FeatureName", "s3Import")
        .when().post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("DBInstanceNotFound"));
    }

    @Test
    @Order(8)
    void refusesAStoppedResource() {
        query("StopDBInstance").formParam("DBInstanceIdentifier", INSTANCE)
        .when().post("/").then().statusCode(200);
        instanceRole("AddRoleToDBInstance", S3_ROLE, "s3Import")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidDBInstanceState"));
        query("StartDBInstance").formParam("DBInstanceIdentifier", INSTANCE)
        .when().post("/").then().statusCode(200);

        query("StopDBCluster").formParam("DBClusterIdentifier", CLUSTER)
        .when().post("/").then().statusCode(200);
        clusterRole("AddRoleToDBCluster", S3_ROLE)
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidDBClusterStateFault"));
        query("StartDBCluster").formParam("DBClusterIdentifier", CLUSTER)
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(9)
    void createDbClusterAssociatesTheRolesItNames() {
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", "roles-at-create")
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("AssociatedRoles.DBClusterAssociatedRole.1.RoleArn", S3_ROLE)
                .formParam("AssociatedRoles.DBClusterAssociatedRole.1.FeatureName", "s3Import")
                .formParam("AssociatedRoles.DBClusterAssociatedRole.2.RoleArn", LAMBDA_ROLE)
        .when().post("/").then().statusCode(200)
                .body("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.AssociatedRoles.DBClusterRole[0].RoleArn",
                        equalTo(S3_ROLE))
                .body("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.AssociatedRoles.DBClusterRole[1].RoleArn",
                        equalTo(LAMBDA_ROLE));
    }

    @Test
    @Order(10)
    void createDbClusterNamingARoleTwiceCreatesNothing() {
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", "roles-duplicate")
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("AssociatedRoles.member.1.RoleArn", S3_ROLE)
                .formParam("AssociatedRoles.member.2.RoleArn", S3_ROLE)
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DBClusterRoleAlreadyExists"));
        query("DescribeDBClusters").formParam("DBClusterIdentifier", "roles-duplicate")
        .when().post("/").then().statusCode(404);
    }
}
