package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * ModifyDBInstance read DBInstanceClass, AllocatedStorage and EngineVersion from the request and
 * threw them away, so a resize or an engine upgrade returned 200 while DescribeDBInstances kept
 * reporting the values the instance was created with. A caller that diffs its configuration
 * against a read-back, Terraform above all, saw the same pending change on every run.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsInstanceScalingIntegrationTest {

    private static final String ID = "scaling-db";
    private static final String RESULT =
            "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.";

    private static RequestSpecification rds(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static void create() {
        rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", ID)
                .formParam("Engine", "postgres")
                .formParam("EngineVersion", "13")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("MasterUsername", "adminuser")
                .formParam("MasterUserPassword", "secret123")
                .formParam("AllocatedStorage", "20")
                .when().post("/").then().statusCode(200);
    }

    private static RequestSpecification modify() {
        return rds("ModifyDBInstance").formParam("DBInstanceIdentifier", ID);
    }

    private static ValidatableResponse describe() {
        return rds("DescribeDBInstances").formParam("DBInstanceIdentifier", ID)
                .when().post("/").then().statusCode(200);
    }

    @AfterEach
    void cleanUp() {
        rds("DeleteDBInstance").formParam("DBInstanceIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void modifyDBInstance_roundTripsTheNewClassStorageAndEngineVersion() {
        create();

        modify()
                .formParam("DBInstanceClass", "db.t3.large")
                .formParam("AllocatedStorage", "100")
                .formParam("EngineVersion", "13.7")
                .when().post("/").then().statusCode(200)
                .body(containsString("<DBInstanceClass>db.t3.large</DBInstanceClass>"))
                .body(containsString("<AllocatedStorage>100</AllocatedStorage>"))
                .body(containsString("<EngineVersion>13.7</EngineVersion>"));

        describe()
                .body(RESULT + "DBInstanceClass", equalTo("db.t3.large"))
                .body(RESULT + "AllocatedStorage", equalTo("100"))
                .body(RESULT + "EngineVersion", equalTo("13.7"));
    }

    @Test
    void modifyDBInstance_roundsAnIncreaseUnderTenPercentUpTheWayAwsDoes() {
        create();

        modify().formParam("AllocatedStorage", "21")
                .when().post("/").then().statusCode(200);

        describe().body(RESULT + "AllocatedStorage", equalTo("22"));
    }

    @Test
    void modifyDBInstance_refusesToShrinkAllocatedStorage() {
        create();

        modify().formParam("AllocatedStorage", "10")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"));

        describe().body(RESULT + "AllocatedStorage", equalTo("20"));
    }

    @Test
    void modifyDBInstance_refusesAMajorEngineVersionUpgradeWithoutTheFlag() {
        create();

        modify().formParam("EngineVersion", "14")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"));

        describe().body(RESULT + "EngineVersion", equalTo("13"));
    }

    @Test
    void modifyDBInstance_appliesNothingWhenOneMemberIsRefused() {
        create();

        modify()
                .formParam("DBInstanceClass", "db.t3.large")
                .formParam("AllocatedStorage", "10")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"));

        describe()
                .body(RESULT + "DBInstanceClass", equalTo("db.t3.micro"))
                .body(RESULT + "AllocatedStorage", equalTo("20"));
    }

    @Test
    void modifyDBInstance_upgradesAMajorEngineVersionWhenTheFlagIsPresent() {
        create();

        modify().formParam("EngineVersion", "14")
                .formParam("AllowMajorVersionUpgrade", "true")
                .when().post("/").then().statusCode(200);

        describe().body(RESULT + "EngineVersion", equalTo("14"));
    }
}
