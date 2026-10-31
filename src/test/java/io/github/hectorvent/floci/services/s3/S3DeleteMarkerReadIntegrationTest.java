package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class S3DeleteMarkerReadIntegrationTest {
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    @Test
    void aReadOfTheCurrentDeleteMarkerIsAMissingKeyThatNamesTheMarker() {
        String bucket = createBucket("marker-read-current", "Enabled");
        given().body("data").when().put("/" + bucket + "/key").then().statusCode(200);
        String marker = deleteKey(bucket);

        for (String method : List.of("GET", "HEAD")) {
            given().when().request(method, "/" + bucket + "/key").then().statusCode(404)
                    .header("x-amz-delete-marker", "true")
                    .header("x-amz-version-id", marker)
                    .header("Last-Modified", nullValue())
                    .header("Allow", nullValue());
        }
        given().header("Range", "bytes=0-0").when().get("/" + bucket + "/key").then().statusCode(404)
                .header("x-amz-delete-marker", "true")
                .header("x-amz-version-id", marker);
        given().header("If-Match", "\"stale\"").when().get("/" + bucket + "/key").then().statusCode(404)
                .header("x-amz-delete-marker", "true")
                .header("x-amz-version-id", marker);
    }

    @Test
    void aReadThatNamesADeleteMarkerIsRefused() {
        String bucket = createBucket("marker-read-named", "Enabled");
        given().body("data").when().put("/" + bucket + "/key").then().statusCode(200);
        String marker = deleteKey(bucket);
        String lastModified = markerLastModified(bucket);

        assertRefused(bucket, marker, lastModified);
        given().body("again").when().put("/" + bucket + "/key").then().statusCode(200);
        assertRefused(bucket, marker, lastModified);
    }

    @Test
    void theNullDeleteMarkerOfASuspendedBucketIsNamedNull() {
        String bucket = createBucket("marker-read-null", "Suspended");
        given().body("data").when().put("/" + bucket + "/key").then().statusCode(200);
        deleteKey(bucket);

        given().when().get("/" + bucket + "/key").then().statusCode(404)
                .header("x-amz-delete-marker", "true")
                .header("x-amz-version-id", "null");
        assertRefused(bucket, "null", markerLastModified(bucket));
    }

    private static void assertRefused(String bucket, String versionId, String lastModified) {
        String path = "/" + bucket + "/key?versionId=" + versionId;
        given().when().get(path).then().statusCode(405)
                .header("x-amz-delete-marker", "true")
                .header("x-amz-version-id", versionId)
                .header("Last-Modified", lastModified)
                .header("Allow", "DELETE")
                .body(allOf(containsString("<Code>MethodNotAllowed</Code>"),
                        containsString("<Method>GET</Method>"),
                        containsString("<ResourceType>DeleteMarker</ResourceType>")));
        given().when().head(path).then().statusCode(405)
                .header("x-amz-delete-marker", "true")
                .header("x-amz-version-id", versionId)
                .header("Last-Modified", lastModified)
                .header("Allow", "DELETE");
        given().when().get(path + "&partNumber=1").then().statusCode(405)
                .header("x-amz-version-id", versionId);
    }

    private static String createBucket(String bucket, String versioning) {
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("<VersioningConfiguration><Status>" + versioning + "</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        return bucket;
    }

    private static String deleteKey(String bucket) {
        return given().when().delete("/" + bucket + "/key").then().statusCode(204)
                .header("x-amz-delete-marker", "true")
                .extract().header("x-amz-version-id");
    }

    private static String markerLastModified(String bucket) {
        String listed = given().queryParam("versions", "").queryParam("prefix", "key").when().get("/" + bucket)
                .then().statusCode(200)
                .extract().xmlPath().getString("ListVersionsResult.DeleteMarker.LastModified");
        return HTTP_DATE.format(Instant.parse(listed));
    }
}
