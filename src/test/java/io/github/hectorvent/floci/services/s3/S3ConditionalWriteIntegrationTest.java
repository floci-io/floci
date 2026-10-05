package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.Collections.frequency;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class S3ConditionalWriteIntegrationTest {
    private static final String PRECONDITION_FAILED_MESSAGE =
            "At least one of the pre-conditions you specified did not hold";
    private static final String STALE_ETAG = "\"00000000000000000000000000000000\"";

    @Test
    void putObject_ifNoneMatchStar_succeedsWhenKeyMissing() {
        String bucket = createBucket("put-if-none-missing");

        given()
            .header("If-None-Match", "*")
            .body("first")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void putObject_ifNoneMatchStar_412WhenKeyExistsAndDoesNotOverwrite() {
        String bucket = createBucket("put-if-none-existing");
        putObject(bucket, "object.txt", "first");

        ValidatableResponse response = given()
            .header("If-None-Match", "*")
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then();

        assertPreconditionFailed(response, "If-None-Match");

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void concurrentPutObject_ifNoneMatchStar_allowsOnlyOneWriter()
            throws Exception {
        String bucket = createBucket("put-if-none-concurrent");
        String key = "object.txt";
        int writers = 16;
        CyclicBarrier barrier = new CyclicBarrier(writers);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            List<Future<Integer>> futures = java.util.stream.IntStream.range(0, writers)
                    .mapToObj(writer -> executor.submit(() -> {
                        barrier.await();
                        return given()
                            .header("If-None-Match", "*")
                            .body("writer-" + writer)
                        .when()
                            .put("/" + bucket + "/" + key)
                        .then()
                            .extract()
                            .statusCode();
                    }))
                    .collect(toList());

            List<Integer> statusCodes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statusCodes.add(future.get(30, SECONDS));
            }

            assertEquals(1, frequency(statusCodes, 200));
            assertEquals(writers - 1, frequency(statusCodes, 412));
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void putObject_ifNoneMatchEtag_501AndDoesNotWrite() {
        String bucket = createBucket("put-if-none-etag");
        String eTag = putObject(bucket, "object.txt", "first");

        // S3 implements If-None-Match only as "*". A literal ETag, matching or not, is 501.
        for (String ifNoneMatch : List.of(eTag, "\"not-the-current-etag\"", "*, " + eTag, ",")) {
            assertNotImplemented(given()
                .header("If-None-Match", ifNoneMatch)
                .body("second")
            .when()
                .put("/" + bucket + "/object.txt")
            .then());
        }

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void putObject_ifMatchStar_501AndDoesNotWrite() {
        String bucket = createBucket("put-if-match-star");
        putObject(bucket, "object.txt", "first");

        for (String key : List.of("object.txt", "missing.txt")) {
            for (String ifMatch : List.of("*", "\"*\"", STALE_ETAG + ", *")) {
                assertNotImplemented(given()
                    .header("If-Match", ifMatch)
                    .body("second")
                .when()
                    .put("/" + bucket + "/" + key)
                .then());
            }
        }

        assertObjectBody(bucket, "object.txt", "first");
        given().when().get("/" + bucket + "/missing.txt").then().statusCode(404);
    }

    @Test
    void putObject_unimplementedCondition_isAnsweredBeforeTheBodyIsDecodedOrValidated() {
        String bucket = createBucket("put-if-match-star-checksum");

        assertNotImplemented(given()
            .header("If-Match", "*")
            .header("x-amz-checksum-crc32", "INVALID==")
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then());

        // A truncated aws-chunked body is 400 IncompleteBody without the header.
        assertNotImplemented(given()
            .header("If-Match", "*")
            .header("x-amz-content-sha256", "STREAMING-UNSIGNED-PAYLOAD-TRAILER")
            .header("Content-Encoding", "aws-chunked")
            .body("5\r\nhello\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then());

        given().when().get("/" + bucket + "/object.txt").then().statusCode(404);
    }

    @Test
    void putObject_ifMatch_succeedsOnMatch() {
        String bucket = createBucket("put-if-match");
        String eTag = putObject(bucket, "object.txt", "first");

        given()
            .header("If-Match", eTag)
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        assertObjectBody(bucket, "object.txt", "second");
    }

    @Test
    void putObject_ifMatch_412OnMismatch() {
        String bucket = createBucket("put-if-match-wrong");
        putObject(bucket, "object.txt", "first");

        ValidatableResponse response = given()
            .header("If-Match", "\"not-the-current-etag\"")
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then();

        assertPreconditionFailed(response, "If-Match");

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void putObject_headerValueWithAndWithoutQuotes_bothHonoured() {
        String bucket = createBucket("put-quotes");
        String eTag = putObject(bucket, "object.txt", "first");

        given()
            .header("If-Match", stripQuotes(eTag))
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        // The object changed above, so the original ETag is stale, quoted or not.
        ValidatableResponse response = given()
            .header("If-Match", stripQuotes(eTag))
            .body("third")
        .when()
            .put("/" + bucket + "/object.txt")
        .then();

        assertPreconditionFailed(response, "If-Match");

        response = given()
            .header("If-None-Match", "\"*\"")
            .body("third")
        .when()
            .put("/" + bucket + "/object.txt")
        .then();

        assertPreconditionFailed(response, "If-None-Match");

        assertObjectBody(bucket, "object.txt", "second");
    }

    @Test
    void completeMultipartUpload_ifNoneMatchStar_412WhenKeyExists() {
        String bucket = createBucket("mpu-if-none-existing");
        putObject(bucket, "object.txt", "first");
        String uploadId = initiateMultipartUpload(bucket, "object.txt");
        String partETag = uploadPart(bucket, "object.txt", uploadId, 1, "second");

        ValidatableResponse response = given()
            .contentType("application/xml")
            .header("If-None-Match", "*")
            .body(completeMultipartXml(1, partETag))
        .when()
            .post("/" + bucket + "/object.txt?uploadId=" + uploadId)
        .then();

        assertPreconditionFailed(response, "If-None-Match");

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void completeMultipartUpload_ifMatch_succeedsOnMatch() {
        String bucket = createBucket("mpu-if-match");
        String eTag = putObject(bucket, "object.txt", "first");
        String uploadId = initiateMultipartUpload(bucket, "object.txt");
        String partETag = uploadPart(bucket, "object.txt", uploadId, 1, "second");

        given()
            .contentType("application/xml")
            .header("If-Match", eTag)
            .body(completeMultipartXml(1, partETag))
        .when()
            .post("/" + bucket + "/object.txt?uploadId=" + uploadId)
        .then()
            .statusCode(200)
            .body(containsString("<CompleteMultipartUploadResult"));

        assertObjectBody(bucket, "object.txt", "second");
    }

    @Test
    void completeMultipartUpload_ifMatch_412OnMismatchAndDoesNotOverwrite() {
        String bucket = createBucket("mpu-if-match-wrong");
        putObject(bucket, "object.txt", "first");
        String uploadId = initiateMultipartUpload(bucket, "object.txt");
        uploadPart(bucket, "object.txt", uploadId, 1, "second");

        ValidatableResponse response = given()
            .contentType("application/xml")
            .header("If-Match", "\"not-the-current-etag\"")
            .body(completeMultipartXml(1))
        .when()
            .post("/" + bucket + "/object.txt?uploadId=" + uploadId)
        .then();

        assertPreconditionFailed(response, "If-Match");

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void deleteObject_ifMatch_deletesOnMatch() {
        String bucket = createBucket("delete-if-match");
        String eTag = putObject(bucket, "object.txt", "first");

        given()
            .header("If-Match", eTag)
        .when()
            .delete("/" + bucket + "/object.txt")
        .then()
            .statusCode(204);

        assertObjectMissing(bucket, "object.txt");
    }

    @Test
    void deleteObject_ifMatch_412OnMismatchAndDoesNotDelete() {
        String bucket = createBucket("delete-if-match-stale");
        putObject(bucket, "object.txt", "first");

        ValidatableResponse response = given()
            .header("If-Match", STALE_ETAG)
        .when()
            .delete("/" + bucket + "/object.txt")
        .then();

        assertPreconditionFailed(response, "If-Match");
        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void deleteObject_ifMatch_404WhenKeyMissing() {
        String bucket = createBucket("delete-if-match-missing");

        for (String ifMatch : List.of(STALE_ETAG, "*")) {
            given()
                .header("If-Match", ifMatch)
            .when()
                .delete("/" + bucket + "/missing.txt")
            .then()
                .statusCode(404)
                .body("Error.Code", equalTo("NoSuchKey"));
        }
    }

    @Test
    void deleteObject_ifMatchStar_deletesWhenKeyExists() {
        String bucket = createBucket("delete-if-match-star");
        putObject(bucket, "object.txt", "first");

        given()
            .header("If-Match", "*")
        .when()
            .delete("/" + bucket + "/object.txt")
        .then()
            .statusCode(204);

        assertObjectMissing(bucket, "object.txt");
    }

    @Test
    void deleteObject_versioned_ifMatchMismatchCreatesNoDeleteMarker() {
        String bucket = createVersionedBucket("delete-if-match-versioned");
        String eTag = putObject(bucket, "object.txt", "first");

        assertPreconditionFailed(given()
            .header("If-Match", STALE_ETAG)
        .when()
            .delete("/" + bucket + "/object.txt")
        .then(), "If-Match");
        assertObjectBody(bucket, "object.txt", "first");

        given()
            .header("If-Match", eTag)
        .when()
            .delete("/" + bucket + "/object.txt")
        .then()
            .statusCode(204)
            .header("x-amz-delete-marker", "true");
        assertObjectMissing(bucket, "object.txt");
    }

    @Test
    void deleteObject_versioned_ifMatch404WhenCurrentVersionIsDeleteMarker() {
        String bucket = createVersionedBucket("delete-if-match-marker");
        putObject(bucket, "object.txt", "first");
        given().when().delete("/" + bucket + "/object.txt").then().statusCode(204);

        given()
            .header("If-Match", "*")
        .when()
            .delete("/" + bucket + "/object.txt")
        .then()
            .statusCode(404)
            .body("Error.Code", equalTo("NoSuchKey"));
    }

    @Test
    void deleteObject_versioned_ifMatchIsEvaluatedAgainstTheCurrentVersion() {
        String bucket = createVersionedBucket("delete-if-match-version-id");
        String olderETag = putObject(bucket, "object.txt", "older");
        String olderVersionId = versionIdOf(bucket, "object.txt", 1);
        String currentETag = putObject(bucket, "object.txt", "current");

        // The addressed version's own ETag is not what the precondition checks.
        assertPreconditionFailed(given()
            .header("If-Match", olderETag)
        .when()
            .delete("/" + bucket + "/object.txt?versionId=" + olderVersionId)
        .then(), "If-Match");
        given().when().get("/" + bucket + "/object.txt?versionId=" + olderVersionId).then().statusCode(200);

        given()
            .header("If-Match", currentETag)
        .when()
            .delete("/" + bucket + "/object.txt?versionId=" + olderVersionId)
        .then()
            .statusCode(204);
        given().when().get("/" + bucket + "/object.txt?versionId=" + olderVersionId).then().statusCode(404);
        assertObjectBody(bucket, "object.txt", "current");
    }

    @Test
    void deleteObjects_perObjectETag_isAPreconditionPerEntry() {
        String bucket = createBucket("delete-objects-etag");
        String matchingETag = putObject(bucket, "matching.txt", "matching");
        putObject(bucket, "stale.txt", "stale");
        putObject(bucket, "unconditional.txt", "unconditional");

        String response = given()
            .contentType("application/xml")
            .body("""
                    <Delete>
                        <Object><Key>matching.txt</Key><ETag>%s</ETag></Object>
                        <Object><Key>stale.txt</Key><ETag>%s</ETag></Object>
                        <Object><Key>missing.txt</Key><ETag>*</ETag></Object>
                        <Object><Key>unconditional.txt</Key></Object>
                    </Delete>""".formatted(stripQuotes(matchingETag), stripQuotes(STALE_ETAG)))
        .when()
            .post("/" + bucket + "?delete")
        .then()
            .statusCode(200)
            .extract().asString();

        XmlPath xml = XmlPath.from(response);
        assertEquals(List.of("matching.txt", "unconditional.txt"), xml.getList("DeleteResult.Deleted.Key"));
        assertEquals(List.of("stale.txt", "missing.txt"), xml.getList("DeleteResult.Error.Key"));
        assertEquals(List.of("PreconditionFailed", "NoSuchKey"), xml.getList("DeleteResult.Error.Code"));
        assertObjectBody(bucket, "stale.txt", "stale");
        assertObjectMissing(bucket, "matching.txt");
        assertObjectMissing(bucket, "unconditional.txt");
    }

    @Test
    void concurrentDeleteObject_ifMatch_neverDeletesAnOverwrite() throws Exception {
        String bucket = createBucket("delete-if-match-concurrent");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int round = 0; round < 25; round++) {
                String key = "object-" + round + ".txt";
                String original = putObject(bucket, key, "original");
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Integer> delete = executor.submit(() -> {
                    barrier.await();
                    return given().header("If-Match", original)
                        .when().delete("/" + bucket + "/" + key)
                        .then().extract().statusCode();
                });
                Future<Integer> overwrite = executor.submit(() -> {
                    barrier.await();
                    return given().body("overwrite")
                        .when().put("/" + bucket + "/" + key)
                        .then().extract().statusCode();
                });
                assertEquals(200, overwrite.get(10, SECONDS));
                int deleteStatus = delete.get(10, SECONDS);
                // Delete-then-overwrite and overwrite-then-412 both leave the overwrite in place.
                // Only a check that is not atomic with the delete can remove it.
                assertEquals(true, deleteStatus == 204 || deleteStatus == 412, "delete status " + deleteStatus);
                assertObjectBody(bucket, key, "overwrite");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static String createBucket(String label) {
        String bucket = "cond-" + label + "-" + UUID.randomUUID().toString().substring(0, 8);
        given()
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);
        return bucket;
    }

    private static String createVersionedBucket(String label) {
        String bucket = createBucket(label);
        given()
            .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
        .when()
            .put("/" + bucket + "?versioning")
        .then()
            .statusCode(200);
        return bucket;
    }

    /** Version id of the {@code n}-th version written to {@code key} (1-based, oldest first). */
    private static String versionIdOf(String bucket, String key, int n) {
        List<String> ids = given()
        .when()
            .get("/" + bucket + "?versions&prefix=" + key)
        .then()
            .statusCode(200)
            .extract().xmlPath().getList("ListVersionsResult.Version.VersionId");
        return ids.get(ids.size() - n);
    }

    private static void assertObjectMissing(String bucket, String key) {
        given()
        .when()
            .get("/" + bucket + "/" + key)
        .then()
            .statusCode(404);
    }

    private static String putObject(String bucket, String key, String body) {
        return given()
            .body(body)
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200)
            .extract().header("ETag");
    }

    private static void assertObjectBody(String bucket, String key, String body) {
        given()
        .when()
            .get("/" + bucket + "/" + key)
        .then()
            .statusCode(200)
            .body(equalTo(body));
    }

    private static void assertNotImplemented(ValidatableResponse response) {
        response.statusCode(501)
                .body("Error.Code", equalTo("NotImplemented"));
    }

    private static void assertPreconditionFailed(ValidatableResponse response, String condition) {
        response.statusCode(412)
                .body("Error.Code", equalTo("PreconditionFailed"))
                .body("Error.Message", equalTo(PRECONDITION_FAILED_MESSAGE))
                .body("Error.Condition", equalTo(condition));
    }

    private static String initiateMultipartUpload(String bucket, String key) {
        return given()
            .contentType("application/octet-stream")
        .when()
            .post("/" + bucket + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
    }

    private static String uploadPart(String bucket, String key, String uploadId, int partNumber, String body) {
        return given()
            .body(body)
        .when()
            .put("/" + bucket + "/" + key + "?uploadId=" + uploadId + "&partNumber=" + partNumber)
            .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .extract().header("ETag");
    }

    private static String completeMultipartXml(int partNumber) {
        return completeMultipartXml(partNumber, "etag");
    }

    private static String completeMultipartXml(int partNumber, String eTag) {
        return """
                <CompleteMultipartUpload>
                    <Part><PartNumber>%d</PartNumber><ETag>%s</ETag></Part>
                </CompleteMultipartUpload>""".formatted(partNumber, eTag);
    }

    private static String stripQuotes(String eTag) {
        return eTag.replace("\"", "");
    }
}
