package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class S3MultipartListingIntegrationTest {
    @Inject
    S3Service service;

    @Test
    void listsFilteredUploadsAcrossPagesIncludingRepeatedKeys() {
        String bucket = "multipart-listing-regression";
        given().put("/" + bucket).then().statusCode(200);
        String first = initiate(bucket, "a.txt");
        String second = initiate(bucket, "a.txt");
        initiate(bucket, "b.txt");

        given().queryParam("uploads", "").queryParam("prefix", "a")
                .get("/" + bucket).then().statusCode(200)
                .body(containsString("<UploadId>" + first + "</UploadId>"))
                .body(containsString("<UploadId>" + second + "</UploadId>"))
                .body(not(containsString("<Key>b.txt</Key>")));

        Set<String> ids = new HashSet<>();
        String keyMarker = null;
        String uploadMarker = null;
        for (int page = 0; page < 3; page++) {
            RequestSpecification request = given()
                    .queryParam("uploads", "").queryParam("max-uploads", "1");
            if (keyMarker != null) {
                request.queryParam("key-marker", keyMarker).queryParam("upload-id-marker", uploadMarker);
            }
            Response response = request.get("/" + bucket);
            response.then().statusCode(200).body("ListMultipartUploadsResult.Upload.size()", is(1));
            ids.add(response.xmlPath().getString("ListMultipartUploadsResult.Upload[0].UploadId"));
            boolean truncated = response.xmlPath().getBoolean("ListMultipartUploadsResult.IsTruncated");
            if (page < 2) {
                assertTrue(truncated);
                keyMarker = response.xmlPath().getString("ListMultipartUploadsResult.NextKeyMarker");
                uploadMarker = response.xmlPath().getString("ListMultipartUploadsResult.NextUploadIdMarker");
            } else {
                assertFalse(truncated);
            }
        }
        assertEquals(3, ids.size());
        assertTrue(ids.contains(first));
        assertTrue(ids.contains(second));
    }

    private String initiate(String bucket, String key) {
        return given().post("/" + bucket + "/" + key + "?uploads").then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void continuesAfterRemovedMarkerWithoutHidingOrRepeatingUploads(boolean complete) {
        String bucket = "multipart-listing-removed-marker-" + complete;
        given().put("/" + bucket).then().statusCode(200);
        List<String> uploadIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            uploadIds.add(initiate(bucket, "a.txt"));
        }
        uploadIds.sort(String::compareTo);
        String later = uploadIds.get(0);
        String earlier = uploadIds.get(1);
        String marker = uploadIds.get(2);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        service.getMultipartUpload(bucket, "a.txt", earlier).setInitiated(start);
        service.getMultipartUpload(bucket, "a.txt", marker).setInitiated(start.plusSeconds(1));
        service.getMultipartUpload(bucket, "a.txt", later).setInitiated(start.plusSeconds(2));
        given().queryParam("uploads", "").queryParam("max-uploads", "2")
                .get("/" + bucket).then().statusCode(200)
                .body("ListMultipartUploadsResult.NextUploadIdMarker", is(marker));
        if (complete) {
            String etag = given().body("data").put("/" + bucket + "/a.txt?partNumber=1&uploadId=" + marker)
                    .then().statusCode(200).extract().header("ETag");
            given().body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + etag
                    + "</ETag></Part></CompleteMultipartUpload>")
                    .post("/" + bucket + "/a.txt?uploadId=" + marker).then().statusCode(200);
        } else {
            given().delete("/" + bucket + "/a.txt?uploadId=" + marker).then().statusCode(204);
        }
        given().queryParam("uploads", "").queryParam("key-marker", "a.txt")
                .queryParam("upload-id-marker", marker).get("/" + bucket).then().statusCode(200)
                .body("ListMultipartUploadsResult.Upload.size()", is(1))
                .body("ListMultipartUploadsResult.Upload.UploadId", is(later))
                .body(not(containsString(earlier)))
                .body("ListMultipartUploadsResult.IsTruncated", is("false"));
    }

    @Test
    void delimiterAndMarkersPageWithoutRepeatingCommonPrefixes() {
        String bucket = "multipart-listing-prefixes";
        given().put("/" + bucket).then().statusCode(200);
        initiate(bucket, "dir/a.txt");
        initiate(bucket, "dir/b.txt");
        initiate(bucket, "other.txt");
        Response first = given().queryParam("uploads", "").queryParam("delimiter", "/")
                .queryParam("max-uploads", "1").get("/" + bucket);
        first.then().statusCode(200).body(containsString("<Prefix>dir/</Prefix>"))
                .body(containsString("<IsTruncated>true</IsTruncated>"));
        String nextKey = first.xmlPath().getString("ListMultipartUploadsResult.NextKeyMarker");
        given().queryParam("uploads", "").queryParam("delimiter", "/")
                .queryParam("max-uploads", "1").queryParam("key-marker", nextKey)
                .get("/" + bucket).then().statusCode(200)
                .body(not(containsString("<Prefix>dir/</Prefix>")))
                .body(containsString("<Key>other.txt</Key>"))
                .body(containsString("<IsTruncated>false</IsTruncated>"));
    }

    @Test
    void rejectsInvalidPageSizeAndCapsTheMaximum() {
        String bucket = "multipart-listing-max";
        given().put("/" + bucket).then().statusCode(200);
        given().queryParam("uploads", "").queryParam("max-uploads", "-1")
                .get("/" + bucket).then().statusCode(400).body(containsString("InvalidArgument"));
        given().queryParam("uploads", "").queryParam("max-uploads", "1001")
                .get("/" + bucket).then().statusCode(200).body(containsString("<MaxUploads>1000</MaxUploads>"));
    }

    @Test
    void sdkPaginatorReturnsEveryMatchingUploadOnce() {
        String bucket = "multipart-listing-sdk";
        try (S3Client client = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "test", Region.US_EAST_1)) {
            client.createBucket(request -> request.bucket(bucket));
            String first = client.createMultipartUpload(request -> request.bucket(bucket).key("a.txt")).uploadId();
            String second = client.createMultipartUpload(request -> request.bucket(bucket).key("a.txt")).uploadId();
            client.createMultipartUpload(request -> request.bucket(bucket).key("b.txt"));
            Set<String> ids = new HashSet<>();
            List<String> orderedIds = new ArrayList<>();
            ListMultipartUploadsRequest request = ListMultipartUploadsRequest.builder()
                    .bucket(bucket).prefix("a").maxUploads(1).build();
            for (ListMultipartUploadsResponse page : client.listMultipartUploadsPaginator(request)) {
                for (MultipartUpload upload : page.uploads()) {
                    assertTrue(ids.add(upload.uploadId()));
                    orderedIds.add(upload.uploadId());
                }
            }
            assertEquals(Set.of(first, second), ids);
            assertEquals(List.of(first, second), orderedIds);
            client.createMultipartUpload(requestValue -> requestValue.bucket(bucket).key("\uE000"));
            client.createMultipartUpload(requestValue -> requestValue.bucket(bucket).key("\uD800\uDC00"));
            List<String> keys = new ArrayList<>();
            ListMultipartUploadsRequest all = ListMultipartUploadsRequest.builder().bucket(bucket).maxUploads(1).build();
            for (ListMultipartUploadsResponse page : client.listMultipartUploadsPaginator(all)) {
                for (MultipartUpload upload : page.uploads()) {
                    keys.add(upload.key());
                }
            }
            assertEquals(List.of("a.txt", "a.txt", "b.txt", "\uE000", "\uD800\uDC00"), keys);
        }
    }
}
