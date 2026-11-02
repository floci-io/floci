package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.RdsRequests.deleteInstance;
import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsLicenseModelIntegrationTest {

    private static RequestSpecification create(String id, String engine) {
        return rds("CreateDBInstance").formParam("DBInstanceIdentifier", id)
                .formParam("Engine", engine).formParam("DBInstanceClass", "db.t3.micro")
                .formParam("MasterUsername", "admin").formParam("MasterUserPassword", "password123")
                .formParam("AllocatedStorage", "20");
    }

    private static String described(String id) {
        Response read = rds("DescribeDBInstances").formParam("DBInstanceIdentifier", id).post("/");
        assertEquals(200, read.statusCode(), read.asString());
        return read.xmlPath().getString(
                "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.LicenseModel");
    }

    @Test
    void omittedLicenseModelReportsTheEngineDefault() {
        try {
            create("license-default-pg", "postgres").post("/").then().statusCode(200);
            create("license-default-mysql", "mysql").post("/").then().statusCode(200);
            assertEquals("postgresql-license", described("license-default-pg"));
            assertEquals("general-public-license", described("license-default-mysql"));
        } finally {
            deleteInstance("license-default-pg");
            deleteInstance("license-default-mysql");
        }
    }

    @Test
    void requestedLicenseModelIsRetainedOnCreateAndModify() {
        String id = "license-requested";
        try {
            create(id, "postgres").formParam("LicenseModel", "bring-your-own-license")
                    .post("/").then().statusCode(200);
            assertEquals("bring-your-own-license", described(id));
            rds("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("LicenseModel", "postgresql-license").post("/").then().statusCode(200);
            assertEquals("postgresql-license", described(id));
        } finally {
            deleteInstance(id);
        }
    }
}
