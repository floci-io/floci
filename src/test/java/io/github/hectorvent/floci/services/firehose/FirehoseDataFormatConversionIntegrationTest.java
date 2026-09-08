package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Wire-level coverage for DataFormatConversionConfiguration: the unit tests in
 * {@link FirehoseDataFormatConversionTest} build model objects directly, so
 * this class is what exercises the JSON-to-model mapping of the nested shapes
 * and the error responses as an SDK would see them.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseDataFormatConversionIntegrationTest {

    private static final String STREAM_NAME = "test-format-conversion-stream";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-delivery-role";
    private static final String BUCKET_ARN = "arn:aws:s3:::conversion-archive";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";
    private static final String DESCRIPTION_PATH =
            "DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createAcceptsAFullConversionConfiguration() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "DeliveryStreamType": "DirectPut",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s",
                        "CompressionFormat": "UNCOMPRESSED",
                        "BufferingHints": { "SizeInMBs": 64, "IntervalInSeconds": 60 },
                        "DataFormatConversionConfiguration": {
                          "Enabled": true,
                          "SchemaConfiguration": {
                            "RoleARN": "%s",
                            "DatabaseName": "analytics",
                            "TableName": "events",
                            "Region": "us-east-1"
                          },
                          "InputFormatConfiguration": { "Deserializer": { "OpenXJsonSerDe": {} } },
                          "OutputFormatConfiguration": {
                            "Serializer": { "ParquetSerDe": { "Compression": "GZIP", "WriterVersion": "V2" } }
                          }
                        }
                      }
                    }
                    """.formatted(STREAM_NAME, ROLE_ARN, BUCKET_ARN, ROLE_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamARN", notNullValue());
    }

    /**
     * The description echoes the configuration as given: no CatalogId or VersionId
     * materialized, the empty OpenXJsonSerDe kept, and the conversion member absent
     * from the plain S3DestinationDescription mirror, which does not have it in
     * AWS's model.
     */
    @Test
    @Order(2)
    void describeEchoesTheConversionConfigurationVerbatim() {
        String conversionPath = DESCRIPTION_PATH + ".DataFormatConversionConfiguration";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(conversionPath + ".Enabled", equalTo(true))
            .body(conversionPath + ".SchemaConfiguration.RoleARN", equalTo(ROLE_ARN))
            .body(conversionPath + ".SchemaConfiguration.DatabaseName", equalTo("analytics"))
            .body(conversionPath + ".SchemaConfiguration.TableName", equalTo("events"))
            .body(conversionPath + ".SchemaConfiguration.Region", equalTo("us-east-1"))
            .body(conversionPath + ".SchemaConfiguration.CatalogId", nullValue())
            .body(conversionPath + ".SchemaConfiguration.VersionId", nullValue())
            .body(conversionPath + ".InputFormatConfiguration.Deserializer.OpenXJsonSerDe", notNullValue())
            .body(conversionPath + ".OutputFormatConfiguration.Serializer.ParquetSerDe.Compression", equalTo("GZIP"))
            .body(conversionPath + ".OutputFormatConfiguration.Serializer.ParquetSerDe.WriterVersion", equalTo("V2"))
            .body(conversionPath + ".OutputFormatConfiguration.Serializer.ParquetSerDe.BlockSizeBytes", nullValue())
            .body("DeliveryStreamDescription.Destinations[0].S3DestinationDescription"
                    + ".DataFormatConversionConfiguration", nullValue());
    }

    @Test
    @Order(3)
    void updateCarryingOnlyEnabledFalseKeepsTheStoredMembers() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "UpdateDestination")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "CurrentDeliveryStreamVersionId": "1",
                      "DestinationId": "destinationId-000000000001",
                      "ExtendedS3DestinationUpdate": {
                        "DataFormatConversionConfiguration": { "Enabled": false }
                      }
                    }
                    """.formatted(STREAM_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String conversionPath = DESCRIPTION_PATH + ".DataFormatConversionConfiguration";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.VersionId", equalTo("2"))
            .body(conversionPath + ".Enabled", equalTo(false))
            .body(conversionPath + ".SchemaConfiguration.DatabaseName", equalTo("analytics"))
            .body(conversionPath + ".OutputFormatConfiguration.Serializer.ParquetSerDe", notNullValue());
    }

    /**
     * The legacy shape does not model the member, and AWS's JSON protocol ignores a
     * member a shape does not model, so the request succeeds with it disregarded
     * rather than stored. The SDKs cannot send it here at all.
     */
    @Test
    @Order(6)
    void legacyS3DestinationShapeIgnoresTheConversionMember() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "legacy-shape-conversion-stream",
                      "S3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s",
                        "CompressionFormat": "UNCOMPRESSED",
                        "DataFormatConversionConfiguration": {
                          "Enabled": true,
                          "SchemaConfiguration": { "RoleARN": "%s", "DatabaseName": "db", "TableName": "t" },
                          "InputFormatConfiguration": { "Deserializer": { "OpenXJsonSerDe": {} } },
                          "OutputFormatConfiguration": { "Serializer": { "ParquetSerDe": {} } }
                        }
                      }
                    }
                    """.formatted(ROLE_ARN, BUCKET_ARN, ROLE_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"legacy-shape-conversion-stream\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(DESCRIPTION_PATH + ".DataFormatConversionConfiguration", nullValue());
    }

    @Test
    @Order(4)
    void createRejectsCompressedDestinationsWithTheAwsMessage() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "rejected-conversion-stream",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s",
                        "CompressionFormat": "GZIP",
                        "DataFormatConversionConfiguration": {
                          "Enabled": true,
                          "SchemaConfiguration": { "RoleARN": "%s", "DatabaseName": "db", "TableName": "t" },
                          "InputFormatConfiguration": { "Deserializer": { "OpenXJsonSerDe": {} } },
                          "OutputFormatConfiguration": { "Serializer": { "ParquetSerDe": {} } }
                        }
                      }
                    }
                    """.formatted(ROLE_ARN, BUCKET_ARN, ROLE_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"))
            .body("message", equalTo("The S3 destination's compression format must be set to UNCOMPRESSED"
                    + " when data format conversion is enabled. To enable compression within the converted"
                    + " output format, set the appropriate serialization option in the output format configuration."));
    }

    @Test
    @Order(5)
    void createRejectsParquetSerDeBoundsAtTheSmithyLayer() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "rejected-bounds-stream",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s",
                        "DataFormatConversionConfiguration": {
                          "OutputFormatConfiguration": { "Serializer": { "ParquetSerDe": { "PageSizeBytes": 1 } } }
                        }
                      }
                    }
                    """.formatted(ROLE_ARN, BUCKET_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value at"
                    + " 'extendedS3DestinationConfiguration.dataFormatConversionConfiguration"
                    + ".outputFormatConfiguration.serializer.parquetSerDe.pageSizeBytes'"
                    + " failed to satisfy constraint: Member must have value greater than or equal to 65536"));
    }
}
