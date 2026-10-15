package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A role's {@code FeatureName} has to be one the engine lists in {@code SupportedFeatureNames}, on
 * {@code AddRoleToDBInstance}, {@code AddRoleToDBCluster} and {@code CreateDBCluster}.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsRoleFeatureNameIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/rds-feature";
    private static final String OTHER_ROLE = "arn:aws:iam::000000000000:role/rds-feature-other";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261014/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static void createInstance(String id, String engine) {
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", id)
                .formParam("Engine", engine)
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("AllocatedStorage", "20")
        .when().post("/").then().statusCode(200);
    }

    private static void createCluster(String id, String engine) {
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", engine)
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(1)
    void instanceFeatureMustBeOneTheEngineSupports() {
        createInstance("feature-mysql", "mysql");
        query("AddRoleToDBInstance").formParam("DBInstanceIdentifier", "feature-mysql")
                .formParam("RoleArn", ROLE).formParam("FeatureName", "Lambda")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
                .body("ErrorResponse.Error.Message", containsString("Supported features: none"));

        createInstance("feature-postgres", "postgres");
        query("AddRoleToDBInstance").formParam("DBInstanceIdentifier", "feature-postgres")
                .formParam("RoleArn", ROLE).formParam("FeatureName", "SageMaker")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
                .body("ErrorResponse.Error.Message", containsString("s3Import, s3Export, Lambda"));
        // Matched without regard to case, and kept as the request spelled it.
        query("AddRoleToDBInstance").formParam("DBInstanceIdentifier", "feature-postgres")
                .formParam("RoleArn", ROLE).formParam("FeatureName", "s3import")
        .when().post("/").then().statusCode(200);
        query("DescribeDBInstances").formParam("DBInstanceIdentifier", "feature-postgres")
        .when().post("/").then().statusCode(200)
                .body(containsString("<FeatureName>s3import</FeatureName>"));
    }

    @Test
    @Order(5)
    void theOtherFeatureChecksMatchWithoutRegardToCaseToo() {
        // feature-postgres holds ROLE for "s3import" (order 1). The same feature in another case is
        // the same feature: a second role for it is refused, and it finds the association to remove.
        query("AddRoleToDBInstance").formParam("DBInstanceIdentifier", "feature-postgres")
                .formParam("RoleArn", OTHER_ROLE).formParam("FeatureName", "s3Import")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DBInstanceRoleAlreadyExists"));
        query("RemoveRoleFromDBInstance").formParam("DBInstanceIdentifier", "feature-postgres")
                .formParam("RoleArn", ROLE).formParam("FeatureName", "S3IMPORT")
        .when().post("/").then().statusCode(200);

        // feature-apg holds ROLE for "Bedrock" (order 2); a cluster removal matches it the same way.
        query("RemoveRoleFromDBCluster").formParam("DBClusterIdentifier", "feature-apg")
                .formParam("RoleArn", ROLE).formParam("FeatureName", "bedrock")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(2)
    void clusterFeatureMustBeOneTheEngineSupports() {
        createCluster("feature-apg", "aurora-postgresql");
        query("AddRoleToDBCluster").formParam("DBClusterIdentifier", "feature-apg")
                .formParam("RoleArn", ROLE).formParam("FeatureName", "Bedrock")
        .when().post("/").then().statusCode(200);
        query("AddRoleToDBCluster").formParam("DBClusterIdentifier", "feature-apg")
                .formParam("RoleArn", OTHER_ROLE).formParam("FeatureName", "S3_INTEGRATION")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));

        // Aurora MySQL lists no features: its roles take none, and a named one is refused.
        createCluster("feature-ams", "aurora-mysql");
        query("AddRoleToDBCluster").formParam("DBClusterIdentifier", "feature-ams")
                .formParam("RoleArn", ROLE).formParam("FeatureName", "s3Import")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));
        query("AddRoleToDBCluster").formParam("DBClusterIdentifier", "feature-ams")
                .formParam("RoleArn", ROLE)
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(3)
    void createDbClusterWithAnUnsupportedFeatureCreatesNothing() {
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", "feature-create")
                .formParam("Engine", "aurora-mysql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("AssociatedRoles.DBClusterAssociatedRole.1.RoleArn", ROLE)
                .formParam("AssociatedRoles.DBClusterAssociatedRole.1.FeatureName", "Lambda")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));
        query("DescribeDBClusters").formParam("DBClusterIdentifier", "feature-create")
        .when().post("/").then().statusCode(404);
    }

    @Test
    @Order(4)
    void createDbClusterWithoutAnEngineChecksTheFeatureOnceTheClusterExists() {
        // No Engine means PostgreSQL, which lists no Comprehend: the roles are refused after the
        // cluster is made, and the cluster is deleted again.
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", "feature-no-engine")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("AssociatedRoles.DBClusterAssociatedRole.1.RoleArn", ROLE)
                .formParam("AssociatedRoles.DBClusterAssociatedRole.1.FeatureName", "Comprehend")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));
        query("DescribeDBClusters").formParam("DBClusterIdentifier", "feature-no-engine")
        .when().post("/").then().statusCode(404);
    }
}
