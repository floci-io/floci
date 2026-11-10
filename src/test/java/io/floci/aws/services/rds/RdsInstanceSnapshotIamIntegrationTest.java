package io.floci.aws.services.rds;

import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsInstanceSnapshotIamIntegrationTest {
    @ParameterizedTest
    @CsvSource({"true, , false", "true, FaLsE, false", "true, TrUe, true",
            "false, , false", "false, FaLsE, false", "false, TrUe, true"})
    void restoreUsesRequestedIamFlagOrDefaultsFalse(boolean sourceEnabled, String flag, boolean expected) {
        restore(sourceEnabled, flag, false, expected);
    }

    @ParameterizedTest
    @CsvSource({"true, yes", "true, ''", "true, not-a-boolean",
            "false, yes", "false, ''", "false, not-a-boolean"})
    void malformedIamFlagDoesNotCreateInstance(boolean sourceEnabled, String flag) {
        restore(sourceEnabled, flag, true, false);
    }

    private static void restore(boolean sourceEnabled, String flag, boolean invalid, boolean expected) {
        String source = "instance-iam-" + UUID.randomUUID();
        String snapshot = source + "-snap";
        String target = source + "-target";
        try {
            rds("CreateDBInstance").formParam("DBInstanceIdentifier", source).formParam("Engine", "postgres")
                    .formParam("DBInstanceClass", "db.t3.micro").formParam("MasterUsername", "admin")
                    .formParam("MasterUserPassword", "Password123!").formParam("AllocatedStorage", 20)
                    .formParam("EnableIAMDatabaseAuthentication", sourceEnabled).post("/").then().statusCode(200);
            rds("CreateDBSnapshot").formParam("DBInstanceIdentifier", source)
                    .formParam("DBSnapshotIdentifier", snapshot).post("/").then().statusCode(200);
            RequestSpecification request = rds("RestoreDBInstanceFromDBSnapshot")
                    .formParam("DBInstanceIdentifier", target).formParam("DBSnapshotIdentifier", snapshot);
            if (flag != null) {
                request.formParam("EnableIAMDatabaseAuthentication", flag);
            }
            if (invalid) {
                request.post("/").then().statusCode(400).body(containsString("InvalidParameterValue"));
                rds("DescribeDBInstances").formParam("DBInstanceIdentifier", target)
                        .post("/").then().statusCode(404).body(containsString("DBInstanceNotFound"));
            } else {
                request.post("/").then().statusCode(200).body(containsString(
                        "<IAMDatabaseAuthenticationEnabled>" + expected + "</IAMDatabaseAuthenticationEnabled>"));
                rds("DescribeDBInstances").formParam("DBInstanceIdentifier", target).post("/").then()
                        .statusCode(200).body(containsString("<IAMDatabaseAuthenticationEnabled>" + expected
                                + "</IAMDatabaseAuthenticationEnabled>"));
            }
            rds("DescribeDBInstances").formParam("DBInstanceIdentifier", source).post("/").then()
                    .statusCode(200).body(containsString("<IAMDatabaseAuthenticationEnabled>" + sourceEnabled
                            + "</IAMDatabaseAuthenticationEnabled>"));
        } finally {
            for (String id : new String[]{target, source}) {
                rds("DeleteDBInstance").formParam("DBInstanceIdentifier", id)
                        .formParam("SkipFinalSnapshot", true).post("/");
            }
            rds("DeleteDBSnapshot").formParam("DBSnapshotIdentifier", snapshot).post("/");
        }
    }
}
