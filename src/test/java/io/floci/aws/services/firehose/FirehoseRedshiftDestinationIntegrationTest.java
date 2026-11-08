package io.floci.aws.services.firehose;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-level coverage of the Redshift destination shape on CreateDeliveryStream and UpdateDestination. */
@QuarkusTest
class FirehoseRedshiftDestinationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";
    private static final String JDBC_URL = "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev";
    private static final String DESTINATION_ID = "destinationId-000000000001";

    private static final String RS_CONFIG = """
            {
              "DeliveryStreamName": "%s",
              "RedshiftDestinationConfiguration": {
                "RoleARN": "arn:aws:iam::000000000000:role/firehose",
                "ClusterJDBCURL": "%s",
                "Username": "admin", "Password": "Secret123",
                "CopyCommand": {"DataTableName": "events", "CopyOptions": "json 'auto'"},
                "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::%s"}
              }
            }
            """;

    @Inject
    FirehoseService firehoseService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse post(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .body(body)
                .when()
                .post("/")
                .then();
    }

    private static String createBody(String name, String bucket) {
        return RS_CONFIG.formatted(name, JDBC_URL, bucket);
    }

    private static void assertStreamAbsent(String name) {
        post("DescribeDeliveryStream", "{\"DeliveryStreamName\": \"" + name + "\"}")
                .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    /** A rejected update must leave the stored destination and its version exactly as they were. */
    private static void assertUnchanged(String name, String version, String table) {
        post("DescribeDeliveryStream", "{\"DeliveryStreamName\": \"" + name + "\"}")
                .statusCode(200)
                .body("DeliveryStreamDescription.VersionId", equalTo(version))
                .body("DeliveryStreamDescription.Destinations[0].RedshiftDestinationDescription"
                        + ".CopyCommand.DataTableName", equalTo(table))
                .body("DeliveryStreamDescription.Destinations[0].RedshiftDestinationDescription"
                        + ".S3BackupMode", nullValue());
    }

    @Test
    void createAndDescribeNeverReturnsThePassword() {
        post("CreateDeliveryStream", createBody("rs-describe", "rs-describe-stage")).statusCode(200);

        post("DescribeDeliveryStream", "{\"DeliveryStreamName\": \"rs-describe\"}")
                .statusCode(200)
                .body("DeliveryStreamDescription.Destinations[0].RedshiftDestinationDescription.ClusterJDBCURL",
                        equalTo(JDBC_URL))
                .body("DeliveryStreamDescription.Destinations[0].RedshiftDestinationDescription.Username",
                        equalTo("admin"))
                .body("DeliveryStreamDescription.Destinations[0].RedshiftDestinationDescription.Password",
                        nullValue())
                .body("DeliveryStreamDescription.Destinations[0].RedshiftDestinationDescription"
                        + ".S3DestinationDescription.BucketARN", equalTo("arn:aws:s3:::rs-describe-stage"))
                .body("DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription", nullValue());
    }

    @Test
    void describeDoesNotStripThePasswordFromTheStoredStream() {
        post("CreateDeliveryStream", createBody("rs-keeps-password", "rs-keeps-password-stage")).statusCode(200);

        post("DescribeDeliveryStream", "{\"DeliveryStreamName\": \"rs-keeps-password\"}").statusCode(200);

        String stored = firehoseService.describeDeliveryStream("rs-keeps-password")
                .getDestinations().get(0).getRedshiftDestinationDescription().getPassword();
        assertEquals("Secret123", stored);
    }

    @Test
    void createRejectsTwoDestinationConfigurations() {
        String body = createBody("rs-two", "rs-two-stage")
                .replace("\"RedshiftDestinationConfiguration\"",
                        "\"ExtendedS3DestinationConfiguration\": {\"RoleARN\": \"arn:aws:iam::000000000000:role/r\","
                                + " \"BucketARN\": \"arn:aws:s3:::b\"}, \"RedshiftDestinationConfiguration\"");

        post("CreateDeliveryStream", body).statusCode(400).body("__type", equalTo("InvalidArgumentException"));
        assertStreamAbsent("rs-two");
    }

    @Test
    void createRejectsAJdbcUrlThatDoesNotMatchTheAwsPattern() {
        String body = RS_CONFIG.formatted("rs-bad-url", "jdbc:postgresql://localhost:5432/dev", "rs-bad-url-stage");

        post("CreateDeliveryStream", body).statusCode(400)
                .body("message", containsString("clusterJDBCURL"));
        assertStreamAbsent("rs-bad-url");
    }

    @Test
    void createRejectsAnIntermediateConfigurationWithoutABucketOrRole() {
        String body = createBody("rs-no-bucket", "unused")
                .replace("\"S3Configuration\": {\"RoleARN\": \"arn:aws:iam::000000000000:role/firehose\","
                        + " \"BucketARN\": \"arn:aws:s3:::unused\"}", "\"S3Configuration\": {}");

        post("CreateDeliveryStream", body).statusCode(400)
                .body("message", containsString("s3Configuration.bucketARN"))
                .body("message", containsString("s3Configuration.roleARN"));
        assertStreamAbsent("rs-no-bucket");
    }

    @Test
    void createRejectsABackupConfigurationWithoutABucket() {
        String body = createBody("rs-backup-no-bucket", "rs-backup-no-bucket-stage")
                .replace("\"Username\": \"admin\"", "\"S3BackupMode\": \"Enabled\","
                        + " \"S3BackupConfiguration\": {\"RoleARN\": \"arn:aws:iam::000000000000:role/firehose\"},"
                        + " \"Username\": \"admin\"");

        post("CreateDeliveryStream", body).statusCode(400)
                .body("message", containsString("s3BackupConfiguration.bucketARN"));
        assertStreamAbsent("rs-backup-no-bucket");
    }

    @Test
    void createRejectsOnlyHalfOfTheBackupBufferingHints() {
        String body = createBody("rs-backup-hints", "rs-backup-hints-stage")
                .replace("\"Username\": \"admin\"", "\"S3BackupMode\": \"Enabled\","
                        + " \"S3BackupConfiguration\": {\"RoleARN\": \"arn:aws:iam::000000000000:role/firehose\","
                        + " \"BucketARN\": \"arn:aws:s3:::rs-backup-hints-copy\","
                        + " \"BufferingHints\": {\"SizeInMBs\": 5}}, \"Username\": \"admin\"");

        post("CreateDeliveryStream", body).statusCode(400).body("__type", equalTo("InvalidArgumentException"));
        assertStreamAbsent("rs-backup-hints");
    }

    @Test
    void createRejectsAMalformedSecretsManagerRole() {
        String body = createBody("rs-secret-role", "rs-secret-role-stage")
                .replace("\"Username\": \"admin\"", "\"SecretsManagerConfiguration\": {\"Enabled\": false,"
                        + " \"RoleARN\": \"not-an-arn\"}, \"Username\": \"admin\"");

        post("CreateDeliveryStream", body).statusCode(400)
                .body("message", containsString("secretsManagerConfiguration.roleARN"));
        assertStreamAbsent("rs-secret-role");
    }

    @Test
    void updateAfterDeleteDoesNotBringTheStreamBack() {
        post("CreateDeliveryStream", createBody("rs-deleted", "rs-deleted-stage")).statusCode(200);
        post("DeleteDeliveryStream", "{\"DeliveryStreamName\": \"rs-deleted\"}").statusCode(200);

        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-deleted", "CurrentDeliveryStreamVersionId": "1",
                 "DestinationId": "%s",
                 "RedshiftDestinationUpdate": {"CopyCommand": {"DataTableName": "events2"}}}
                """.formatted(DESTINATION_ID)).statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        assertStreamAbsent("rs-deleted");
    }

    @Test
    void updateDestinationChangesTheCopyCommandAndBumpsTheVersion() {
        post("CreateDeliveryStream", createBody("rs-update", "rs-update-stage")).statusCode(200);

        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-update", "CurrentDeliveryStreamVersionId": "1",
                 "DestinationId": "%s",
                 "RedshiftDestinationUpdate": {"CopyCommand": {"DataTableName": "events2"}}}
                """.formatted(DESTINATION_ID)).statusCode(200);

        post("DescribeDeliveryStream", "{\"DeliveryStreamName\": \"rs-update\"}")
                .body("DeliveryStreamDescription.VersionId", equalTo("2"))
                .body("DeliveryStreamDescription.Destinations[0].RedshiftDestinationDescription"
                        + ".CopyCommand.DataTableName", equalTo("events2"));
    }

    @Test
    void s3UpdateOnARedshiftStreamIsRejected() {
        post("CreateDeliveryStream", createBody("rs-wrong-update", "rs-wrong-update-stage")).statusCode(200);

        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-wrong-update", "CurrentDeliveryStreamVersionId": "1",
                 "DestinationId": "%s",
                 "ExtendedS3DestinationUpdate": {"BucketARN": "arn:aws:s3:::other"}}
                """.formatted(DESTINATION_ID)).statusCode(400).body("__type", equalTo("InvalidArgumentException"));
        assertUnchanged("rs-wrong-update", "1", "events");
    }

    @Test
    void backupCanBeEnabledButNotDisabledAgain() {
        post("CreateDeliveryStream", createBody("rs-backup", "rs-backup-stage")).statusCode(200);

        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-backup", "CurrentDeliveryStreamVersionId": "1",
                 "DestinationId": "%s",
                 "RedshiftDestinationUpdate": {"S3BackupMode": "Enabled",
                   "S3BackupUpdate": {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                                      "BucketARN": "arn:aws:s3:::rs-backup-copy"}}}
                """.formatted(DESTINATION_ID)).statusCode(200);

        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-backup", "CurrentDeliveryStreamVersionId": "2",
                 "DestinationId": "%s",
                 "RedshiftDestinationUpdate": {"S3BackupMode": "Disabled"}}
                """.formatted(DESTINATION_ID)).statusCode(400);
    }

    @Test
    void flushWithAMissingClusterKeepsTheStagedObject() {
        post("CreateDeliveryStream", createBody("rs-flush", "rs-flush-stage")).statusCode(200);
        String data = Base64.getEncoder().encodeToString("{\"id\":1}".getBytes(StandardCharsets.UTF_8));
        post("PutRecord", "{\"DeliveryStreamName\": \"rs-flush\", \"Record\": {\"Data\": \"" + data + "\"}}")
                .statusCode(200);

        firehoseService.flush("rs-flush");

        String listing = given().when().get("/rs-flush-stage").then().statusCode(200).extract().asString();
        assertTrue(listing.contains("<Key>"),
                "expected a staged object in rs-flush-stage, got: " + listing);
    }

    @Test
    void partialBackupUpdateKeepsTheBackupBucket() {
        post("CreateDeliveryStream", createBody("rs-backup-merge", "rs-backup-merge-stage")).statusCode(200);
        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-backup-merge", "CurrentDeliveryStreamVersionId": "1",
                 "DestinationId": "%s",
                 "RedshiftDestinationUpdate": {"S3BackupMode": "Enabled",
                   "S3BackupUpdate": {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                                      "BucketARN": "arn:aws:s3:::rs-backup-merge-copy"}}}
                """.formatted(DESTINATION_ID)).statusCode(200);

        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-backup-merge", "CurrentDeliveryStreamVersionId": "2",
                 "DestinationId": "%s",
                 "RedshiftDestinationUpdate": {"S3BackupUpdate": {"Prefix": "bk/"}}}
                """.formatted(DESTINATION_ID)).statusCode(200);

        post("DescribeDeliveryStream", "{\"DeliveryStreamName\": \"rs-backup-merge\"}")
                .body("DeliveryStreamDescription.Destinations[0].RedshiftDestinationDescription"
                        + ".S3BackupDescription.BucketARN", equalTo("arn:aws:s3:::rs-backup-merge-copy"))
                .body("DeliveryStreamDescription.Destinations[0].RedshiftDestinationDescription"
                        + ".S3BackupDescription.Prefix", equalTo("bk/"));
    }

    @Test
    void updateCopyCommandWithoutATableNameIsRejected() {
        post("CreateDeliveryStream", createBody("rs-copy-no-table", "rs-copy-no-table-stage")).statusCode(200);

        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-copy-no-table", "CurrentDeliveryStreamVersionId": "1",
                 "DestinationId": "%s",
                 "RedshiftDestinationUpdate": {"CopyCommand": {"CopyOptions": "CSV"}}}
                """.formatted(DESTINATION_ID)).statusCode(400);
        assertUnchanged("rs-copy-no-table", "1", "events");
    }

    @Test
    void updateRejectsANewBackupWithoutABucketAndKeepsTheStream() {
        post("CreateDeliveryStream", createBody("rs-new-backup", "rs-new-backup-stage")).statusCode(200);

        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-new-backup", "CurrentDeliveryStreamVersionId": "1",
                 "DestinationId": "%s",
                 "RedshiftDestinationUpdate": {"S3BackupMode": "Enabled",
                   "S3BackupUpdate": {"Prefix": "bk/"}}}
                """.formatted(DESTINATION_ID)).statusCode(400)
                .body("message", containsString("s3BackupUpdate.bucketARN"));
        assertUnchanged("rs-new-backup", "1", "events");
    }

    @Test
    void updateRejectsOnlyHalfOfTheBackupBufferingHints() {
        post("CreateDeliveryStream", createBody("rs-upd-hints", "rs-upd-hints-stage")).statusCode(200);

        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-upd-hints", "CurrentDeliveryStreamVersionId": "1",
                 "DestinationId": "%s",
                 "RedshiftDestinationUpdate": {"S3BackupMode": "Enabled",
                   "S3BackupUpdate": {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                                      "BucketARN": "arn:aws:s3:::rs-upd-hints-copy",
                                      "BufferingHints": {"IntervalInSeconds": 60}}}}
                """.formatted(DESTINATION_ID)).statusCode(400).body("__type", equalTo("InvalidArgumentException"));
        assertUnchanged("rs-upd-hints", "1", "events");
    }

    @Test
    void explicitNullRedshiftConfigurationIsTreatedAsAbsent() {
        post("CreateDeliveryStream", """
                {"DeliveryStreamName": "rs-null-config", "RedshiftDestinationConfiguration": null}
                """).statusCode(200);
    }

    @Test
    void dataFormatConversionInTheIntermediateS3ConfigurationIsIgnored() {
        String body = createBody("rs-conversion", "rs-conversion-stage")
                .replace("\"BucketARN\": \"arn:aws:s3:::rs-conversion-stage\"",
                        "\"BucketARN\": \"arn:aws:s3:::rs-conversion-stage\","
                                + " \"DataFormatConversionConfiguration\": {\"Enabled\": true}");
        post("CreateDeliveryStream", body).statusCode(200);

        post("DescribeDeliveryStream", "{\"DeliveryStreamName\": \"rs-conversion\"}")
                .body("DeliveryStreamDescription.Destinations[0].RedshiftDestinationDescription"
                        + ".S3DestinationDescription.DataFormatConversionConfiguration", nullValue());
    }

    @Test
    void createRejectsBackupEnabledWithoutABackupConfiguration() {
        String body = createBody("rs-backup-missing", "rs-backup-missing-stage")
                .replace("\"Username\": \"admin\"", "\"S3BackupMode\": \"Enabled\", \"Username\": \"admin\"");

        post("CreateDeliveryStream", body).statusCode(400);
    }

    @Test
    void updateRejectsBackupEnabledWithoutABackupConfiguration() {
        post("CreateDeliveryStream", createBody("rs-backup-missing-upd", "rs-backup-missing-upd-stage")).statusCode(200);

        post("UpdateDestination", """
                {"DeliveryStreamName": "rs-backup-missing-upd", "CurrentDeliveryStreamVersionId": "1",
                 "DestinationId": "%s",
                 "RedshiftDestinationUpdate": {"S3BackupMode": "Enabled"}}
                """.formatted(DESTINATION_ID)).statusCode(400);
    }

    @Test
    void createRejectsAnIntermediatePrefixTheCopyEmulationCannotRead() {
        String body = createBody("rs-bad-prefix", "rs-bad-prefix-stage")
                .replace("\"BucketARN\": \"arn:aws:s3:::rs-bad-prefix-stage\"",
                        "\"BucketARN\": \"arn:aws:s3:::rs-bad-prefix-stage\", \"Prefix\": \"my prefix/\"");

        post("CreateDeliveryStream", body).statusCode(400);
    }
}
