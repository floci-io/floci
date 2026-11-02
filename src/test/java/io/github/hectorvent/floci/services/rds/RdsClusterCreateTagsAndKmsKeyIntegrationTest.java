package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * The Tags and KmsKeyId of a CreateDBCluster request are kept, so ListTagsForResource and DescribeDBClusters return
 * them. Without that a caller that reads them back, such as the Terraform AWS provider, sees an empty
 * {@code tags_all} and a missing {@code kms_key_id} right after the create, and plans to replace the cluster.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterCreateTagsAndKmsKeyIntegrationTest {

    private static final String ID = "create-options-cluster";
    private static final String KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";

    @AfterEach
    void cleanUp() {
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void tagsGivenAtCreateAreReadableThroughListTagsForResource() {
        String arn = createCluster()
                .formParam("Tags.Tag.1.Key", "Accounting").formParam("Tags.Tag.1.Value", "gna")
                .formParam("Tags.Tag.2.Key", "BusinessSolution").formParam("Tags.Tag.2.Value", "gue")
                .when().post("/").then().statusCode(200)
                .extract().path("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.DBClusterArn");

        rds("ListTagsForResource").formParam("ResourceName", arn)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Key>Accounting</Key>"))
                .body(containsString("<Value>gna</Value>"))
                .body(containsString("<Key>BusinessSolution</Key>"))
                .body(containsString("<Value>gue</Value>"));
    }

    @Test
    void tagsAreAlsoReturnedAsTheTagListOfDescribeDbClusters() {
        // The Terraform AWS provider reads a cluster's tags from here, not from ListTagsForResource.
        createCluster()
                .formParam("Tags.Tag.1.Key", "Accounting").formParam("Tags.Tag.1.Value", "gna")
                .when().post("/").then().statusCode(200)
                .body(containsString("<TagList><Tag><Key>Accounting</Key><Value>gna</Value></Tag></TagList>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<TagList><Tag><Key>Accounting</Key><Value>gna</Value></Tag></TagList>"));
    }

    @Test
    void tagsAreAlsoReadInTheMemberForm() {
        String arn = createCluster()
                .formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", "platform")
                .when().post("/").then().statusCode(200)
                .extract().path("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.DBClusterArn");

        rds("ListTagsForResource").formParam("ResourceName", arn)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Key>team</Key>"))
                .body(containsString("<Value>platform</Value>"));
    }

    @Test
    void kmsKeyIdRoundTripsFromCreateToDescribe() {
        createCluster()
                .formParam("StorageEncrypted", "true").formParam("KmsKeyId", KEY_ARN)
                .when().post("/").then().statusCode(200)
                .body(containsString("<KmsKeyId>" + KEY_ARN + "</KmsKeyId>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"))
                .body(containsString("<KmsKeyId>" + KEY_ARN + "</KmsKeyId>"));
    }

    @Test
    void aClusterCreatedWithoutThemHasNoTagsAndNoKmsKeyId() {
        String arn = createCluster().when().post("/").then().statusCode(200)
                .body(not(containsString("<KmsKeyId>")))
                .extract().path("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.DBClusterArn");

        rds("ListTagsForResource").formParam("ResourceName", arn)
                .when().post("/").then().statusCode(200)
                .body(not(containsString("<Key>")));
        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(not(containsString("<KmsKeyId>")));
    }

    private static RequestSpecification createCluster() {
        return rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password");
    }
}
