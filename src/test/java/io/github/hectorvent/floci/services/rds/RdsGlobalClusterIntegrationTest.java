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
import static org.hamcrest.Matchers.not;

/**
 * The Aurora global database lifecycle over the Query protocol: an empty global cluster, a
 * primary and a secondary joined through CreateDBCluster, describe, switchover and failover,
 * removal in the documented order, and FailoverDBCluster inside a cluster.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsGlobalClusterIntegrationTest {

    private static final String GLOBAL = "gdb-test";
    private static final String PRIMARY = "gdb-test-primary";
    private static final String SECONDARY = "gdb-test-secondary";
    private static final String PRIMARY_ARN = "arn:aws:rds:us-east-1:000000000000:cluster:" + PRIMARY;
    private static final String SECONDARY_ARN = "arn:aws:rds:eu-west-1:000000000000:cluster:" + SECONDARY;

    private static RequestSpecification query(String action, String region) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/" + region + "/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static RequestSpecification query(String action) {
        return query(action, "us-east-1");
    }

    @Test
    @Order(1)
    void createGlobalClusterThenPrimaryThenSecondary() {
        query("CreateGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("DatabaseName", "appdb")
                .formParam("Tags.Tag.1.Key", "team")
                .formParam("Tags.Tag.1.Value", "data")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CreateGlobalClusterResult>"))
            .body(containsString("<GlobalClusterIdentifier>" + GLOBAL + "</GlobalClusterIdentifier>"))
            .body(containsString("<GlobalClusterArn>arn:aws:rds::000000000000:global-cluster:" + GLOBAL + "</GlobalClusterArn>"))
            .body(containsString("<Status>available</Status>"))
            .body(containsString("<Engine>aurora-postgresql</Engine>"))
            .body(containsString("<GlobalClusterMembers></GlobalClusterMembers>"))
            .body(containsString("<Value>data</Value>"));

        query("CreateGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("Engine", "aurora-postgresql")
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>GlobalClusterAlreadyExistsFault</Code>"));

        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", PRIMARY)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("GlobalClusterIdentifier", GLOBAL)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<GlobalClusterIdentifier>" + GLOBAL + "</GlobalClusterIdentifier>"))
            .body(containsString("<DatabaseName>appdb</DatabaseName>"));

        query("CreateDBCluster", "eu-west-1")
                .formParam("DBClusterIdentifier", SECONDARY)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("GlobalClusterIdentifier", GLOBAL)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterArn>" + SECONDARY_ARN + "</DBClusterArn>"))
            .body(containsString("<MasterUsername>admin</MasterUsername>"))
            .body(containsString("<GlobalClusterIdentifier>" + GLOBAL + "</GlobalClusterIdentifier>"));

        query("DescribeGlobalClusters")
                .formParam("GlobalClusterIdentifier", GLOBAL)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<GlobalClusters><GlobalClusterMember>"))
            .body(containsString("<DBClusterArn>" + PRIMARY_ARN + "</DBClusterArn><Readers><member>"
                    + SECONDARY_ARN + "</member></Readers><IsWriter>true</IsWriter>"))
            .body(containsString("<DBClusterArn>" + SECONDARY_ARN + "</DBClusterArn><Readers></Readers><IsWriter>false</IsWriter>"))
            .body(containsString("<SynchronizationStatus>connected</SynchronizationStatus>"));

        // A secondary may not sit in the primary's Region nor bring its own credentials.
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", "gdb-test-third")
                .formParam("Engine", "aurora-postgresql")
                .formParam("GlobalClusterIdentifier", GLOBAL)
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>InvalidParameterCombination</Code>"));
        query("CreateDBCluster", "ap-south-1")
                .formParam("DBClusterIdentifier", "gdb-test-third")
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("GlobalClusterIdentifier", GLOBAL)
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>InvalidParameterCombination</Code>"))
            .body(containsString("Cannot specify user name"));
    }

    @Test
    @Order(2)
    void switchoverAndFailoverMoveThePrimaryRole() {
        query("SwitchoverGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("TargetDbClusterIdentifier", SECONDARY_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SwitchoverGlobalClusterResult>"))
            .body(containsString("<DBClusterArn>" + SECONDARY_ARN + "</DBClusterArn><Readers><member>"
                    + PRIMARY_ARN + "</member></Readers><IsWriter>true</IsWriter>"));

        query("SwitchoverGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("TargetDbClusterIdentifier", SECONDARY_ARN)
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>InvalidDBClusterStateFault</Code>"));

        query("FailoverGlobalCluster", "us-east-1")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("TargetDbClusterIdentifier", PRIMARY_ARN)
                .formParam("AllowDataLoss", "true")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<FailoverGlobalClusterResult>"))
            .body(containsString("<DBClusterArn>" + PRIMARY_ARN + "</DBClusterArn><Readers><member>"
                    + SECONDARY_ARN + "</member></Readers><IsWriter>true</IsWriter>"));

        query("FailoverGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("TargetDbClusterIdentifier", SECONDARY_ARN)
                .formParam("AllowDataLoss", "true")
                .formParam("Switchover", "true")
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>InvalidParameterCombination</Code>"));
    }

    @Test
    @Order(3)
    void modifyRenamesAndProtects() {
        query("ModifyGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("DeletionProtection", "true")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ModifyGlobalClusterResult>"))
            .body(containsString("<DeletionProtection>true</DeletionProtection>"));

        query("DescribeDBClusters")
                .formParam("DBClusterIdentifier", PRIMARY)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<GlobalClusterIdentifier>" + GLOBAL + "</GlobalClusterIdentifier>"));
    }

    @Test
    @Order(4)
    void removalFollowsTheDocumentedOrderAndDeletionNeedsAnEmptyUnprotectedGlobalCluster() {
        query("RemoveFromGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("DbClusterIdentifier", PRIMARY_ARN)
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>InvalidGlobalClusterStateFault</Code>"));

        query("DeleteGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>InvalidGlobalClusterStateFault</Code>"));

        query("RemoveFromGlobalCluster", "eu-west-1")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("DbClusterIdentifier", SECONDARY_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<RemoveFromGlobalClusterResult>"))
            .body(not(containsString(SECONDARY_ARN)));
        query("DescribeDBClusters", "eu-west-1")
                .formParam("DBClusterIdentifier", SECONDARY)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<GlobalClusterIdentifier>")));

        query("RemoveFromGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("DbClusterIdentifier", PRIMARY_ARN)
        .when().post("/").then().statusCode(200)
            .body(containsString("<GlobalClusterMembers></GlobalClusterMembers>"));

        query("DeleteGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>InvalidGlobalClusterStateFault</Code>"))
            .body(containsString("deletion protection"));
        query("ModifyGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
                .formParam("DeletionProtection", "false")
        .when().post("/").then().statusCode(200);
        query("DeleteGlobalCluster")
                .formParam("GlobalClusterIdentifier", GLOBAL)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DeleteGlobalClusterResult>"))
            .body(containsString("<Status>deleting</Status>"));
        query("DescribeGlobalClusters")
                .formParam("GlobalClusterIdentifier", GLOBAL)
        .when().post("/").then().statusCode(404)
            .body(containsString("<Code>GlobalClusterNotFoundFault</Code>"));
    }

    @Test
    @Order(5)
    void failoverDbClusterPromotesAReader() {
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", "gdb-test-writer")
                .formParam("DBClusterIdentifier", PRIMARY)
                .formParam("Engine", "aurora-postgresql")
                .formParam("DBInstanceClass", "db.r6g.large")
        .when().post("/").then().statusCode(200);

        query("FailoverDBCluster")
                .formParam("DBClusterIdentifier", PRIMARY)
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>InvalidDBClusterStateFault</Code>"));

        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", "gdb-test-reader")
                .formParam("DBClusterIdentifier", PRIMARY)
                .formParam("Engine", "aurora-postgresql")
                .formParam("DBInstanceClass", "db.r6g.large")
        .when().post("/").then().statusCode(200);
        query("DescribeDBClusters")
                .formParam("DBClusterIdentifier", PRIMARY)
        .when().post("/").then().statusCode(200)
            .body(containsString("<DBInstanceIdentifier>gdb-test-writer</DBInstanceIdentifier><IsClusterWriter>true</IsClusterWriter>"))
            .body(containsString("<DBInstanceIdentifier>gdb-test-reader</DBInstanceIdentifier><IsClusterWriter>false</IsClusterWriter>"));

        query("FailoverDBCluster")
                .formParam("DBClusterIdentifier", PRIMARY)
                .formParam("TargetDBInstanceIdentifier", "gdb-test-reader")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<FailoverDBClusterResult>"))
            .body(containsString("<DBInstanceIdentifier>gdb-test-reader</DBInstanceIdentifier><IsClusterWriter>true</IsClusterWriter>"))
            .body(containsString("<DBInstanceIdentifier>gdb-test-writer</DBInstanceIdentifier><IsClusterWriter>false</IsClusterWriter>"));
    }

    @Test
    @Order(6)
    void cleanUp() {
        for (String instance : new String[]{"gdb-test-writer", "gdb-test-reader"}) {
            query("DeleteDBInstance").formParam("DBInstanceIdentifier", instance)
                    .when().post("/").then().statusCode(200);
        }
        query("DeleteDBCluster").formParam("DBClusterIdentifier", PRIMARY)
                .when().post("/").then().statusCode(200);
        query("DeleteDBCluster", "eu-west-1").formParam("DBClusterIdentifier", SECONDARY)
                .when().post("/").then().statusCode(200);
    }
}
