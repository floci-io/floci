package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class S3ConditionalCopyIntegrationTest {
    private static final String STALE_ETAG = "\"00000000000000000000000000000000\"";
    private static final String LONG_AGO = "Sat, 01 Jan 2000 00:00:00 GMT";
    // IMF-fixdate, two-digit day: RFC_1123_DATE_TIME writes "Sat, 3 Oct", which the S3 date parser rejects.
    private static final String FAR_FUTURE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .format(ZonedDateTime.now(ZoneOffset.UTC).plusDays(1));

    // --- destination preconditions (If-Match / If-None-Match) ---

    @Test
    void copyObject_ifNoneMatchStar_412WhenDestinationExistsAndDoesNotOverwrite() {
        String bucket = createBucket("copy-dest-if-none");
        putObject(bucket, "src.txt", "source");
        putObject(bucket, "dst.txt", "destination");

        copy(bucket, "src.txt", "dst.txt").header("If-None-Match", "*")
        .when().put("/" + bucket + "/dst.txt")
        .then().statusCode(412)
            .body("Error.Code", equalTo("PreconditionFailed"))
            .body("Error.Condition", equalTo("If-None-Match"));

        assertObjectBody(bucket, "dst.txt", "destination");
    }

    @Test
    void copyObject_ifNoneMatchStar_copiesWhenDestinationMissing() {
        String bucket = createBucket("copy-dest-if-none-missing");
        putObject(bucket, "src.txt", "source");

        copy(bucket, "src.txt", "dst.txt").header("If-None-Match", "*")
        .when().put("/" + bucket + "/dst.txt")
        .then().statusCode(200);

        assertObjectBody(bucket, "dst.txt", "source");
    }

    @Test
    void copyObject_ifMatch_412OnStaleDestinationAndDoesNotOverwrite() {
        String bucket = createBucket("copy-dest-if-match");
        putObject(bucket, "src.txt", "source");
        String destinationETag = putObject(bucket, "dst.txt", "destination");

        copy(bucket, "src.txt", "dst.txt").header("If-Match", STALE_ETAG)
        .when().put("/" + bucket + "/dst.txt")
        .then().statusCode(412)
            .body("Error.Code", equalTo("PreconditionFailed"))
            .body("Error.Condition", equalTo("If-Match"));
        assertObjectBody(bucket, "dst.txt", "destination");

        copy(bucket, "src.txt", "dst.txt").header("If-Match", destinationETag)
        .when().put("/" + bucket + "/dst.txt")
        .then().statusCode(200);
        assertObjectBody(bucket, "dst.txt", "source");
    }

    @Test
    void copyObject_ifMatch_404WhenDestinationMissing() {
        String bucket = createBucket("copy-dest-if-match-missing");
        putObject(bucket, "src.txt", "source");

        copy(bucket, "src.txt", "dst.txt").header("If-Match", STALE_ETAG)
        .when().put("/" + bucket + "/dst.txt")
        .then().statusCode(404).body("Error.Code", equalTo("NoSuchKey"));

        given().when().get("/" + bucket + "/dst.txt").then().statusCode(404);
    }

    @Test
    void copyObject_selfCopyHonoursDestinationIfMatch() {
        String bucket = createBucket("copy-self-if-match");
        String eTag = putObject(bucket, "obj.txt", "body");

        copy(bucket, "obj.txt", "obj.txt")
            .header("x-amz-metadata-directive", "REPLACE")
            .header("If-Match", STALE_ETAG)
        .when().put("/" + bucket + "/obj.txt")
        .then().statusCode(412);

        copy(bucket, "obj.txt", "obj.txt")
            .header("x-amz-metadata-directive", "REPLACE")
            .header("If-Match", eTag)
        .when().put("/" + bucket + "/obj.txt")
        .then().statusCode(200);
    }

    // --- copy-source preconditions (x-amz-copy-source-if-*) ---

    @Test
    void copyObject_copySourceIfMatch() {
        String bucket = createBucket("copy-src-if-match");
        String sourceETag = putObject(bucket, "src.txt", "source");

        assertCopySource(bucket, "x-amz-copy-source-if-match", STALE_ETAG, 412);
        assertCopySource(bucket, "x-amz-copy-source-if-match", sourceETag, 200);
    }

    @Test
    void copyObject_copySourceIfNoneMatch() {
        String bucket = createBucket("copy-src-if-none-match");
        String sourceETag = putObject(bucket, "src.txt", "source");

        assertCopySource(bucket, "x-amz-copy-source-if-none-match", sourceETag, 412);
        assertCopySource(bucket, "x-amz-copy-source-if-none-match", STALE_ETAG, 200);
    }

    @Test
    void copyObject_copySourceIfModifiedSince() {
        String bucket = createBucket("copy-src-if-modified");
        putObject(bucket, "src.txt", "source");
        String lastModified = lastModified(bucket, "src.txt");

        assertCopySource(bucket, "x-amz-copy-source-if-modified-since", FAR_FUTURE, 412);
        assertCopySource(bucket, "x-amz-copy-source-if-modified-since", LONG_AGO, 200);
        // Last-Modified has second precision, so the source has not been modified since itself.
        assertCopySource(bucket, "x-amz-copy-source-if-modified-since", lastModified, 412);
    }

    @Test
    void copyObject_copySourceIfUnmodifiedSince() {
        String bucket = createBucket("copy-src-if-unmodified");
        putObject(bucket, "src.txt", "source");
        String lastModified = lastModified(bucket, "src.txt");

        assertCopySource(bucket, "x-amz-copy-source-if-unmodified-since", LONG_AGO, 412);
        assertCopySource(bucket, "x-amz-copy-source-if-unmodified-since", FAR_FUTURE, 200);
        assertCopySource(bucket, "x-amz-copy-source-if-unmodified-since", lastModified, 200);
    }

    @Test
    void copyObject_copySourceIfMatchTrueOverridesIfUnmodifiedSinceFalse() {
        String bucket = createBucket("copy-src-combo-match");
        String sourceETag = putObject(bucket, "src.txt", "source");

        // Alone, the date condition fails; paired with a matching if-match it is not consulted.
        assertCopySource(bucket, "x-amz-copy-source-if-unmodified-since", LONG_AGO, 412);
        copy(bucket, "src.txt", "dst.txt")
            .header("x-amz-copy-source-if-match", sourceETag)
            .header("x-amz-copy-source-if-unmodified-since", LONG_AGO)
        .when().put("/" + bucket + "/dst.txt")
        .then().statusCode(200);
    }

    @Test
    void copyObject_copySourceIfNoneMatchFalseFailsDespiteIfModifiedSinceTrue() {
        String bucket = createBucket("copy-src-combo-none");
        String sourceETag = putObject(bucket, "src.txt", "source");

        // Alone, the date condition passes; a failing if-none-match still fails the pair.
        assertCopySource(bucket, "x-amz-copy-source-if-modified-since", LONG_AGO, 200);
        copy(bucket, "src.txt", "dst.txt")
            .header("x-amz-copy-source-if-none-match", sourceETag)
            .header("x-amz-copy-source-if-modified-since", LONG_AGO)
        .when().put("/" + bucket + "/dst.txt")
        .then().statusCode(412);

        given().when().get("/" + bucket + "/dst.txt").then().statusCode(404);
    }

    @Test
    void uploadPartCopy_rejectedCopyLeavesExistingPartUnchanged() {
        String bucket = createBucket("upload-part-copy-keep");
        putObject(bucket, "src.txt", "source");
        String uploadId = given()
        .when()
            .post("/" + bucket + "/dst.txt?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        String partETag = given().body("original")
        .when().put("/" + bucket + "/dst.txt?partNumber=1&uploadId=" + uploadId)
        .then().statusCode(200).extract().header("ETag");

        copy(bucket, "src.txt", "dst.txt").header("x-amz-copy-source-if-match", STALE_ETAG)
        .when().put("/" + bucket + "/dst.txt?partNumber=1&uploadId=" + uploadId)
        .then().statusCode(412);

        given().body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + partETag
                + "</ETag></Part></CompleteMultipartUpload>")
        .when().post("/" + bucket + "/dst.txt?uploadId=" + uploadId)
        .then().statusCode(200);
        assertObjectBody(bucket, "dst.txt", "original");
    }

    @Test
    void uploadPartCopy_copySourceIfMatch() {
        String bucket = createBucket("upload-part-copy-src");
        String sourceETag = putObject(bucket, "src.txt", "source");
        String uploadId = given()
        .when()
            .post("/" + bucket + "/dst.txt?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        copy(bucket, "src.txt", "dst.txt").header("x-amz-copy-source-if-match", STALE_ETAG)
        .when().put("/" + bucket + "/dst.txt?partNumber=1&uploadId=" + uploadId)
        .then().statusCode(412).body("Error.Code", equalTo("PreconditionFailed"));

        copy(bucket, "src.txt", "dst.txt").header("x-amz-copy-source-if-match", sourceETag)
        .when().put("/" + bucket + "/dst.txt?partNumber=1&uploadId=" + uploadId)
        .then().statusCode(200);
    }

    private static void assertCopySource(String bucket, String header, String value, int status) {
        String destination = "dst-" + UUID.randomUUID() + ".txt";
        copy(bucket, "src.txt", destination).header(header, value)
        .when().put("/" + bucket + "/" + destination)
        .then().statusCode(status);
        given().when().get("/" + bucket + "/" + destination).then().statusCode(status == 200 ? 200 : 404);
    }

    private static RequestSpecification copy(String bucket, String sourceKey, String destinationKey) {
        return given().header("x-amz-copy-source", "/" + bucket + "/" + sourceKey);
    }

    private static String createBucket(String label) {
        String bucket = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        given().when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static String putObject(String bucket, String key, String body) {
        return given().body(body).when().put("/" + bucket + "/" + key)
                .then().statusCode(200).extract().header("ETag");
    }

    private static String lastModified(String bucket, String key) {
        return given().when().head("/" + bucket + "/" + key)
                .then().statusCode(200).extract().header("Last-Modified");
    }

    private static void assertObjectBody(String bucket, String key, String body) {
        given().when().get("/" + bucket + "/" + key).then().statusCode(200).body(equalTo(body));
    }
}
