package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class S3PresignedPutTaggingIntegrationTest {

    private static final String BUCKET = "presigned-tagging-bucket";

    @Inject
    PreSignedUrlGenerator presignGenerator;

    /**
     * Regression test for #932: a presigned PUT URL whose {@code X-Amz-SignedHeaders}
     * includes {@code x-amz-tagging} must NOT be misrouted to the
     * {@code ?tagging} sub-resource handler (PutObjectTagging).
     *
     * <p>The fix was in {@link S3RequestParser#hasQueryParamInString}: the old
     * {@code String.contains()} implementation falsely matched "tagging" inside the
     * <em>value</em> of {@code X-Amz-SignedHeaders} (e.g.
     * {@code host%3Bx-amz-tagging}), causing a 404. The new implementation correctly
     * splits on {@code &} and extracts parameter names before {@code =}.
     */
    @Test
    void presignedPutWithTaggingSignedHeaderDoesNotMisrouteToPutObjectTagging() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);

        int port = io.restassured.RestAssured.port;
        String fullBaseUrl = "http://localhost:" + port;
        String presignedUrl = presignGenerator.generatePresignedUrl(
                fullBaseUrl, BUCKET, "tagged-via-presign.txt", "PUT", 3600);

        URI uri = URI.create(presignedUrl);
        String rawQuery = uri.getRawQuery();
        assertTrue(rawQuery.contains("X-Amz-SignedHeaders=host"),
                "expected generated presigned URL to include X-Amz-SignedHeaders=host");

        // Add x-amz-tagging to X-Amz-SignedHeaders by properly parsing and reconstructing
        // the query string — avoids the fragility of raw String.replace().
        String taggingSignedHeadersQuery = Arrays.stream(rawQuery.split("&"))
                .map(pair -> {
                    if (pair.startsWith("X-Amz-SignedHeaders=")) {
                        return pair + "%3Bx-amz-tagging";
                    }
                    return pair;
                })
                .collect(Collectors.joining("&"));

        given()
            .urlEncodingEnabled(false)
            .contentType("text/plain")
            .header("x-amz-tagging", "tag=test")
            .body("uploaded via presigned PUT with tagging")
        .when()
            .put(uri.getRawPath() + "?" + taggingSignedHeadersQuery)
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());

        given()
        .when()
            .get("/" + BUCKET + "/tagged-via-presign.txt")
        .then()
            .statusCode(200)
            .body(equalTo("uploaded via presigned PUT with tagging"));

        given()
        .when()
            .get("/" + BUCKET + "/tagged-via-presign.txt?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>tag</Key>"))
            .body(containsString("<Value>test</Value>"));
    }

    /**
     * Regression test for #3608: SDK presigners hoist {@code x-amz-tagging} into the query
     * string and sign only {@code host}, so the uploader sends no tagging header at all.
     * Real S3 applies the tags from the query parameter; Floci must do the same.
     *
     * <p>The header form is itself URL-encoded ({@code c=d%20e}), and the presigner
     * URL-encodes that whole value once more when it becomes a query parameter, which is
     * why the tag string below is double-encoded.
     */
    @Test
    void presignedPutWithTaggingQueryParameterAppliesTags() {
        createBucket();
        String key = "tagged-via-query.txt";
        URI uri = presign(key, "PUT");

        given()
            .urlEncodingEnabled(false)
            .contentType("text/plain")
            .body("uploaded via presigned PUT with tagging in the query string")
        .when()
            .put(uri.getRawPath() + "?" + uri.getRawQuery() + "&x-amz-tagging=a%3Db%26c%3Dd%2520e")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());

        given()
        .when()
            .get("/" + BUCKET + "/" + key + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>a</Key>"))
            .body(containsString("<Value>b</Value>"))
            .body(containsString("<Key>c</Key>"))
            .body(containsString("<Value>d e</Value>"));
    }

    @Test
    void presignedPutWithTaggingHeaderAndQueryParameterPrefersHeader() {
        createBucket();
        String key = "tagged-via-header-and-query.txt";
        URI uri = presign(key, "PUT");

        given()
            .urlEncodingEnabled(false)
            .contentType("text/plain")
            .header("x-amz-tagging", "header=1")
            .body("the signed header wins over the query parameter")
        .when()
            .put(uri.getRawPath() + "?" + uri.getRawQuery() + "&x-amz-tagging=query%3D1")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/" + key + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>header</Key>"))
            .body(containsString("<Value>1</Value>"))
            .body(not(containsString("<Key>query</Key>")));
    }

    @Test
    void presignedCreateMultipartUploadWithTaggingQueryParameterAppliesTags() {
        createBucket();
        String key = "tagged-multipart-via-query.txt";
        String objectPath = "/" + BUCKET + "/" + key;
        URI uri = presign(key, "POST");

        String uploadId = given()
            .urlEncodingEnabled(false)
        .when()
            .post(uri.getRawPath() + "?uploads&x-amz-tagging=a%3Db%26c%3Dd&" + uri.getRawQuery())
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        String partETag = given()
            .body("part-one")
        .when()
            .put(objectPath + "?uploadId=" + uploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .extract().header("ETag");

        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>%s</ETag></Part>
                </CompleteMultipartUpload>""".formatted(partETag);

        given()
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post(objectPath + "?uploadId=" + uploadId)
        .then()
            .statusCode(200);

        given()
        .when()
            .get(objectPath + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>a</Key>"))
            .body(containsString("<Value>b</Value>"))
            .body(containsString("<Key>c</Key>"))
            .body(containsString("<Value>d</Value>"));
    }

    private static void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    private URI presign(String key, String method) {
        String fullBaseUrl = "http://localhost:" + RestAssured.port;
        return URI.create(presignGenerator.generatePresignedUrl(fullBaseUrl, BUCKET, key, method, 3600));
    }
}
