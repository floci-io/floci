package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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
    }
}
