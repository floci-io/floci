package io.floci.aws.services.cloudhsmv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class CloudHsmV2CopyBackupTagsIntegrationTest {

    private static final String SOURCE_REGION = "us-east-1";
    private static final String DESTINATION_REGION = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void copyWithTagsUsesOnlyTheRequestedTags() {
        String sourceBackupId = createTaggedSourceBackup();

        request("CopyBackupToRegion", SOURCE_REGION)
            .body("""
                {"BackupId":"%s","DestinationRegion":"%s",
                 "TagList":[{"Key":"team","Value":"destination"}]}
                """.formatted(sourceBackupId, DESTINATION_REGION))
            .post("/")
            .then().statusCode(200);

        request("DescribeBackups", DESTINATION_REGION)
            .body("{\"Filters\":{\"sourceBackupIds\":[\"" + sourceBackupId + "\"]}}")
            .post("/")
            .then().statusCode(200)
            .body("Backups.size()", equalTo(1))
            .body("Backups[0].TagList", hasSize(1))
            .body("Backups[0].TagList[0].Key", equalTo("team"))
            .body("Backups[0].TagList[0].Value", equalTo("destination"));

        request("ListTags", DESTINATION_REGION)
            .body("{\"ResourceId\":\"" + copiedBackupId(sourceBackupId) + "\"}")
            .post("/")
            .then().statusCode(200)
            .body("TagList", hasSize(1))
            .body("TagList[0].Key", equalTo("team"))
            .body("TagList[0].Value", equalTo("destination"));
    }

    @Test
    void copyWithoutTagsInheritsSourceBackupTags() {
        String sourceBackupId = createTaggedSourceBackup();

        request("CopyBackupToRegion", SOURCE_REGION)
            .body("{\"BackupId\":\"" + sourceBackupId + "\",\"DestinationRegion\":\"" + DESTINATION_REGION + "\"}")
            .post("/")
            .then().statusCode(200);

        request("DescribeBackups", DESTINATION_REGION)
            .body("{\"Filters\":{\"sourceBackupIds\":[\"" + sourceBackupId + "\"]}}")
            .post("/")
            .then().statusCode(200)
            .body("Backups.size()", equalTo(1))
            .body("Backups[0].TagList", hasSize(2))
            .body("Backups[0].TagList[0].Key", equalTo("team"))
            .body("Backups[0].TagList[0].Value", equalTo("source"))
            .body("Backups[0].TagList[1].Key", equalTo("obsolete"))
            .body("Backups[0].TagList[1].Value", equalTo("old"));

        request("UntagResource", DESTINATION_REGION)
            .body("{\"ResourceId\":\"" + copiedBackupId(sourceBackupId) + "\",\"TagKeyList\":[\"team\"]}")
            .post("/")
            .then().statusCode(200);

        request("ListTags", SOURCE_REGION)
            .body("{\"ResourceId\":\"" + sourceBackupId + "\"}")
            .post("/")
            .then().statusCode(200)
            .body("TagList", hasSize(2))
            .body("TagList.Key", hasItems("team", "obsolete"));
    }

    @Test
    void copyRejectsExplicitlyEmptyTagList() {
        String sourceBackupId = createTaggedSourceBackup();

        request("CopyBackupToRegion", SOURCE_REGION)
            .body("{\"BackupId\":\"" + sourceBackupId + "\",\"DestinationRegion\":\"" + DESTINATION_REGION + "\",\"TagList\":[]}")
            .post("/")
            .then().statusCode(400);
    }

    @Test
    void copyRejectsInvalidTagCharacters() {
        String sourceBackupId = createTaggedSourceBackup();

        request("CopyBackupToRegion", SOURCE_REGION)
            .body("""
                {"BackupId":"%s","DestinationRegion":"%s",
                 "TagList":[{"Key":"bad!","Value":"safe"}]}
                """.formatted(sourceBackupId, DESTINATION_REGION))
            .post("/")
            .then().statusCode(400);

        request("CopyBackupToRegion", SOURCE_REGION)
            .body("""
                {"BackupId":"%s","DestinationRegion":"%s",
                 "TagList":[{"Key":"valid","Value":"bad!"}]}
                """.formatted(sourceBackupId, DESTINATION_REGION))
            .post("/")
            .then().statusCode(400);

        request("DescribeBackups", DESTINATION_REGION)
            .body("{\"Filters\":{\"sourceBackupIds\":[\"" + sourceBackupId + "\"]}}")
            .post("/")
            .then().statusCode(200)
            .body("Backups", hasSize(0));
    }

    @Test
    void copyAcceptsSupplementaryLetterWithinTagCharacterLimits() {
        String sourceBackupId = createTaggedSourceBackup();
        String letter = new String(Character.toChars(0x10400));
        String key = letter.repeat(65);
        String value = letter.repeat(129);

        request("CopyBackupToRegion", SOURCE_REGION)
            .body("""
                {"BackupId":"%s","DestinationRegion":"%s",
                 "TagList":[{"Key":"%s","Value":"%s"}]}
                """.formatted(sourceBackupId, DESTINATION_REGION, key, value))
            .post("/")
            .then().statusCode(200);

        request("DescribeBackups", DESTINATION_REGION)
            .body("{\"Filters\":{\"sourceBackupIds\":[\"" + sourceBackupId + "\"]}}")
            .post("/")
            .then().statusCode(200)
            .body("Backups[0].TagList", hasSize(1))
            .body("Backups[0].TagList[0].Key", equalTo(key))
            .body("Backups[0].TagList[0].Value", equalTo(value));
    }

    private String copiedBackupId(String sourceBackupId) {
        return request("DescribeBackups", DESTINATION_REGION)
            .body("{\"Filters\":{\"sourceBackupIds\":[\"" + sourceBackupId + "\"]}}")
            .post("/")
            .then().statusCode(200)
            .extract().jsonPath().getString("Backups[0].BackupId");
    }

    private String createTaggedSourceBackup() {
        String clusterId = request("CreateCluster", SOURCE_REGION)
            .body("{\"HsmType\":\"hsm1.medium\",\"SubnetIds\":[\"subnet-abcdef01\"]}")
            .post("/")
            .then().statusCode(200)
            .extract().jsonPath().getString("Cluster.ClusterId");

        String backupId = request("DescribeBackups", SOURCE_REGION)
            .body("{\"Filters\":{\"clusterIds\":[\"" + clusterId + "\"]}}")
            .post("/")
            .then().statusCode(200)
            .body("Backups.size()", equalTo(1))
            .extract().jsonPath().getString("Backups[0].BackupId");

        request("TagResource", SOURCE_REGION)
            .body("""
                {"ResourceId":"%s","TagList":[
                  {"Key":"team","Value":"source"},
                  {"Key":"obsolete","Value":"old"}]}
                """.formatted(backupId))
            .post("/")
            .then().statusCode(200);
        return backupId;
    }

    private RequestSpecification request(String operation, String region) {
        return given()
            .header("X-Amz-Target", "BaldrApiService." + operation)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261111/" + region + "/cloudhsm/aws4_request")
            .contentType("application/x-amz-json-1.1; charset=UTF-8");
    }
}
