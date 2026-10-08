package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The RDS catalogue reads over the Query protocol: engine versions with their families, defaults
 * and upgrade targets, the engine default parameter calls, and the storage an instance can move to.
 */
@QuarkusTest
class RdsCatalogueReadIntegrationTest {

    private static final String VERSIONS =
            "DescribeDBEngineVersionsResponse.DescribeDBEngineVersionsResult.DBEngineVersions.DBEngineVersion";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261006/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    void describeDbEngineVersionsReportsFamiliesDefaultsAndUpgradeTargets() {
        query("DescribeDBEngineVersions")
                .formParam("Engine", "postgres")
                .formParam("EngineVersion", "16.3")
        .when().post("/").then()
                .statusCode(200)
                .body(VERSIONS + ".size()", equalTo(1))
                .body(VERSIONS + ".DBParameterGroupFamily", equalTo("postgres16"))
                .body(VERSIONS + ".MajorEngineVersion", equalTo("16"))
                .body(VERSIONS + ".DBEngineDescription", equalTo("PostgreSQL"))
                .body(VERSIONS + ".Status", equalTo("available"))
                .body(VERSIONS + ".SupportedEngineModes.member", equalTo("provisioned"))
                .body(VERSIONS + ".ValidUpgradeTarget.UpgradeTarget.size()", equalTo(4))
                .body(VERSIONS + ".ValidUpgradeTarget.UpgradeTarget[0].EngineVersion", equalTo("16.14"))
                .body(VERSIONS + ".ValidUpgradeTarget.UpgradeTarget[0].IsMajorVersionUpgrade", equalTo("false"))
                .body(VERSIONS + ".ValidUpgradeTarget.UpgradeTarget[1].IsMajorVersionUpgrade", equalTo("true"));

        // A major version names every version in it; DefaultOnly then keeps that major's default.
        query("DescribeDBEngineVersions").formParam("Engine", "postgres").formParam("EngineVersion", "18")
        .when().post("/").then().statusCode(200).body(VERSIONS + ".size()", equalTo(2));
        query("DescribeDBEngineVersions").formParam("Engine", "postgres").formParam("EngineVersion", "18")
                .formParam("DefaultOnly", "true")
        .when().post("/").then().statusCode(200)
                .body(VERSIONS + ".EngineVersion", equalTo("18.4"));
        // Without a version, DefaultOnly gives the version Floci creates when none is named.
        query("DescribeDBEngineVersions").formParam("Engine", "aurora-mysql").formParam("DefaultOnly", "true")
        .when().post("/").then().statusCode(200)
                .body(VERSIONS + ".EngineVersion", equalTo("8.0.mysql_aurora.3.05.2"))
                .body(VERSIONS + ".DBParameterGroupFamily", equalTo("aurora-mysql8.0"));

        query("DescribeDBEngineVersions")
                .formParam("Filters.Filter.1.Name", "db-parameter-group-family")
                .formParam("Filters.Filter.1.Values.Value.1", "mysql8.4")
        .when().post("/").then().statusCode(200)
                .body(VERSIONS + ".EngineVersion", equalTo("8.4.4"));
        query("DescribeDBEngineVersions").formParam("Engine", "oracle-ee")
        .when().post("/").then().statusCode(200)
                .body(not(containsString("<DBEngineVersion>")));
        query("DescribeDBEngineVersions").formParam("MaxRecords", "20")
        .when().post("/").then().statusCode(200)
                .body(VERSIONS + ".size()", equalTo(19))
                .body(not(containsString("<Marker>")));
    }

    @Test
    void orderableOptionsComeFromTheSameCatalogue() {
        query("DescribeOrderableDBInstanceOptions").formParam("Engine", "mysql")
        .when().post("/").then().statusCode(200)
                .body(containsString("<EngineVersion>8.0.36</EngineVersion>"));
        // The versions the list used to name still find their rows.
        query("DescribeOrderableDBInstanceOptions").formParam("Engine", "mysql").formParam("EngineVersion", "8.0")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DBInstanceClass>db.t3.micro</DBInstanceClass>"));
        query("DescribeOrderableDBInstanceOptions").formParam("Engine", "mariadb").formParam("EngineVersion", "11")
        .when().post("/").then().statusCode(200)
                .body(containsString("<EngineVersion>11.2</EngineVersion>"));
    }

    @Test
    void engineDefaultParametersCheckTheFamily() {
        query("DescribeEngineDefaultParameters").formParam("DBParameterGroupFamily", "postgres16")
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<DescribeEngineDefaultParametersResult><EngineDefaults>"
                        + "<DBParameterGroupFamily>postgres16</DBParameterGroupFamily><Parameters></Parameters>"));
        query("DescribeEngineDefaultClusterParameters").formParam("DBParameterGroupFamily", "aurora-postgresql16")
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<DescribeEngineDefaultClusterParametersResult><EngineDefaults>"));
        query("DescribeEngineDefaultParameters").formParam("DBParameterGroupFamily", "postgres9")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        query("DescribeEngineDefaultParameters")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void validModificationsGiveTheStorageRangeFromTheCurrentAllocation() {
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", "catalogue-db")
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", "40")
        .when().post("/").then().statusCode(200);

        query("DescribeValidDBInstanceModifications").formParam("DBInstanceIdentifier", "catalogue-db")
        .when().post("/").then()
                .statusCode(200)
                // 40 GiB stays as it is, and any increase stores at least 10% more (44 GiB).
                .body(containsString("<ValidStorageOptions><StorageType>gp2</StorageType><StorageSize>"
                        + "<Range><From>40</From><To>40</To><Step>1</Step></Range>"
                        + "<Range><From>44</From><To>65536</To><Step>1</Step></Range></StorageSize>"
                        + "<SupportsStorageAutoscaling>true</SupportsStorageAutoscaling></ValidStorageOptions>"));
        query("DescribeValidDBInstanceModifications").formParam("DBInstanceIdentifier", "catalogue-missing")
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBInstanceNotFound</Code>"));

        query("DeleteDBInstance").formParam("DBInstanceIdentifier", "catalogue-db")
        .when().post("/").then().statusCode(200);
    }
}
