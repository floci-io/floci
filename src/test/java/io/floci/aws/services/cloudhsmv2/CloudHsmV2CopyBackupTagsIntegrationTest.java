package io.floci.aws.services.cloudhsmv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
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

        ValidatableResponse copiedBackup = describeCopiedBackup(sourceBackupId)
            .body("Backups[0].TagList", hasSize(1))
            .body("Backups[0].TagList[0].Key", equalTo("team"))
            .body("Backups[0].TagList[0].Value", equalTo("destination"));
        String destinationBackupId = copiedBackup.extract().jsonPath().getString("Backups[0].BackupId");

        request("ListTags", DESTINATION_REGION)
            .body("{\"ResourceId\":\"" + destinationBackupId + "\"}")
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

        ValidatableResponse copiedBackup = describeCopiedBackup(sourceBackupId)
            .body("Backups[0].TagList", hasSize(2))
            .body("Backups[0].TagList[0].Key", equalTo("team"))
            .body("Backups[0].TagList[0].Value", equalTo("source"))
            .body("Backups[0].TagList[1].Key", equalTo("obsolete"))
            .body("Backups[0].TagList[1].Value", equalTo("old"));
        String destinationBackupId = copiedBackup.extract().jsonPath().getString("Backups[0].BackupId");

        request("UntagResource", DESTINATION_REGION)
            .body("{\"ResourceId\":\"" + destinationBackupId + "\",\"TagKeyList\":[\"team\"]}")
            .post("/")
            .then().statusCode(200);

        request("ListTags", SOURCE_REGION)
            .body("{\"ResourceId\":\"" + sourceBackupId + "\"}")
            .post("/")
            .then().statusCode(200)
            .body("TagList", hasSize(2))
            .body("TagList.Key", hasItems("team", "obsolete"));
    }

    private ValidatableResponse describeCopiedBackup(String sourceBackupId) {
        return request("DescribeBackups", DESTINATION_REGION)
            .body("{\"Filters\":{\"sourceBackupIds\":[\"" + sourceBackupId + "\"]}}")
            .post("/")
            .then().statusCode(200)
            .body("Backups.size()", equalTo(1));
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
