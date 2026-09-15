package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * CreateDBInstance and ModifyDBInstance took the monitoring, Performance Insights, engine
 * lifecycle, log export and storage autoscaling settings and dropped them, so DescribeDBInstances
 * reported none of them and a caller saw its own configuration missing on every read.
 *
 * <p>Each member's documented default and valid values come from the RDS model.
 */
@QuarkusTest
@TestProfile(RdsInstanceMonitoringFieldsIntegrationTest.NoContainersProfile.class)
class RdsInstanceMonitoringFieldsIntegrationTest {

    public static class NoContainersProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.mock", "true");
        }
    }

    private static final String ID = "monitoring-fields-db";
    private static final String ROLE = "arn:aws:iam::000000000000:role/rds-monitoring-role";
    private static final String RESULT =
            "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.";

    private static io.restassured.specification.RequestSpecification rds(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static io.restassured.specification.RequestSpecification create() {
        return rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", ID)
                .formParam("Engine", "postgres")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("MasterUsername", "adminuser")
                .formParam("MasterUserPassword", "secret123")
                .formParam("AllocatedStorage", "20");
    }

    private static io.restassured.response.ValidatableResponse describe() {
        return rds("DescribeDBInstances").formParam("DBInstanceIdentifier", ID)
                .when().post("/").then().statusCode(200);
    }

    @AfterEach
    void cleanUp() {
        rds("DeleteDBInstance").formParam("DBInstanceIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void createDBInstance_roundTripsEveryMonitoringMember() {
        create()
                .formParam("MonitoringInterval", "30")
                .formParam("MonitoringRoleArn", ROLE)
                .formParam("EnablePerformanceInsights", "true")
                .formParam("PerformanceInsightsRetentionPeriod", "93")
                .formParam("EngineLifecycleSupport", "open-source-rds-extended-support-disabled")
                .formParam("MaxAllocatedStorage", "100")
                .formParam("EnableCloudwatchLogsExports.member.1", "postgresql")
                .formParam("EnableCloudwatchLogsExports.member.2", "upgrade")
                .when().post("/").then().statusCode(200);

        describe()
                .body(RESULT + "MonitoringInterval", equalTo("30"))
                .body(RESULT + "MonitoringRoleArn", equalTo(ROLE))
                .body(RESULT + "PerformanceInsightsEnabled", equalTo("true"))
                .body(RESULT + "PerformanceInsightsRetentionPeriod", equalTo("93"))
                .body(RESULT + "EngineLifecycleSupport",
                        equalTo("open-source-rds-extended-support-disabled"))
                .body(RESULT + "MaxAllocatedStorage", equalTo("100"))
                .body(RESULT + "EnabledCloudwatchLogsExports.member[0]", equalTo("postgresql"))
                .body(RESULT + "EnabledCloudwatchLogsExports.member[1]", equalTo("upgrade"));
    }

    @Test
    void createDBInstance_withoutTheMembers_reportsTheirDocumentedDefaults() {
        create().when().post("/").then().statusCode(200);

        describe()
                .body(RESULT + "MonitoringInterval", equalTo("0"))
                .body(RESULT + "PerformanceInsightsEnabled", equalTo("false"))
                .body(RESULT + "EngineLifecycleSupport",
                        equalTo("open-source-rds-extended-support"));

        // AWS reports neither when storage autoscaling is off and no log type is exported.
        String body = rds("DescribeDBInstances").formParam("DBInstanceIdentifier", ID)
                .when().post("/").then().extract().asString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("MaxAllocatedStorage"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("EnabledCloudwatchLogsExports"));
    }

    @Test
    void modifyDBInstance_replacesTheStoredMonitoringMembers() {
        create().formParam("MonitoringInterval", "15").when().post("/").then().statusCode(200);

        rds("ModifyDBInstance")
                .formParam("DBInstanceIdentifier", ID)
                .formParam("MonitoringInterval", "60")
                .formParam("MaxAllocatedStorage", "500")
                .when().post("/").then().statusCode(200);

        describe()
                .body(RESULT + "MonitoringInterval", equalTo("60"))
                .body(RESULT + "MaxAllocatedStorage", equalTo("500"));
    }

    @Test
    void createDBInstance_rejectsAMonitoringIntervalOutsideTheValidValues() {
        create().formParam("MonitoringInterval", "45")
                .when().post("/").then().statusCode(400)
                .body(containsString("InvalidParameterValue"))
                .body(containsString("Valid values are 0, 1, 5, 10, 15, 30, 60"));
    }

    @Test
    void createDBInstance_rejectsAPerformanceInsightsRetentionPeriodThatIsNotAllowed() {
        create().formParam("PerformanceInsightsRetentionPeriod", "94")
                .when().post("/").then().statusCode(400)
                .body(containsString("InvalidParameterValue"))
                .body(not(containsString("<DBInstance>")));
    }

    @Test
    void createDBInstance_rejectsAnUnknownEngineLifecycleSupportValue() {
        create().formParam("EngineLifecycleSupport", "open-source-rds-standard-support")
                .when().post("/").then().statusCode(400)
                .body(containsString("InvalidParameterValue"));
    }
}
