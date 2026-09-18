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
 * CopyDBParameterGroup, CopyDBClusterParameterGroup, CopyOptionGroup, ResetDBParameterGroup and
 * ResetDBClusterParameterGroup: a copy carries the source's family (or engine and major version),
 * overrides and options into a group that must not exist yet; a reset drops overrides so the
 * group answers with engine defaults again.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsParameterGroupCopyResetIntegrationTest {

    private static final String SOURCE_PG = "copy-test-source-pg";
    private static final String TARGET_PG = "copy-test-target-pg";
    private static final String SOURCE_PG_ARN = "arn:aws:rds:us-east-1:000000000000:pg:" + SOURCE_PG;
    private static final String SOURCE_CLUSTER_PG = "copy-test-source-cluster-pg";
    private static final String TARGET_CLUSTER_PG = "copy-test-target-cluster-pg";
    private static final String FROM_DEFAULT_CLUSTER_PG = "copy-test-from-default-cluster-pg";
    private static final String SOURCE_OG = "copy-test-source-og";
    private static final String TARGET_OG = "copy-test-target-og";

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
    void copyDbParameterGroupCarriesFamilyOverridesAndTags() {
        query("CreateDBParameterGroup")
                .formParam("DBParameterGroupName", SOURCE_PG)
                .formParam("DBParameterGroupFamily", "postgres15")
                .formParam("Description", "source")
        .when().post("/").then().statusCode(200);
        query("ModifyDBParameterGroup")
                .formParam("DBParameterGroupName", SOURCE_PG)
                .formParam("Parameters.member.1.ParameterName", "max_connections")
                .formParam("Parameters.member.1.ParameterValue", "250")
                .formParam("Parameters.member.2.ParameterName", "log_min_duration_statement")
                .formParam("Parameters.member.2.ParameterValue", "500")
        .when().post("/").then().statusCode(200);

        // The source may be named by ARN, as the API reference allows.
        query("CopyDBParameterGroup")
                .formParam("SourceDBParameterGroupIdentifier", SOURCE_PG_ARN)
                .formParam("TargetDBParameterGroupIdentifier", TARGET_PG)
                .formParam("TargetDBParameterGroupDescription", "copied")
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "prod")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CopyDBParameterGroupResult>"))
            .body(containsString("<DBParameterGroupName>" + TARGET_PG + "</DBParameterGroupName>"))
            .body(containsString("<DBParameterGroupFamily>postgres15</DBParameterGroupFamily>"))
            .body(containsString("<Description>copied</Description>"))
            .body(containsString(":pg:" + TARGET_PG + "</DBParameterGroupArn>"));

        query("DescribeDBParameters")
                .formParam("DBParameterGroupName", TARGET_PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>max_connections</ParameterName>"))
            .body(containsString("<ParameterValue>250</ParameterValue>"))
            .body(containsString("<ParameterName>log_min_duration_statement</ParameterName>"));

        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:pg:" + TARGET_PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>env</Key>"))
            .body(containsString("<Value>prod</Value>"));
    }

    @Test
    @Order(2)
    void copyIsIndependentOfItsSourceAndRefusesAnExistingTarget() {
        query("ModifyDBParameterGroup")
                .formParam("DBParameterGroupName", SOURCE_PG)
                .formParam("Parameters.member.1.ParameterName", "max_connections")
                .formParam("Parameters.member.1.ParameterValue", "999")
        .when().post("/").then().statusCode(200);

        query("DescribeDBParameters")
                .formParam("DBParameterGroupName", TARGET_PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterValue>250</ParameterValue>"))
            .body(not(containsString("<ParameterValue>999</ParameterValue>")));

        query("CopyDBParameterGroup")
                .formParam("SourceDBParameterGroupIdentifier", SOURCE_PG)
                .formParam("TargetDBParameterGroupIdentifier", TARGET_PG)
                .formParam("TargetDBParameterGroupDescription", "again")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>DBParameterGroupAlreadyExists</Code>"));

        query("CopyDBParameterGroup")
                .formParam("SourceDBParameterGroupIdentifier", "no-such-group")
                .formParam("TargetDBParameterGroupIdentifier", "copy-test-never-created")
                .formParam("TargetDBParameterGroupDescription", "x")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>DBParameterGroupNotFound</Code>"));

        query("CopyDBParameterGroup")
                .formParam("SourceDBParameterGroupIdentifier", SOURCE_PG)
                .formParam("TargetDBParameterGroupIdentifier", "copy-test-never-created")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("TargetDBParameterGroupDescription"));

        query("CopyDBParameterGroup")
                .formParam("SourceDBParameterGroupIdentifier", "default.postgres15")
                .formParam("TargetDBParameterGroupIdentifier", "copy-test-never-created")
                .formParam("TargetDBParameterGroupDescription", "x")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"))
            .body(containsString("is not a valid identifier"));
    }

    @Test
    @Order(3)
    void resetDbParameterGroupDropsNamedOverridesOrAllOfThem() {
        // Named the way `aws rds reset-db-parameter-group --parameters ...` puts it on the wire.
        query("ResetDBParameterGroup")
                .formParam("DBParameterGroupName", TARGET_PG)
                .formParam("Parameters.Parameter.1.ParameterName", "max_connections")
                .formParam("Parameters.Parameter.1.ApplyMethod", "immediate")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ResetDBParameterGroupResult>"))
            .body(containsString("<DBParameterGroupName>" + TARGET_PG + "</DBParameterGroupName>"));

        query("DescribeDBParameters")
                .formParam("DBParameterGroupName", TARGET_PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<ParameterName>max_connections</ParameterName>")))
            .body(containsString("<ParameterName>log_min_duration_statement</ParameterName>"));

        query("ResetDBParameterGroup")
                .formParam("DBParameterGroupName", TARGET_PG)
                .formParam("ResetAllParameters", "true")
        .when().post("/")
        .then()
            .statusCode(200);

        query("DescribeDBParameters")
                .formParam("DBParameterGroupName", TARGET_PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<ParameterName>")));

        query("ResetDBParameterGroup")
                .formParam("DBParameterGroupName", TARGET_PG)
                .formParam("ResetAllParameters", "true")
                .formParam("Parameters.Parameter.1.ParameterName", "max_connections")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterCombination</Code>"));

        query("ResetDBParameterGroup")
                .formParam("DBParameterGroupName", "no-such-group")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>DBParameterGroupNotFound</Code>"));
    }

    @Test
    @Order(4)
    void clusterParameterGroupCopyAndResetBehaveTheSameWay() {
        query("CreateDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", SOURCE_CLUSTER_PG)
                .formParam("DBParameterGroupFamily", "aurora-postgresql15")
                .formParam("Description", "source")
        .when().post("/").then().statusCode(200);
        query("ModifyDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", SOURCE_CLUSTER_PG)
                .formParam("Parameters.member.1.ParameterName", "rds.force_ssl")
                .formParam("Parameters.member.1.ParameterValue", "1")
        .when().post("/").then().statusCode(200);

        query("CopyDBClusterParameterGroup")
                .formParam("SourceDBClusterParameterGroupIdentifier", SOURCE_CLUSTER_PG)
                .formParam("TargetDBClusterParameterGroupIdentifier", TARGET_CLUSTER_PG)
                .formParam("TargetDBClusterParameterGroupDescription", "copied")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CopyDBClusterParameterGroupResult>"))
            .body(containsString("<DBClusterParameterGroupName>" + TARGET_CLUSTER_PG + "</DBClusterParameterGroupName>"))
            .body(containsString("<DBParameterGroupFamily>aurora-postgresql15</DBParameterGroupFamily>"))
            .body(containsString(":cluster-pg:" + TARGET_CLUSTER_PG + "</DBClusterParameterGroupArn>"));

        query("DescribeDBClusterParameters")
                .formParam("DBClusterParameterGroupName", TARGET_CLUSTER_PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>rds.force_ssl</ParameterName>"));

        // A managed default group cannot be copied. AWS refuses it through the identifier rule,
        // since default group names carry periods; the reference says to create a custom group
        // for the family instead.
        query("CopyDBClusterParameterGroup")
                .formParam("SourceDBClusterParameterGroupIdentifier", "default.aurora-postgresql15")
                .formParam("TargetDBClusterParameterGroupIdentifier", FROM_DEFAULT_CLUSTER_PG)
                .formParam("TargetDBClusterParameterGroupDescription", "from default")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"))
            .body(containsString("is not a valid identifier"));
        query("DescribeDBClusterParameterGroups")
                .formParam("DBClusterParameterGroupName", FROM_DEFAULT_CLUSTER_PG)
        .when().post("/")
        .then()
            .statusCode(404);

        query("ResetDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", TARGET_CLUSTER_PG)
                .formParam("ResetAllParameters", "true")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ResetDBClusterParameterGroupResult>"))
            .body(containsString("<DBClusterParameterGroupName>" + TARGET_CLUSTER_PG + "</DBClusterParameterGroupName>"));

        query("DescribeDBClusterParameters")
                .formParam("DBClusterParameterGroupName", TARGET_CLUSTER_PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<ParameterName>rds.force_ssl</ParameterName>")));

        query("ResetDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", "default.aurora-postgresql15")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidDBParameterGroupState</Code>"));
    }

    @Test
    @Order(5)
    void copyOptionGroupCarriesEngineVersionAndOptions() {
        query("CreateOptionGroup")
                .formParam("OptionGroupName", SOURCE_OG)
                .formParam("EngineName", "mysql")
                .formParam("MajorEngineVersion", "8.0")
                .formParam("OptionGroupDescription", "source")
        .when().post("/").then().statusCode(200);
        query("ModifyOptionGroup")
                .formParam("OptionGroupName", SOURCE_OG)
                .formParam("OptionsToInclude.member.1.OptionName", "MARIADB_AUDIT_PLUGIN")
                .formParam("OptionsToInclude.member.1.OptionSettings.member.1.Name", "SERVER_AUDIT_EVENTS")
                .formParam("OptionsToInclude.member.1.OptionSettings.member.1.Value", "CONNECT")
                .formParam("ApplyImmediately", "true")
        .when().post("/").then().statusCode(200);

        query("CopyOptionGroup")
                .formParam("SourceOptionGroupIdentifier", SOURCE_OG)
                .formParam("TargetOptionGroupIdentifier", TARGET_OG)
                .formParam("TargetOptionGroupDescription", "copied")
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "prod")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CopyOptionGroupResult>"))
            .body(containsString("<OptionGroupName>" + TARGET_OG + "</OptionGroupName>"))
            .body(containsString("<EngineName>mysql</EngineName>"))
            .body(containsString("<MajorEngineVersion>8.0</MajorEngineVersion>"))
            .body(containsString("<OptionGroupDescription>copied</OptionGroupDescription>"))
            .body(containsString("<OptionName>MARIADB_AUDIT_PLUGIN</OptionName>"))
            .body(containsString("<Value>CONNECT</Value>"));

        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:og:" + TARGET_OG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>env</Key>"));

        query("CopyOptionGroup")
                .formParam("SourceOptionGroupIdentifier", SOURCE_OG)
                .formParam("TargetOptionGroupIdentifier", TARGET_OG)
                .formParam("TargetOptionGroupDescription", "again")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>OptionGroupAlreadyExistsFault</Code>"));

        query("CopyOptionGroup")
                .formParam("SourceOptionGroupIdentifier", "no-such-option-group")
                .formParam("TargetOptionGroupIdentifier", "copy-test-never-created-og")
                .formParam("TargetOptionGroupDescription", "x")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>OptionGroupNotFoundFault</Code>"));
    }

    @Test
    @Order(9)
    void cleanUp() {
        for (String name : new String[] {SOURCE_PG, TARGET_PG}) {
            query("DeleteDBParameterGroup").formParam("DBParameterGroupName", name).when().post("/");
        }
        for (String name : new String[] {SOURCE_CLUSTER_PG, TARGET_CLUSTER_PG, FROM_DEFAULT_CLUSTER_PG}) {
            query("DeleteDBClusterParameterGroup").formParam("DBClusterParameterGroupName", name).when().post("/");
        }
        for (String name : new String[] {SOURCE_OG, TARGET_OG}) {
            query("DeleteOptionGroup").formParam("OptionGroupName", name).when().post("/");
        }
    }
}
