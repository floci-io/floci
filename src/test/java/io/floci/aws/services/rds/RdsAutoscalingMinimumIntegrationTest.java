package io.floci.aws.services.rds;

import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsAutoscalingMinimumIntegrationTest {
    private static final String RESULT =
            "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.";

    @ParameterizedTest
    @CsvSource({"19", "0", "-1"})
    void lowerMaximumRejectsWithoutChangingAnySetting(int maximum) {
        String id = "autoscaling-lower-" + UUID.randomUUID();
        try {
            create(id, 20);
            Response response = modify(id).formParam("MaxAllocatedStorage", maximum)
                    .formParam("BackupRetentionPeriod", 2).post("/");
            Response stored = describe(id).extract().response();
            assertAll("only equality is the disable exception",
                    () -> response.then().statusCode(400)
                            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterCombination"))
                            .body("ErrorResponse.Error.Message", equalTo("Invalid max storage size for postgres")),
                    () -> stored.then().body(RESULT + "AllocatedStorage", equalTo("20")),
                    () -> stored.then().body(RESULT + "MaxAllocatedStorage", equalTo("100")),
                    () -> stored.then().body(RESULT + "BackupRetentionPeriod", equalTo("7")));
            modify(id).formParam("MaxAllocatedStorage", 22).post("/").then().statusCode(200);
            describe(id).body(RESULT + "MaxAllocatedStorage", equalTo("22"));
        } finally {
            remove(id);
        }
    }

    @ParameterizedTest
    @CsvSource({"20,21,22,false", "21,23,24,false", "20,21,22,true"})
    void insufficientEnabledMaximumRejectsTheWholeModification(int allocated, int invalid, int valid,
                                                               boolean growAllocation) {
        String id = "autoscaling-min-" + UUID.randomUUID();
        try {
            create(id, allocated);
            RequestSpecification request = modify(id).formParam("MaxAllocatedStorage", invalid)
                    .formParam("BackupRetentionPeriod", 2);
            if (growAllocation) {
                request.formParam("AllocatedStorage", allocated + 1);
            }
            Response response = request.post("/");
            Response stored = describe(id).extract().response();
            assertAll("invalid maximum refuses before any setting or allocation changes",
                    () -> response.then().statusCode(400)
                            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterCombination"))
                            .body("ErrorResponse.Error.Message", equalTo("Invalid max storage size for postgres")),
                    () -> stored.then().body(RESULT + "MaxAllocatedStorage", equalTo("100")),
                    () -> stored.then().body(RESULT + "BackupRetentionPeriod", equalTo("7")),
                    () -> stored.then().body(RESULT + "AllocatedStorage", equalTo(Integer.toString(allocated))));
            modify(id).formParam("MaxAllocatedStorage", valid).formParam("BackupRetentionPeriod", 2)
                    .post("/").then().statusCode(200);
            describe(id).body(RESULT + "MaxAllocatedStorage", equalTo(Integer.toString(valid)))
                    .body(RESULT + "BackupRetentionPeriod", equalTo("2"))
                    .body(RESULT + "AllocatedStorage", equalTo(Integer.toString(allocated)));
        } finally {
            remove(id);
        }
    }

    @ParameterizedTest
    @CsvSource({"30,30,32,false", "30,30,32,true", "21,22,24,false"})
    void simultaneousGrowthDoesNotAddAnUnverifiedMinimum(int requested, int resolved, int maximum,
                                                         boolean immediately) {
        String id = "autoscaling-min-" + UUID.randomUUID();
        try {
            create(id, 20);
            modify(id).formParam("AllocatedStorage", requested).formParam("MaxAllocatedStorage", maximum)
                    .formParam("BackupRetentionPeriod", 2).formParam("ApplyImmediately", immediately)
                    .post("/").then().statusCode(200);
            // Floci applies storage changes immediately; this is not a live AWS combined-growth capture.
            describe(id).body(RESULT + "AllocatedStorage", equalTo(Integer.toString(resolved)))
                    .body(RESULT + "MaxAllocatedStorage", equalTo(Integer.toString(maximum)))
                    .body(RESULT + "BackupRetentionPeriod", equalTo("2"));
        } finally {
            remove(id);
        }
    }

    @Test
    void omittedMaximumRetainsTheExistingThreshold() {
        String id = "autoscaling-min-" + UUID.randomUUID();
        try {
            create(id, 20);
            modify(id).formParam("BackupRetentionPeriod", 2).post("/").then().statusCode(200);
            describe(id).body(RESULT + "MaxAllocatedStorage", equalTo("100"))
                    .body(RESULT + "BackupRetentionPeriod", equalTo("2"));
        } finally {
            remove(id);
        }
    }

    @Test
    void equalMaximumKeepsTheExistingDisableRequestAccepted() {
        String id = "autoscaling-min-" + UUID.randomUUID();
        try {
            create(id, 20);
            modify(id).formParam("MaxAllocatedStorage", 20).formParam("BackupRetentionPeriod", 2)
                    .post("/").then().statusCode(200);
            // Preserve the existing emulator representation, without claiming AWS normalization.
            describe(id).body(RESULT + "BackupRetentionPeriod", equalTo("2"))
                    .body(RESULT + "MaxAllocatedStorage", equalTo("20"))
                    .body(RESULT + "AllocatedStorage", equalTo("20"));
        } finally {
            remove(id);
        }
    }

    @Test
    void clusterMemberThresholdHandlingRemainsSeparate() {
        String id = "autoscaling-min-" + UUID.randomUUID();
        String cluster = "autoscaling-cluster-" + UUID.randomUUID();
        try {
            rds("CreateDBCluster").formParam("DBClusterIdentifier", cluster)
                    .formParam("Engine", "aurora-postgresql").formParam("MasterUsername", "adminuser")
                    .formParam("MasterUserPassword", "secret123").post("/").then().statusCode(200);
            rds("CreateDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("DBClusterIdentifier", cluster).formParam("Engine", "aurora-postgresql")
                    .formParam("DBInstanceClass", "db.t3.micro").formParam("AllocatedStorage", 20)
                    .formParam("MaxAllocatedStorage", 100).post("/").then().statusCode(200);
            describe(id).body(RESULT + "AllocatedStorage", equalTo("20"));
            modify(id).formParam("MaxAllocatedStorage", 21).post("/").then().statusCode(200);
            describe(id).body(RESULT + "MaxAllocatedStorage", equalTo("21"));
        } finally {
            assertAll("remove member and cluster even after partial setup",
                    () -> remove(id),
                    () -> {
                        Response response = rds("DeleteDBCluster").formParam("DBClusterIdentifier", cluster)
                                .formParam("SkipFinalSnapshot", true).post("/");
                        if (response.statusCode() != 404) {
                            response.then().statusCode(200);
                        }
                    });
        }
    }

    private static void create(String id, int allocated) {
        rds("CreateDBInstance").formParam("DBInstanceIdentifier", id).formParam("Engine", "postgres")
                .formParam("DBInstanceClass", "db.t3.micro").formParam("MasterUsername", "adminuser")
                .formParam("MasterUserPassword", "secret123").formParam("AllocatedStorage", allocated)
                .formParam("MaxAllocatedStorage", 100).formParam("BackupRetentionPeriod", 7)
                .post("/").then().statusCode(200);
        describe(id).body(RESULT + "AllocatedStorage", equalTo(Integer.toString(allocated)))
                .body(RESULT + "MaxAllocatedStorage", equalTo("100"))
                .body(RESULT + "BackupRetentionPeriod", equalTo("7"));
    }

    private static RequestSpecification modify(String id) {
        return rds("ModifyDBInstance").formParam("DBInstanceIdentifier", id);
    }

    private static ValidatableResponse describe(String id) {
        return rds("DescribeDBInstances").formParam("DBInstanceIdentifier", id).post("/").then().statusCode(200);
    }

    private static void remove(String id) {
        Response response = rds("DeleteDBInstance").formParam("DBInstanceIdentifier", id)
                .formParam("SkipFinalSnapshot", true).post("/");
        if (response.statusCode() != 404) {
            response.then().statusCode(200);
        }
    }
}
