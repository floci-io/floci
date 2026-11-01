package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsLicenseModelIntegrationTest {

    private static RequestSpecification query(String action) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20261101/"
                + "us-east-1/rds/aws4_request, SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC).formParam("Action", action).formParam("Version", "2014-10-31");
    }

    private static RequestSpecification create(String id, String engine) {
        return query("CreateDBInstance").formParam("DBInstanceIdentifier", id)
                .formParam("Engine", engine).formParam("DBInstanceClass", "db.t3.micro")
                .formParam("MasterUsername", "admin").formParam("MasterUserPassword", "password123")
                .formParam("AllocatedStorage", "20");
    }

    private static String described(String id) {
        Response read = query("DescribeDBInstances").formParam("DBInstanceIdentifier", id).post("/");
        assertEquals(200, read.statusCode(), read.asString());
        return read.xmlPath().getString(
                "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.LicenseModel");
    }

    private static void delete(String id) {
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", id)
                .formParam("SkipFinalSnapshot", "true").post("/");
    }

    @Test
    void omittedLicenseModelReportsTheEngineDefault() {
        try {
            create("license-default-pg", "postgres").post("/").then().statusCode(200);
            create("license-default-mysql", "mysql").post("/").then().statusCode(200);
            assertEquals("postgresql-license", described("license-default-pg"));
            assertEquals("general-public-license", described("license-default-mysql"));
        } finally {
            delete("license-default-pg");
            delete("license-default-mysql");
        }
    }

    @Test
    void requestedLicenseModelIsRetainedOnCreateAndModify() {
        String id = "license-requested";
        try {
            create(id, "postgres").formParam("LicenseModel", "bring-your-own-license")
                    .post("/").then().statusCode(200);
            assertEquals("bring-your-own-license", described(id));
            query("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("LicenseModel", "postgresql-license").post("/").then().statusCode(200);
            assertEquals("postgresql-license", described(id));
        } finally {
            delete(id);
        }
    }
}
