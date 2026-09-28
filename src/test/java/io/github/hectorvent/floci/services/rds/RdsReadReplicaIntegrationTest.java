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
 * CreateDBInstanceReadReplica, PromoteReadReplica, SwitchoverReadReplica and
 * PromoteReadReplicaDBCluster over the Query protocol: the replica inherits the source, both
 * ends report the link the way DescribeDBInstances does on AWS, promotion clears it, and the
 * two operations that no emulated engine supports answer with the API reference's errors.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsReadReplicaIntegrationTest {

    private static final String SOURCE = "replica-test-source";
    private static final String REPLICA = "replica-test-reader";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    @Order(1)
    void createReadReplicaLinksBothEndsInDescribeDbInstances() {
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", SOURCE)
                .formParam("Engine", "postgres")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBName", "appdb")
                .formParam("DBInstanceClass", "db.t3.medium")
                .formParam("AllocatedStorage", "40")
        .when().post("/").then().statusCode(200)
                .body(containsString("<ReadReplicaDBInstanceIdentifiers></ReadReplicaDBInstanceIdentifiers>"))
                .body(not(containsString("<StatusInfos>")));

        query("CreateDBInstanceReadReplica")
                .formParam("DBInstanceIdentifier", REPLICA)
                .formParam("SourceDBInstanceIdentifier", SOURCE)
                .formParam("Tags.Tag.1.Key", "role")
                .formParam("Tags.Tag.1.Value", "reader")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CreateDBInstanceReadReplicaResult>"))
            .body(containsString("<DBInstanceIdentifier>" + REPLICA + "</DBInstanceIdentifier>"))
            .body(containsString("<ReadReplicaSourceDBInstanceIdentifier>" + SOURCE
                    + "</ReadReplicaSourceDBInstanceIdentifier>"))
            .body(containsString("<Engine>postgres</Engine>"))
            .body(containsString("<EngineVersion>16.3</EngineVersion>"))
            .body(containsString("<MasterUsername>admin</MasterUsername>"))
            .body(containsString("<DBName>appdb</DBName>"))
            .body(containsString("<DBInstanceClass>db.t3.medium</DBInstanceClass>"))
            .body(containsString("<AllocatedStorage>40</AllocatedStorage>"))
            .body(containsString("<BackupRetentionPeriod>0</BackupRetentionPeriod>"))
            .body(containsString("<StatusType>read replication</StatusType>"))
            .body(containsString("<Normal>true</Normal>"))
            .body(containsString("<Status>replicating</Status>"))
            .body(containsString("<Message></Message>"))
            .body(containsString(":db:" + REPLICA + "</DBInstanceArn>"))
            .body(containsString("<Value>reader</Value>"));

        query("DescribeDBInstances")
                .formParam("DBInstanceIdentifier", SOURCE)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ReadReplicaDBInstanceIdentifier>" + REPLICA
                    + "</ReadReplicaDBInstanceIdentifier>"))
            .body(not(containsString("<ReadReplicaSourceDBInstanceIdentifier>")));
    }

    @Test
    @Order(2)
    void switchoverIsRefusedForAPostgresReplica() {
        query("SwitchoverReadReplica")
                .formParam("DBInstanceIdentifier", REPLICA)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidDBInstanceState</Code>"));

        query("SwitchoverReadReplica")
                .formParam("DBInstanceIdentifier", "replica-test-missing")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>DBInstanceNotFound</Code>"));
    }

    @Test
    @Order(3)
    void promoteReadReplicaClearsTheLinkAndTurnsBackupsOn() {
        query("PromoteReadReplica")
                .formParam("DBInstanceIdentifier", SOURCE)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidDBInstanceState</Code>"))
            .body(containsString("is not a read replica"));

        query("PromoteReadReplica")
                .formParam("DBInstanceIdentifier", REPLICA)
                .formParam("BackupRetentionPeriod", "3")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PromoteReadReplicaResult>"))
            .body(containsString("<DBInstanceIdentifier>" + REPLICA + "</DBInstanceIdentifier>"))
            .body(containsString("<BackupRetentionPeriod>3</BackupRetentionPeriod>"))
            .body(not(containsString("<ReadReplicaSourceDBInstanceIdentifier>")))
            .body(not(containsString("<StatusInfos>")));

        query("DescribeDBInstances")
                .formParam("DBInstanceIdentifier", SOURCE)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ReadReplicaDBInstanceIdentifiers></ReadReplicaDBInstanceIdentifiers>"));
    }

    @Test
    @Order(4)
    void createReadReplicaRefusesAMissingOrUnnamedSource() {
        query("CreateDBInstanceReadReplica")
                .formParam("DBInstanceIdentifier", "replica-test-orphan")
                .formParam("SourceDBInstanceIdentifier", "replica-test-missing")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>DBInstanceNotFound</Code>"));

        query("CreateDBInstanceReadReplica")
                .formParam("DBInstanceIdentifier", "replica-test-orphan")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterCombination</Code>"));
    }

    @Test
    @Order(5)
    void promoteReadReplicaDbClusterAnswersWithTheClusterErrors() {
        query("PromoteReadReplicaDBCluster")
                .formParam("DBClusterIdentifier", "replica-test-no-cluster")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>DBClusterNotFoundFault</Code>"));
    }

    @Test
    @Order(6)
    void cleanUp() {
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", REPLICA)
                .when().post("/").then().statusCode(200);
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", SOURCE)
                .when().post("/").then().statusCode(200);
    }
}
