package io.github.hectorvent.floci.services.neptune;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NeptuneInstanceSettingsIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String CLUSTER_ID = "tf-neptune-instances";
    private static final String WRITER_ID = "tf-neptune-instances-writer";
    private static final String READER_ID = "tf-neptune-instances-reader";
    private static final String WRITER_ARN = "arn:aws:neptune:us-east-1:000000000000:db:" + WRITER_ID;

    private static RequestSpecification rds(String action) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260904/us-east-1/rds"
                        + "/aws4_request, SignedHeaders=content-type;host, Signature=test")
                .contentType(FORM)
                .formParam("Action", action);
    }

    @Test
    @Order(1)
    void createEchoesEverySettingTerraformSends() {
        rds("CreateDBCluster")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("Engine", "neptune")
            .formParam("Port", "8211")
        .when().post("/")
        .then()
            .statusCode(200);

        rds("CreateDBInstance")
            .formParam("DBInstanceIdentifier", WRITER_ID)
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("DBInstanceClass", "db.r5.large")
            .formParam("Engine", "neptune")
            .formParam("AutoMinorVersionUpgrade", "true")
            .formParam("PromotionTier", "0")
            .formParam("PubliclyAccessible", "false")
            .formParam("Tags.Tag.1.Key", "Name")
            .formParam("Tags.Tag.1.Value", WRITER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBInstanceClass>db.r5.large</DBInstanceClass>"))
            .body(containsString("<Port>8211</Port>"))
            .body(containsString("<AutoMinorVersionUpgrade>true</AutoMinorVersionUpgrade>"))
            .body(containsString("<PromotionTier>0</PromotionTier>"))
            .body(containsString("<PubliclyAccessible>false</PubliclyAccessible>"))
            .body(containsString("<StorageEncrypted>false</StorageEncrypted>"))
            .body(containsString("<DBSubnetGroup><DBSubnetGroupName>default</DBSubnetGroupName>"))
            .body(containsString("<DBParameterGroupName>default.neptune1.3</DBParameterGroupName>"))
            .body(containsString("<PreferredBackupWindow>04:00-06:00</PreferredBackupWindow>"))
            .body(containsString("<PreferredMaintenanceWindow>mon:00:00-mon:03:00</PreferredMaintenanceWindow>"))
            .body(containsString("<InstanceCreateTime>"));
    }

    @Test
    @Order(2)
    void describeReadsTheSameSettingsBack() {
        rds("DescribeDBInstances")
            .formParam("DBInstanceIdentifier", WRITER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<AutoMinorVersionUpgrade>true</AutoMinorVersionUpgrade>"))
            .body(containsString("<PromotionTier>0</PromotionTier>"))
            .body(containsString("<PubliclyAccessible>false</PubliclyAccessible>"))
            .body(containsString("<StorageEncrypted>false</StorageEncrypted>"))
            .body(containsString("<DBParameterGroupName>default.neptune1.3</DBParameterGroupName>"))
            .body(containsString("<AvailabilityZone>"));
    }

    @Test
    @Order(3)
    void modifyAppliesTheChangedSettingsOnly() {
        rds("ModifyDBInstance")
            .formParam("DBInstanceIdentifier", WRITER_ID)
            .formParam("AutoMinorVersionUpgrade", "false")
            .formParam("PromotionTier", "5")
            .formParam("DBParameterGroupName", "tuned")
            .formParam("PreferredMaintenanceWindow", "Sun:05:00-Sun:06:00")
            .formParam("ApplyImmediately", "true")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<AutoMinorVersionUpgrade>false</AutoMinorVersionUpgrade>"))
            .body(containsString("<PromotionTier>5</PromotionTier>"))
            .body(containsString("<DBParameterGroupName>tuned</DBParameterGroupName>"))
            .body(containsString("<PreferredMaintenanceWindow>sun:05:00-sun:06:00</PreferredMaintenanceWindow>"))
            .body(containsString("<DBInstanceClass>db.r5.large</DBInstanceClass>"))
            .body(containsString("<PubliclyAccessible>false</PubliclyAccessible>"));
    }

    @Test
    @Order(4)
    void tagsRoundTripThroughTheInstanceArn() {
        rds("AddTagsToResource")
            .formParam("ResourceName", WRITER_ARN)
            .formParam("Tags.Tag.1.Key", "env")
            .formParam("Tags.Tag.1.Value", "test")
        .when().post("/")
        .then()
            .statusCode(200);

        rds("ListTagsForResource")
            .formParam("ResourceName", WRITER_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>Name</Key><Value>" + WRITER_ID + "</Value>"))
            .body(containsString("<Key>env</Key><Value>test</Value>"));

        rds("RemoveTagsFromResource")
            .formParam("ResourceName", WRITER_ARN)
            .formParam("TagKeys.member.1", "env")
        .when().post("/")
        .then()
            .statusCode(200);

        rds("ListTagsForResource")
            .formParam("ResourceName", WRITER_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Key>env</Key>")));
    }

    @Test
    @Order(5)
    void onlyTheFirstMemberIsTheClusterWriter() {
        rds("CreateDBInstance")
            .formParam("DBInstanceIdentifier", READER_ID)
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("DBInstanceClass", "db.r5.large")
            .formParam("Engine", "neptune")
            .formParam("PromotionTier", "2")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PromotionTier>2</PromotionTier>"));

        rds("DescribeDBClusters")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterMember><DBInstanceIdentifier>" + WRITER_ID
                    + "</DBInstanceIdentifier><IsClusterWriter>true</IsClusterWriter></DBClusterMember>"))
            .body(containsString("<DBClusterMember><DBInstanceIdentifier>" + READER_ID
                    + "</DBInstanceIdentifier><IsClusterWriter>false</IsClusterWriter></DBClusterMember>"));
    }

    @Test
    @Order(6)
    void deletingTheWriterPromotesTheNextMember() {
        rds("DeleteDBInstance")
            .formParam("DBInstanceIdentifier", WRITER_ID)
        .when().post("/")
        .then()
            .statusCode(200);

        rds("DescribeDBClusters")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterMember><DBInstanceIdentifier>" + READER_ID
                    + "</DBInstanceIdentifier><IsClusterWriter>true</IsClusterWriter></DBClusterMember>"))
            .body(not(containsString(WRITER_ID)));

        rds("DeleteDBInstance")
            .formParam("DBInstanceIdentifier", READER_ID)
        .when().post("/")
        .then()
            .statusCode(200);

        rds("DeleteDBCluster")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("SkipFinalSnapshot", "true")
        .when().post("/")
        .then()
            .statusCode(200);
    }
}
