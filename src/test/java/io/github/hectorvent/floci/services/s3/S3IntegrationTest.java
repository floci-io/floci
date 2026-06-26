package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.restassured.config.DecoderConfig;
import io.restassured.config.RestAssuredConfig;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3IntegrationTest {
    private static final String SSE_CUSTOMER_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String SSE_CUSTOMER_KEY_MD5 = customerKeyMd5(SSE_CUSTOMER_KEY);
    private static final String WRONG_SSE_CUSTOMER_KEY = Base64.getEncoder().encodeToString("abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
    private static final String WRONG_SSE_CUSTOMER_KEY_MD5 = customerKeyMd5(WRONG_SSE_CUSTOMER_KEY);
    private static final String SHORT_SSE_CUSTOMER_KEY = Base64.getEncoder().encodeToString("short-key".getBytes(StandardCharsets.UTF_8));
    private static final String SHORT_SSE_CUSTOMER_KEY_MD5 = customerKeyMd5(SHORT_SSE_CUSTOMER_KEY);

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/test-bucket")
        .then()
            .statusCode(200)
            .header("Location", equalTo("/test-bucket"));
    }

    @Test
    @Order(2)
    void createDuplicateBucketInUsEast1IsIdempotent() {
        given()
        .when()
            .put("/test-bucket")
        .then()
            .statusCode(200)
            .header("Location", equalTo("/test-bucket"));
    }

    @Test
    @Order(3)
    void listBuckets() {
        given()
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("test-bucket"));
    }

    @Test
    @Order(4)
    void putObject() {
        given()
            .contentType("text/plain")
            .header("x-amz-meta-owner", "team-a")
            .header("x-amz-storage-class", "STANDARD_IA")
            .body("Hello World from S3!")
        .when()
            .put("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
    }

    @Test
    @Order(5)
    void getObject() {
        given()
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("Content-Length", notNullValue())
            .header("x-amz-meta-owner", equalTo("team-a"))
            .header("x-amz-storage-class", equalTo("STANDARD_IA"))
            .header("x-amz-checksum-crc64nvme", notNullValue())
            .body(equalTo("Hello World from S3!"));
    }

    @Test
    @Order(6)
    void getObjectAttributes() {
        given()
            .header("x-amz-object-attributes", "ETag,ObjectSize,StorageClass,Checksum")
        .when()
            .get("/test-bucket/greeting.txt?attributes")
        .then()
            .statusCode(200)
            .body(containsString("<GetObjectAttributesResponse"))
            .body(containsString("<StorageClass>STANDARD_IA</StorageClass>"))
            .body(containsString("<ObjectSize>20</ObjectSize>"))
            .body(containsString("<ChecksumCRC64NVME>"));
    }

    @Test
    @Order(7)
    void headObject() {
        given()
        .when()
            .head("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("Content-Length", notNullValue())
            .header("x-amz-meta-owner", equalTo("team-a"))
            .header("x-amz-storage-class", equalTo("STANDARD_IA"))
            .header("x-amz-checksum-crc64nvme", notNullValue());
    }

    @Test
    @Order(8)
    void getObjectNotFound() {
        given()
        .when()
            .get("/test-bucket/nonexistent.txt")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchKey"));
    }

    @Test
    @Order(9)
    void putAnotherObject() {
        given()
            .contentType("application/json")
            .body("{\"key\": \"value\"}")
        .when()
            .put("/test-bucket/data/config.json")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(10)
    void listObjects() {
        given()
        .when()
            .get("/test-bucket")
        .then()
            .statusCode(200)
            .body(containsString("greeting.txt"))
            .body(containsString("data/config.json"));
    }

    @Test
    @Order(11)
    void listObjectsWithPrefix() {
        given()
            .queryParam("prefix", "data/")
        .when()
            .get("/test-bucket")
        .then()
            .statusCode(200)
            .body(containsString("data/config.json"))
            .body(not(containsString("greeting.txt")));
    }

    @Test
    @Order(12)
    void listObjectsWithDelimiterReturnsCommonPrefixes() {
        given()
            .queryParam("delimiter", "/")
            .queryParam("list-type", "2")
        .when()
            .get("/test-bucket")
        .then()
            .statusCode(200)
            .body(containsString("<CommonPrefixes>"))
            .body(containsString("<Prefix>data/</Prefix>"))
            .body(containsString("<Key>greeting.txt</Key>"))
            .body(containsString("<KeyCount>2</KeyCount>"))
            .body(containsString("<IsTruncated>false</IsTruncated>"));
    }

    @Test
    @Order(13)
    void copyObject() {
        given()
            .header("x-amz-copy-source", "/test-bucket/greeting.txt")
            .header("x-amz-metadata-directive", "REPLACE")
            .header("x-amz-meta-owner", "team-b")
            .header("x-amz-storage-class", "GLACIER")
            .contentType("application/json")
        .when()
            .put("/test-bucket/greeting-copy.txt")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        // Verify the copy
        given()
        .when()
            .get("/test-bucket/greeting-copy.txt")
        .then()
            .statusCode(200)
            .header("x-amz-meta-owner", equalTo("team-b"))
            .header("x-amz-storage-class", equalTo("GLACIER"))
            .body(equalTo("Hello World from S3!"));
    }

    @Test
    @Order(14)
    void deleteObject() {
        given()
        .when()
            .delete("/test-bucket/greeting-copy.txt")
        .then()
            .statusCode(204);

        // Verify it's gone
        given()
        .when()
            .get("/test-bucket/greeting-copy.txt")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(15)
    void deleteNonEmptyBucketFails() {
        given()
        .when()
            .delete("/test-bucket")
        .then()
            .statusCode(409)
            .body(containsString("BucketNotEmpty"));
    }

    @Test
    @Order(15)
    void getObjectAttributesRejectsUnknownSelector() {
        given()
            .header("x-amz-object-attributes", "ETag,UnknownThing")
        .when()
            .get("/test-bucket/greeting.txt?attributes")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));
    }

    @Test
    @Order(16)
    void getNonExistentBucket() {
        given()
        .when()
            .get("/nonexistent-bucket")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    @Test
    @Order(17)
    void headBucketReturnsStoredRegionForLocationConstraintBucket() {
        String bucket = "eu-head-bucket";
        String createBucketConfiguration = """
                <CreateBucketConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <LocationConstraint>eu-central-1</LocationConstraint>
                </CreateBucketConfiguration>
                """;

        given()
            .contentType("application/xml")
            .body(createBucketConfiguration)
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200)
            .header("Location", equalTo("/" + bucket));

        given()
        .when()
            .head("/" + bucket)
        .then()
            .statusCode(200)
            .header("x-amz-bucket-region", equalTo("eu-central-1"));

        given()
        .when()
            .delete("/" + bucket)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(18)
    void createBucketUsesSigningRegionWhenBodyEmpty() {
        String bucket = "signed-region-bucket";

        given()
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260325/eu-west-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=test")
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200)
            .header("Location", equalTo("/" + bucket));

        given()
        .when()
            .head("/" + bucket)
        .then()
            .statusCode(200)
            .header("x-amz-bucket-region", equalTo("eu-west-1"));

        given()
        .when()
            .delete("/" + bucket)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(19)
    void createBucketRejectsUsEast1LocationConstraint() {
        String createBucketConfiguration = """
                <CreateBucketConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <LocationConstraint>us-east-1</LocationConstraint>
                </CreateBucketConfiguration>
                """;

        given()
            .contentType("application/xml")
            .body(createBucketConfiguration)
        .when()
            .put("/invalid-location-bucket")
        .then()
            .statusCode(400)
            .body(containsString("InvalidLocationConstraint"));
    }

    @Test
    @Order(20)
    void copyObjectWithNonAsciiKeySucceeds() {
        String bucket = "copy-nonascii-bucket";
        String srcKey = "src/テスト画像.png";
        String dstKey = "dst/テスト画像.png";
        String encodedSrcKey = "src/%E3%83%86%E3%82%B9%E3%83%88%E7%94%BB%E5%83%8F.png";

        given().put("/" + bucket).then().statusCode(200);

        given()
            .contentType("application/octet-stream")
            .body("hello".getBytes())
        .when()
            .put("/" + bucket + "/" + srcKey)
        .then()
            .statusCode(200);

        given()
            .header("x-amz-copy-source", "/" + bucket + "/" + encodedSrcKey)
        .when()
            .put("/" + bucket + "/" + dstKey)
        .then()
            .statusCode(200)
            .body(containsString("ETag"));

        given()
        .when()
            .get("/" + bucket + "/" + dstKey)
        .then()
            .statusCode(200)
            .body(equalTo("hello"));

        given().delete("/" + bucket + "/" + srcKey);
        given().delete("/" + bucket + "/" + dstKey);
        given().delete("/" + bucket);
    }

    @Test
    @Order(21)
    void copyObjectWithMalformedEncodedSourceReturns400() {
        given()
            .header("x-amz-copy-source", "/test-bucket/%ZZinvalid")
        .when()
            .put("/test-bucket/dest-key")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));
    }

    @Test
    @Order(22)
    void copyObjectWithEmptyBucketReturns400() {
        given()
            .header("x-amz-copy-source", "/key-only-no-bucket")
        .when()
            .put("/test-bucket/dest-key")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));
    }

    @Test
    @Order(21)
    void putLargeObject() {
        // 22 MB – exceeds the old Jackson 20 MB maxStringLength default
        byte[] largeBody = new byte[22 * 1024 * 1024];
        Arrays.fill(largeBody, (byte) 'A');

        given()
        .when()
            .put("/large-object-bucket")
        .then()
            .statusCode(200);

        given()
            .contentType("application/octet-stream")
            .body(largeBody)
        .when()
            .put("/large-object-bucket/large-file.bin")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());

        given()
        .when()
            .get("/large-object-bucket/large-file.bin")
        .then()
            .statusCode(200)
            .header("Content-Length", String.valueOf(largeBody.length));

        given().delete("/large-object-bucket/large-file.bin");
        given().delete("/large-object-bucket");
    }

    @Test
    @Order(30)
    void getObjectWithFullRange() {
        given()
            .header("Range", "bytes=0-4")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(206)
            .header("Content-Range", equalTo("bytes 0-4/20"))
            .header("Content-Length", equalTo("5"))
            .header("Accept-Ranges", equalTo("bytes"))
            .body(equalTo("Hello"));
    }

    @Test
    @Order(31)
    void getObjectWithOpenEndedRange() {
        given()
            .header("Range", "bytes=15-")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(206)
            .header("Content-Range", equalTo("bytes 15-19/20"))
            .body(equalTo("m S3!"));
    }

    @Test
    @Order(32)
    void getObjectWithSuffixRange() {
        given()
            .header("Range", "bytes=-4")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(206)
            .header("Content-Range", equalTo("bytes 16-19/20"))
            .body(equalTo(" S3!"));
    }

    @Test
    @Order(33)
    void getObjectWithInvalidRange() {
        given()
            .header("Range", "bytes=50-100")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(416)
            .header("Content-Range", equalTo("bytes */20"))
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(34)
    void getObjectWithMalformedRangeNoDash() {
        given()
            .header("Range", "bytes=0")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(35)
    void getObjectWithMalformedRangeEmptySuffix() {
        given()
            .header("Range", "bytes=-")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(36)
    void getObjectWithMalformedRangeNonNumeric() {
        given()
            .header("Range", "bytes=abc-def")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(37)
    void getObjectWithMalformedRangeNegativeStart() {
        given()
            .header("Range", "bytes=-1-4")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(38)
    void getObjectWithoutRangeReturnsAcceptRanges() {
        given()
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .header("Accept-Ranges", equalTo("bytes"));
    }

    @Test
    @Order(39)
    void headObjectReturnsAcceptRanges() {
        given()
        .when()
            .head("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .header("Accept-Ranges", equalTo("bytes"));
    }

    @Test
    @Order(40)
    void getObjectRangeOmitsWholeObjectChecksum() {
        // greeting.txt has a stored whole-object CRC64NVME checksum (see getObject).
        // A 206 partial response must NOT carry that checksum: it is computed over the
        // full object, so SDKs that validate it against the received range bytes fail.
        // Real S3 omits whole-object checksum headers on ranged responses.
        given()
            .header("Range", "bytes=4-7")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(206)
            .header("Content-Range", equalTo("bytes 4-7/20"))
            .body(equalTo("o Wo"))
            .header("x-amz-checksum-crc64nvme", nullValue());
    }

    @Test
    @Order(41)
    void getObjectWithSuffixRangeForEmptyObject() {
        given()
            .header("x-amz-meta-kind", "empty")
            .body(new byte[0])
        .when()
            .put("/test-bucket/empty.txt")
        .then()
            .statusCode(200);

        given()
            .header("Range", "bytes=-13")
        .when()
            .get("/test-bucket/empty.txt")
        .then()
            .statusCode(200)
            .header("Content-Length", equalTo("0"))
            .header("Accept-Ranges", equalTo("bytes"))
            .header("x-amz-meta-kind", equalTo("empty"))
            .body(equalTo(""));

        given()
        .when()
            .delete("/test-bucket/empty.txt")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(42)
    void getObjectRangeStreamsExactBytesFromLargeObject() {
        String bucket = "stream-range-bucket";
        String key = "large-range.txt";
        byte[] body = alphabetBytes(1024 * 1024);
        int start = 512 * 1024 + 13;
        int end = start + 31;

        given()
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);

        given()
            .contentType("application/octet-stream")
            .header("x-amz-meta-kind", "stream-range")
            .body(body)
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200);

        given()
            .header("Range", "bytes=" + start + "-" + end)
        .when()
            .get("/" + bucket + "/" + key)
        .then()
            .statusCode(206)
            .header("Content-Range", equalTo("bytes " + start + "-" + end + "/" + body.length))
            .header("Content-Length", equalTo("32"))
            .header("Accept-Ranges", equalTo("bytes"))
            .header("x-amz-meta-kind", equalTo("stream-range"))
            .header("x-amz-checksum-crc64nvme", nullValue())
            .body(equalTo(asciiSlice(body, start, 32)));

        given().delete("/" + bucket + "/" + key).then().statusCode(204);
        given().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    @Order(50)
    void getObjectIfNoneMatchReturns304() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-None-Match", eTag)
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(304)
            .header("ETag", equalTo(eTag));
    }

    @Test
    @Order(51)
    void getObjectIfNoneMatchNonMatchingReturns200() {
        given()
            .header("If-None-Match", "\"wrong-etag\"")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .body(equalTo("Hello World from S3!"));
    }

    @Test
    @Order(52)
    void getObjectIfMatchReturns200() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-Match", eTag)
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .body(equalTo("Hello World from S3!"));
    }

    @Test
    @Order(53)
    void getObjectIfMatchWrongEtagReturns412() {
        given()
            .header("If-Match", "\"wrong-etag\"")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(412)
            .body(containsString("PreconditionFailed"));
    }

    @Test
    @Order(54)
    void headObjectIfNoneMatchReturns304() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-None-Match", eTag)
        .when()
            .head("/test-bucket/greeting.txt")
        .then()
            .statusCode(304);
    }

    @Test
    @Order(55)
    void headObjectIfMatchReturns200() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-Match", eTag)
        .when()
            .head("/test-bucket/greeting.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(56)
    void headObjectIfMatchWrongEtagReturns412() {
        given()
            .header("If-Match", "\"wrong-etag\"")
        .when()
            .head("/test-bucket/greeting.txt")
        .then()
            .statusCode(412);
    }

    @Test
    @Order(57)
    void headObjectIfModifiedSinceReturns304() {
        given()
            .header("If-Modified-Since", "Sun, 24 Mar 2030 00:00:00 GMT")
        .when()
            .head("/test-bucket/greeting.txt")
        .then()
            .statusCode(304);
    }

    @Test
    @Order(58)
    void headObjectIfUnmodifiedSinceReturns412() {
        given()
            .header("If-Unmodified-Since", "Tue, 24 Mar 2020 00:00:00 GMT")
        .when()
            .head("/test-bucket/greeting.txt")
        .then()
            .statusCode(412);
    }

    @Test
    @Order(61)
    void getObjectIfModifiedSinceReturns304() {
        given()
            .header("If-Modified-Since", "Sun, 24 Mar 2030 00:00:00 GMT")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(304);
    }

    @Test
    @Order(62)
    void getObjectIfUnmodifiedSinceReturns412() {
        given()
            .header("If-Unmodified-Since", "Tue, 24 Mar 2020 00:00:00 GMT")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(412)
            .body(containsString("PreconditionFailed"));
    }

    @Test
    @Order(63)
    void getObjectIfMatchWildcardReturns200() {
        given()
            .header("If-Match", "*")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .body(equalTo("Hello World from S3!"));
    }

    @Test
    @Order(64)
    void getObjectIfNoneMatchCommaListReturns304() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-None-Match", "\"wrong-etag\", " + eTag + ", \"other\"")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(304);
    }

    @Test
    @Order(65)
    void ifNoneMatchTakesPrecedenceOverIfModifiedSince() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-None-Match", eTag)
            .header("If-Modified-Since", "Tue, 24 Mar 2020 00:00:00 GMT")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(304);
    }

    @Test
    @Order(66)
    void notModifiedResponseIncludesLastModified() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-None-Match", eTag)
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(304)
            .header("ETag", equalTo(eTag))
            .header("Last-Modified", notNullValue());
    }

    @Test
    @Order(70)
    void cleanupAndDeleteBucket() {
        // Delete all objects
        given().delete("/test-bucket/greeting.txt");
        given().delete("/test-bucket/data/config.json");

        // Now delete bucket
        given()
        .when()
            .delete("/test-bucket")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(80)
    void createEncodingTestBucket() {
        given()
        .when()
            .put("/encoding-test-bucket")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(81)
    void putObjectWithContentEncoding() {
        given()
            .contentType("text/plain")
            .header("Content-Encoding", "gzip")
            .body("compressed-content")
        .when()
            .put("/encoding-test-bucket/encoded.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("Content-Encoding", nullValue())
            .header("Content-Disposition", nullValue())
            .header("Cache-Control", nullValue());
    }

    @Test
    @Order(82)
    void getObjectReturnsContentEncoding() {
        RestAssuredConfig noDecompress = RestAssuredConfig.config()
                .decoderConfig(DecoderConfig.decoderConfig().noContentDecoders());
        given()
            .config(noDecompress)
        .when()
            .get("/encoding-test-bucket/encoded.txt")
        .then()
            .statusCode(200)
            .header("Content-Encoding", equalTo("gzip"));
    }

    @Test
    @Order(83)
    void headObjectReturnsContentEncoding() {
        given()
        .when()
            .head("/encoding-test-bucket/encoded.txt")
        .then()
            .statusCode(200)
            .header("Content-Encoding", equalTo("gzip"));
    }

    @Test
    @Order(84)
    void copyObjectPreservesContentEncoding() {
        given()
            .header("x-amz-copy-source", "/encoding-test-bucket/encoded.txt")
        .when()
            .put("/encoding-test-bucket/encoded-copy.txt")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        given()
        .when()
            .head("/encoding-test-bucket/encoded-copy.txt")
        .then()
            .statusCode(200)
            .header("Content-Encoding", equalTo("gzip"));
    }

    @Test
    @Order(85)
    void copyObjectReplaceContentEncoding() {
        given()
            .header("x-amz-copy-source", "/encoding-test-bucket/encoded.txt")
            .header("x-amz-metadata-directive", "REPLACE")
            .header("Content-Encoding", "identity")
        .when()
            .put("/encoding-test-bucket/encoded-replace.txt")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        given()
        .when()
            .head("/encoding-test-bucket/encoded-replace.txt")
        .then()
            .statusCode(200)
            .header("Content-Encoding", equalTo("identity"));
    }

    @Test
    @Order(86)
    void putObjectWithCompositeEncoding_stripsAwsChunkedToken() {
        RestAssuredConfig noDecompress = RestAssuredConfig.config()
                .decoderConfig(DecoderConfig.decoderConfig().noContentDecoders());
        given()
            .contentType("text/plain")
            .header("Content-Encoding", "gzip,aws-chunked")
            .body("compressed-chunked-content")
        .when()
            .put("/encoding-test-bucket/composite-encoded.txt")
        .then()
            .statusCode(200);

        given()
            .config(noDecompress)
        .when()
            .head("/encoding-test-bucket/composite-encoded.txt")
        .then()
            .statusCode(200)
            .header("Content-Encoding", equalTo("gzip"));
    }

    @Test
    @Order(88)
    void cleanupContentEncodingBucket() {
        given().delete("/encoding-test-bucket/encoded.txt");
        given().delete("/encoding-test-bucket/encoded-copy.txt");
        given().delete("/encoding-test-bucket/encoded-replace.txt");
        given().delete("/encoding-test-bucket/composite-encoded.txt");
        given().delete("/encoding-test-bucket");
    }

    // --- Cache-Control header preservation ---

    @Test
    @Order(89)
    void createCacheControlBucketAndPutObject() {
        given()
            .put("/cache-control-bucket")
        .then()
            .statusCode(200);

        given()
            .contentType("text/plain")
            .header("Cache-Control", "public, max-age=31536000")
            .body("cached-content")
        .when()
            .put("/cache-control-bucket/cached.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
    }

    @Test
    @Order(90)
    void getObjectReturnsCacheControl() {
        given()
        .when()
            .get("/cache-control-bucket/cached.txt")
        .then()
            .statusCode(200)
            .header("Cache-Control", equalTo("public, max-age=31536000"));
    }

    @Test
    @Order(90)
    void headObjectReturnsCacheControl() {
        given()
        .when()
            .head("/cache-control-bucket/cached.txt")
        .then()
            .statusCode(200)
            .header("Cache-Control", equalTo("public, max-age=31536000"));
    }

    @Test
    @Order(91)
    void copyObjectPreservesCacheControl() {
        given()
            .header("x-amz-copy-source", "/cache-control-bucket/cached.txt")
        .when()
            .put("/cache-control-bucket/cached-copy.txt")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        given()
        .when()
            .head("/cache-control-bucket/cached-copy.txt")
        .then()
            .statusCode(200)
            .header("Cache-Control", equalTo("public, max-age=31536000"));
    }

    @Test
    @Order(91)
    void copyObjectReplaceCacheControl() {
        given()
            .header("x-amz-copy-source", "/cache-control-bucket/cached.txt")
            .header("x-amz-metadata-directive", "REPLACE")
            .header("Cache-Control", "no-cache")
        .when()
            .put("/cache-control-bucket/cached-nocache.txt")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        given()
        .when()
            .head("/cache-control-bucket/cached-nocache.txt")
        .then()
            .statusCode(200)
            .header("Cache-Control", equalTo("no-cache"));
    }

    @Test
    @Order(92)
    void cleanupCacheControlBucket() {
        given().delete("/cache-control-bucket/cached.txt");
        given().delete("/cache-control-bucket/cached-copy.txt");
        given().delete("/cache-control-bucket/cached-nocache.txt");
        given().delete("/cache-control-bucket");
    }

    // --- Content-Disposition header preservation ---

    @Test
    @Order(130)
    void createContentDispositionBucketAndPutObject() {
        String disposition = "attachment; filename=\"download.txt\"";

        given()
            .put("/content-disposition-bucket")
        .then()
            .statusCode(200);

        given()
            .contentType("text/plain")
            .header("Content-Disposition", disposition)
            .body("disposition-content")
        .when()
            .put("/content-disposition-bucket/disposition.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
    }

    @Test
    @Order(131)
    void getObjectReturnsContentDisposition() {
        given()
        .when()
            .get("/content-disposition-bucket/disposition.txt")
        .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("attachment; filename=\"download.txt\""));
    }

    @Test
    @Order(132)
    void headObjectReturnsContentDisposition() {
        given()
        .when()
            .head("/content-disposition-bucket/disposition.txt")
        .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("attachment; filename=\"download.txt\""));
    }

    @Test
    @Order(133)
    void copyObjectPreservesContentDisposition() {
        given()
            .header("x-amz-copy-source", "/content-disposition-bucket/disposition.txt")
        .when()
            .put("/content-disposition-bucket/disposition-copy.txt")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        given()
        .when()
            .head("/content-disposition-bucket/disposition-copy.txt")
        .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("attachment; filename=\"download.txt\""));
    }

    @Test
    @Order(134)
    void copyObjectReplaceContentDisposition() {
        given()
            .header("x-amz-copy-source", "/content-disposition-bucket/disposition.txt")
            .header("x-amz-metadata-directive", "REPLACE")
            .header("Content-Disposition", "inline; filename=\"inline.txt\"")
        .when()
            .put("/content-disposition-bucket/disposition-inline.txt")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        given()
        .when()
            .head("/content-disposition-bucket/disposition-inline.txt")
        .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("inline; filename=\"inline.txt\""));
    }

    @Test
    @Order(135)
    void cleanupContentDispositionBucket() {
        given().delete("/content-disposition-bucket/disposition.txt");
        given().delete("/content-disposition-bucket/disposition-copy.txt");
        given().delete("/content-disposition-bucket/disposition-inline.txt");
        given().delete("/content-disposition-bucket");
    }

    // --- Server-Side Encryption header preservation ---

    @Test
    @Order(136)
    void createSseBucketAndPutObject() {
        given()
            .put("/sse-bucket")
        .then()
            .statusCode(200);

        given()
            .contentType("text/plain")
            .header("x-amz-server-side-encryption", "AES256")
            .body("encrypted-content")
        .when()
            .put("/sse-bucket/encrypted.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("x-amz-server-side-encryption", equalTo("AES256"));
    }

    @Test
    @Order(137)
    void getObjectReturnsServerSideEncryption() {
        given()
        .when()
            .get("/sse-bucket/encrypted.txt")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption", equalTo("AES256"));
    }

    @Test
    @Order(138)
    void headObjectReturnsServerSideEncryption() {
        given()
        .when()
            .head("/sse-bucket/encrypted.txt")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption", equalTo("AES256"));
    }

    @Test
    @Order(139)
    void copyObjectPreservesServerSideEncryption() {
        given()
            .header("x-amz-copy-source", "/sse-bucket/encrypted.txt")
        .when()
            .put("/sse-bucket/encrypted-copy.txt")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        given()
        .when()
            .head("/sse-bucket/encrypted-copy.txt")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption", equalTo("AES256"));
    }

    @Test
    @Order(140)
    void putObjectRejectsUnsupportedServerSideEncryption() {
        given()
            .contentType("text/plain")
            .header("x-amz-server-side-encryption", "totally-unsupported")
            .body("bad encryption")
        .when()
            .put("/sse-bucket/invalid-encryption.txt")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"))
            .body(containsString("Unsupported x-amz-server-side-encryption value"));
    }

    @Test
    @Order(141)
    void putObjectWithSseCustomerKey() {
        given()
            .contentType("text/plain")
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
            .body("sse-c-content")
        .when()
            .put("/sse-bucket/sse-c.txt")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption-customer-algorithm", equalTo("AES256"))
            .header("x-amz-server-side-encryption-customer-key-MD5", equalTo(SSE_CUSTOMER_KEY_MD5));
    }

    @Test
    @Order(142)
    void getObjectWithSseCustomerKeyRequiresMatchingKey() {
        given()
        .when()
            .get("/sse-bucket/sse-c.txt")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));

        given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", WRONG_SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", WRONG_SSE_CUSTOMER_KEY_MD5)
        .when()
            .get("/sse-bucket/sse-c.txt")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
        .when()
            .get("/sse-bucket/sse-c.txt")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption-customer-algorithm", equalTo("AES256"))
            .header("x-amz-server-side-encryption-customer-key-MD5", equalTo(SSE_CUSTOMER_KEY_MD5))
            .body(equalTo("sse-c-content"));
    }

    @Test
    @Order(143)
    void headObjectWithSseCustomerKeyRequiresMatchingKey() {
        given()
        .when()
            .head("/sse-bucket/sse-c.txt")
        .then()
            .statusCode(400);

        given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
        .when()
            .head("/sse-bucket/sse-c.txt")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption-customer-algorithm", equalTo("AES256"))
            .header("x-amz-server-side-encryption-customer-key-MD5", equalTo(SSE_CUSTOMER_KEY_MD5));
    }

    @Test
    @Order(144)
    void copyObjectWithSseCustomerKeyRequiresSourceKeyAndSupportsDestinationKey() {
        given()
            .header("x-amz-copy-source", "/sse-bucket/sse-c.txt")
        .when()
            .put("/sse-bucket/sse-c-copy.txt")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));

        given()
            .header("x-amz-copy-source", "/sse-bucket/sse-c.txt")
            .header("x-amz-copy-source-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-copy-source-server-side-encryption-customer-key", WRONG_SSE_CUSTOMER_KEY)
            .header("x-amz-copy-source-server-side-encryption-customer-key-MD5", WRONG_SSE_CUSTOMER_KEY_MD5)
        .when()
            .put("/sse-bucket/sse-c-copy.txt")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
            .header("x-amz-copy-source", "/sse-bucket/sse-c.txt")
            .header("x-amz-copy-source-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-copy-source-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-copy-source-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
        .when()
            .put("/sse-bucket/sse-c-copy.txt")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption-customer-algorithm", equalTo("AES256"))
            .header("x-amz-server-side-encryption-customer-key-MD5", equalTo(SSE_CUSTOMER_KEY_MD5));

        given()
        .when()
            .get("/sse-bucket/sse-c-copy.txt")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));

        given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
        .when()
            .get("/sse-bucket/sse-c-copy.txt")
        .then()
            .statusCode(200)
            .body(equalTo("sse-c-content"));
    }

    @Test
    @Order(145)
    void putObjectRejectsInvalidSseCustomerKeyMd5() {
        given()
            .contentType("text/plain")
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", WRONG_SSE_CUSTOMER_KEY_MD5)
            .body("bad sse-c")
        .when()
            .put("/sse-bucket/sse-c-invalid.txt")
        .then()
            .statusCode(400)
            .body(containsString("InvalidDigest"));
    }

    @Test
    @Order(146)
    void putObjectRejectsUnsupportedSseCustomerAlgorithm() {
        given()
            .contentType("text/plain")
            .header("x-amz-server-side-encryption-customer-algorithm", "aws:kms")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
            .body("bad sse-c")
        .when()
            .put("/sse-bucket/sse-c-invalid-algorithm.txt")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"))
            .body(containsString("Unsupported x-amz-server-side-encryption-customer-algorithm value"));
    }

    @Test
    @Order(147)
    void putObjectRejectsInvalidSseCustomerKeyBase64() {
        given()
            .contentType("text/plain")
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", "not-base64")
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
            .body("bad sse-c")
        .when()
            .put("/sse-bucket/sse-c-invalid-key.txt")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"))
            .body(containsString("not valid base64"));
    }

    @Test
    @Order(148)
    void putObjectRejectsInvalidSseCustomerKeyLength() {
        given()
            .contentType("text/plain")
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SHORT_SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SHORT_SSE_CUSTOMER_KEY_MD5)
            .body("bad sse-c")
        .when()
            .put("/sse-bucket/sse-c-invalid-length.txt")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"))
            .body(containsString("256-bit key"));
    }

    @Test
    @Order(149)
    void putObjectRejectsConflictingServerSideEncryption() {
        given()
            .contentType("text/plain")
            .header("x-amz-server-side-encryption", "AES256")
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
            .body("bad sse-c")
        .when()
            .put("/sse-bucket/sse-c-conflicting-encryption.txt")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"))
            .body(containsString("SSE-C cannot be combined"));
    }

    @Test
    @Order(153)
    void cleanupSseBucket() {
        given().delete("/sse-bucket/encrypted.txt");
        given().delete("/sse-bucket/encrypted-copy.txt");
        given().delete("/sse-bucket/sse-c.txt");
        given().delete("/sse-bucket/sse-c-copy.txt");
        given().delete("/sse-bucket");
    }

    // --- S3 Notification Configuration with Filter ---

    @Test
    @Order(90)
    void createNotificationBucket() {
        given()
        .when()
            .put("/notif-test-bucket")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(91)
    void putNotificationConfigWithFilterIsNotDropped() {
        String xml = """
                <NotificationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <QueueConfiguration>
                    <Id>my-notif</Id>
                    <Queue>arn:aws:sqs:us-east-1:000000000000:test-queue</Queue>
                    <Event>s3:ObjectCreated:*</Event>
                    <Filter>
                      <S3Key>
                        <FilterRule>
                          <Name>prefix</Name>
                          <Value>incoming/</Value>
                        </FilterRule>
                      </S3Key>
                    </Filter>
                  </QueueConfiguration>
                </NotificationConfiguration>
                """;

        given()
            .contentType("application/xml")
            .queryParam("notification", "")
            .body(xml)
        .when()
            .put("/notif-test-bucket")
        .then()
            .statusCode(200);

        given()
            .queryParam("notification", "")
        .when()
            .get("/notif-test-bucket")
        .then()
            .statusCode(200)
            .body(containsString("QueueConfiguration"))
            .body(containsString("arn:aws:sqs:us-east-1:000000000000:test-queue"))
            .body(containsString("s3:ObjectCreated:*"))
            // Verify filter rules are preserved in round-trip
            .body(containsString("Filter"))
            .body(containsString("FilterRule"))
            .body(containsString("<Name>prefix</Name>"))
            .body(containsString("<Value>incoming/</Value>"));
    }

    @Test
    @Order(92)
    void putNotificationConfigWithFilterBeforeQueueIsNotDropped() {
        // Filter appears BEFORE Queue — ensures element order doesn't matter
        String xml = """
                <NotificationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <QueueConfiguration>
                    <Id>filter-first</Id>
                    <Filter>
                      <S3Key>
                        <FilterRule>
                          <Name>suffix</Name>
                          <Value>.csv</Value>
                        </FilterRule>
                      </S3Key>
                    </Filter>
                    <Queue>arn:aws:sqs:us-east-1:000000000000:csv-queue</Queue>
                    <Event>s3:ObjectCreated:Put</Event>
                  </QueueConfiguration>
                </NotificationConfiguration>
                """;

        given()
            .contentType("application/xml")
            .queryParam("notification", "")
            .body(xml)
        .when()
            .put("/notif-test-bucket")
        .then()
            .statusCode(200);

        given()
            .queryParam("notification", "")
        .when()
            .get("/notif-test-bucket")
        .then()
            .statusCode(200)
            .body(containsString("QueueConfiguration"))
            .body(containsString("arn:aws:sqs:us-east-1:000000000000:csv-queue"))
            .body(containsString("s3:ObjectCreated:Put"))
            .body(containsString("<Name>suffix</Name>"))
            .body(containsString("<Value>.csv</Value>"));
    }

    @Test
    @Order(93)
    void putLambdaNotificationConfigWithFilterIsPersisted() {
        String xml = """
                <NotificationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <CloudFunctionConfiguration>
                    <Id>lambda-notif</Id>
                    <CloudFunction>arn:aws:lambda:us-east-1:000000000000:function:s3-notif-test</CloudFunction>
                    <Event>s3:ObjectCreated:Put</Event>
                    <Filter>
                      <S3Key>
                        <FilterRule>
                          <Name>prefix</Name>
                          <Value>uploads/</Value>
                        </FilterRule>
                        <FilterRule>
                          <Name>suffix</Name>
                          <Value>.json</Value>
                        </FilterRule>
                      </S3Key>
                    </Filter>
                  </CloudFunctionConfiguration>
                </NotificationConfiguration>
                """;

        given()
            .contentType("application/xml")
            .queryParam("notification", "")
            .body(xml)
        .when()
            .put("/notif-test-bucket")
        .then()
            .statusCode(200);

        given()
            .queryParam("notification", "")
        .when()
            .get("/notif-test-bucket")
        .then()
            .statusCode(200)
            .body(containsString("CloudFunctionConfiguration"))
            .body(containsString("arn:aws:lambda:us-east-1:000000000000:function:s3-notif-test"))
            .body(containsString("s3:ObjectCreated:Put"))
            .body(containsString("<Name>prefix</Name>"))
            .body(containsString("<Value>uploads/</Value>"))
            .body(containsString("<Name>suffix</Name>"))
            .body(containsString("<Value>.json</Value>"));
    }

    @Test
    @Order(94)
    void notificationDeliveredToQueueInDifferentRegion() {
        String sqsAuth = "Credential=AKID/20260507/ap-southeast-2/s3/aws4_request";

        String queueUrl = given()
            .header("Authorization", sqsAuth)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "notif-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        try {
            given()
                .contentType("application/xml")
                .queryParam("notification", "")
                .body("""
                    <NotificationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <QueueConfiguration>
                            <Id>sqs-notif</Id>
                            <Queue>arn:aws:sqs:ap-southeast-2:000000000000:notif-test-queue</Queue>
                            <Event>s3:ObjectCreated:*</Event>
                        </QueueConfiguration>
                    </NotificationConfiguration>
                """)
            .when()
                .put("/notif-test-bucket")
            .then()
                .statusCode(200);

            given()
                .contentType("text/plain")
                .body("hello")
            .when()
                .put("/notif-test-bucket/file.txt")
            .then()
                .statusCode(200);

            given()
                .header("Authorization", sqsAuth)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(
                    "ReceiveMessageResponse.ReceiveMessageResult.Message.Body",
                    allOf(containsString("notif-test-bucket"), containsString("file.txt"))
                );
        } finally {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", sqsAuth)
                .formParam("Action", "DeleteQueue")
                .formParam("QueueUrl", queueUrl)
                .post("/");
        }
    }

    @Test
    @Order(95)
    void cleanupNotificationBucket() {
        given().delete("/notif-test-bucket");
    }

    // --- PublicAccessBlock ---

    @Test
    @Order(100)
    void putPublicAccessBlockReturns200() {
        given().when().put("/test-bucket").then().statusCode(anyOf(equalTo(200), equalTo(409)));

        String xml = "<PublicAccessBlockConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<BlockPublicAcls>true</BlockPublicAcls>"
                + "<IgnorePublicAcls>true</IgnorePublicAcls>"
                + "<BlockPublicPolicy>true</BlockPublicPolicy>"
                + "<RestrictPublicBuckets>true</RestrictPublicBuckets>"
                + "</PublicAccessBlockConfiguration>";
        given()
            .body(xml)
        .when()
            .put("/test-bucket?publicAccessBlock")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(101)
    void getPublicAccessBlockReturnsStoredConfig() {
        given()
        .when()
            .get("/test-bucket?publicAccessBlock")
        .then()
            .statusCode(200)
            .body(containsString("BlockPublicAcls"))
            .body(containsString("true"));
    }

    @Test
    @Order(102)
    void deletePublicAccessBlockReturns204() {
        given()
        .when()
            .delete("/test-bucket?publicAccessBlock")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(103)
    void getPublicAccessBlockAfterDeleteReturns404() {
        given()
        .when()
            .get("/test-bucket?publicAccessBlock")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchPublicAccessBlockConfiguration"));
    }

    // --- ListObjectsV2 pagination ---

    @Test
    @Order(110)
    void listObjectsV2StartAfterFiltersResults() {
        // bucket and objects from earlier test orders exist; add fresh ones in a dedicated bucket
        given().when().put("/pag-test-bucket").then().statusCode(200);
        given().body("a").when().put("/pag-test-bucket/a.txt").then().statusCode(200);
        given().body("b").when().put("/pag-test-bucket/b.txt").then().statusCode(200);
        given().body("c").when().put("/pag-test-bucket/c.txt").then().statusCode(200);

        given()
        .when()
            .get("/pag-test-bucket?list-type=2&start-after=a.txt")
        .then()
            .statusCode(200)
            .body(containsString("<StartAfter>a.txt</StartAfter>"))
            .body(not(containsString("<Key>a.txt</Key>")))
            .body(containsString("<Key>b.txt</Key>"))
            .body(containsString("<Key>c.txt</Key>"));
    }

    @Test
    @Order(111)
    void listObjectsV2ContinuationTokenPaginates() {
        // First page: max-keys=2
        String page1Body =
            given()
            .when()
                .get("/pag-test-bucket?list-type=2&max-keys=2")
            .then()
                .statusCode(200)
                .body(containsString("<IsTruncated>true</IsTruncated>"))
                .body(containsString("<NextContinuationToken>"))
                .extract().body().asString();

        // Extract NextContinuationToken
        int start = page1Body.indexOf("<NextContinuationToken>") + "<NextContinuationToken>".length();
        int end = page1Body.indexOf("</NextContinuationToken>");
        String token = page1Body.substring(start, end);

        // Second page using the token
        given()
        .when()
            .get("/pag-test-bucket?list-type=2&max-keys=2&continuation-token=" + token)
        .then()
            .statusCode(200)
            .body(containsString("<IsTruncated>false</IsTruncated>"))
            .body(containsString("<ContinuationToken>" + token + "</ContinuationToken>"))
            .body(containsString("<Key>c.txt</Key>"));
    }

    @Test
    @Order(112)
    void listObjectsV2EncodingTypeIsEchoed() {
        given()
        .when()
            .get("/pag-test-bucket?list-type=2&encoding-type=url")
        .then()
            .statusCode(200)
            .body(containsString("<EncodingType>url</EncodingType>"));
    }

    @Test
    @Order(113)
    void cleanupPaginationBucket() {
        given().when().delete("/pag-test-bucket/a.txt");
        given().when().delete("/pag-test-bucket/b.txt");
        given().when().delete("/pag-test-bucket/c.txt");
        given().when().delete("/pag-test-bucket");
    }

    @Test
    @Order(120)
    void getBucketLocation_usEast1ReturnsEmptyLocationConstraint() {
        String bucket = "location-us-east-1-bucket";

        given()
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + bucket + "?location")
        .then()
            .statusCode(200)
            .body(not(containsString("<?xml")))
            .body(containsString("<LocationConstraint"))
            .body(not(containsString("us-east-1")));

        given().when().delete("/" + bucket);
    }

    @Test
    @Order(121)
    void getBucketLocation_nonUsEast1ReturnsRegionInBody() {
        String bucket = "location-eu-central-bucket";
        String createBucketConfiguration = """
                <CreateBucketConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <LocationConstraint>eu-central-1</LocationConstraint>
                </CreateBucketConfiguration>
                """;

        given()
            .contentType("application/xml")
            .body(createBucketConfiguration)
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + bucket + "?location")
        .then()
            .statusCode(200)
            .body(not(containsString("<?xml")))
            .body(containsString("eu-central-1"));

        given().when().delete("/" + bucket);
    }
    @Test
    void pathTraversalAttemptsReturn400() {
        // 1. URL-encoded dots survival through Vertx but decoded by our extractObjectKey
        given()
                .urlEncodingEnabled(false)
                .pathParam("bucket", "test-bucket")
        .when()
                .get("/{bucket}/%2e%2e/%2e%2e/secret.txt")
        .then()
                .statusCode(400)
                .body(equalTo(""));

        // 2. Null byte (survives URL-decoding but fails java.nio.file.Path validation)
        given()
                .urlEncodingEnabled(false)
                .pathParam("bucket", "test-bucket")
        .when()
                .get("/{bucket}/%00.txt")
        .then()
                .statusCode(400)
                .body(containsString("InvalidKey"));

        // 3. Mixed-case percent-encoded traversal (%2E instead of %2e)
        //    Absolute paths like //etc/passwd are normalized by the HTTP server
        //    before reaching the controller, so they can't be tested via HTTP.
        //    They are caught at the service layer by the startsWith(bucketDir) guard.
        given()
                .urlEncodingEnabled(false)
                .pathParam("bucket", "test-bucket")
        .when()
                .get("/{bucket}/%2E%2E/%2E%2E/secret.txt")
        .then()
                .statusCode(400)
                .body(equalTo(""));
    }

    @Test
    @Order(150)
    void putObjectRejectsMismatchedCRC32() {
        given()
            .body("hello")
            .header("x-amz-checksum-crc32", "INVALID==")
        .when()
            .put("/test-bucket/checksum-crc32-test.txt")
        .then()
            .statusCode(400)
            .body(containsString("BadDigest"));
    }

    @Test
    @Order(151)
    void putObjectRejectsMismatchedCRC32C() {
        given()
            .body("hello")
            .header("x-amz-checksum-crc32c", "INVALID==")
        .when()
            .put("/test-bucket/checksum-crc32c-test.txt")
        .then()
            .statusCode(400)
            .body(containsString("BadDigest"));
    }

    @Test
    @Order(152)
    void putObjectRejectsMismatchedCRC64NVME() {
        given()
            .body("hello")
            .header("x-amz-checksum-crc64nvme", "INVALID==")
        .when()
            .put("/test-bucket/checksum-crc64nvme-test.txt")
        .then()
            .statusCode(400)
            .body(containsString("BadDigest"));
    }

    @Test
    @Order(200)
    void putObjectWithRawTraversalAboveBucketReturnsBadRequest() {
        given()
            .contentType("text/plain")
            .body("safe-data")
        .when()
            .put("/test-bucket/../../secret.txt")
        .then()
            .statusCode(400)
            .body(equalTo(""));
    }

    @Test
    @Order(201)
    void putObjectWithEncodedTraversalAboveBucketReturnsBadRequest() {
        given()
            .urlEncodingEnabled(false)
            .contentType("text/plain")
            .body("safe-data")
        .when()
            .put("/test-bucket/%2E%2E/%2E%2E/secret.txt")
        .then()
            .statusCode(400)
            .body(equalTo(""));
    }

    @Test
    @Order(202)
    void putObjectWithEncodedSlashTraversalAboveBucketReturnsBadRequest() {
        given()
            .urlEncodingEnabled(false)
            .contentType("text/plain")
            .body("safe-data")
        .when()
            .put("/test-bucket/%2E%2E%2Fsecret.txt")
        .then()
            .statusCode(400)
            .body(equalTo(""));
    }

    @Test
    @Order(203)
    void putObjectWithInternalTraversalSucceeds() {
        given()
            .urlEncodingEnabled(false)
            .contentType("text/plain")
            .body("safe-data")
        .when()
            .put("/test-bucket/docs/%2E%2E/file.txt")
        .then()
            .statusCode(200);

        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/test-bucket/docs/%2E%2E/file.txt")
        .then()
            .statusCode(200)
            .body(equalTo("safe-data"));
    }

    @Test
    @Order(204)
    void listObjectsAllowsTraversalInQueryString() {
        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/test-bucket?prefix=../x")
        .then()
            .statusCode(200)
            .body(containsString("ListBucketResult"));
    }

    @Test
    @Order(205)
    void putObjectWithTraversalAfterBucketPrefixReturnsBadRequest() {
        given()
            .contentType("text/plain")
            .body("safe-data")
        .when()
            .put("/test-bucket/../secret.txt")
        .then()
            .statusCode(400)
            .body(equalTo(""));
    }

    @Test
    @Order(206)
    void bucketLogging_roundTripAndDisable() {
        String bucket = "bucket-logging-test";
        String targetBucket = "bucket-logging-target";

        given()
                .when().put("/" + bucket)
                .then().statusCode(200);

        given()
                .when().put("/" + targetBucket)
                .then().statusCode(200);

        given()
                .queryParam("logging", "")
                .when().get("/" + bucket)
                .then()
                .statusCode(200)
                .body(containsString("BucketLoggingStatus"))
                .body(not(containsString("LoggingEnabled")));

        String loggingXml = """
                <BucketLoggingStatus xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <LoggingEnabled xmlns:test="http://example.com/test" test:attr="value">
                    <TargetBucket>%s</TargetBucket>
                    <TargetPrefix>logs/</TargetPrefix>
                  </LoggingEnabled>
                </BucketLoggingStatus>
                """.formatted(targetBucket);

        given()
                .queryParam("logging", "")
                .body(loggingXml)
                .when().put("/" + bucket)
                .then()
                .statusCode(200);

        given()
                .queryParam("logging", "")
                .when().get("/" + bucket)
                .then()
                .statusCode(200)
                .body(containsString("LoggingEnabled"))
                .body(containsString(targetBucket))
                .body(containsString("logs/"));

        String disabledXml = """
                <BucketLoggingStatus xmlns="http://s3.amazonaws.com/doc/2006-03-01/"/>
                """;

        given()
                .queryParam("logging", "")
                .body(disabledXml)
                .when().put("/" + bucket)
                .then()
                .statusCode(200);

        given()
                .queryParam("logging", "")
                .when().get("/" + bucket)
                .then()
                .statusCode(200)
                .body(containsString("BucketLoggingStatus"))
                .body(not(containsString("LoggingEnabled")));
    }

    private static String customerKeyMd5(String customerKey) {
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest(Base64.getDecoder().decode(customerKey));
            return Base64.getEncoder().encodeToString(md5);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    private static byte[] alphabetBytes(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ('a' + (i % 26));
        }
        return bytes;
    }

    private static String asciiSlice(byte[] bytes, int start, int length) {
        return new String(bytes, start, length, StandardCharsets.US_ASCII);
    }
}
