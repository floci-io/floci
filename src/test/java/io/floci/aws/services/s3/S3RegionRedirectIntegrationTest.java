package io.floci.aws.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A request signed for a Region other than its bucket's is answered with S3's 301 PermanentRedirect and the
 * bucket's Region in {@code x-amz-bucket-region} (botocore's {@code S3RegionRedirectorv2} and its functional
 * fixture in {@code tests/functional/test_s3.py}; the Java SDK's {@code CrossRegionUtils}). RestAssured is told not
 * to follow the 301 itself, so the test sees the redirect S3 sends.
 */
@QuarkusTest
class S3RegionRedirectIntegrationTest {

    private static final String US_EAST_1_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260325/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=test";
    private static final String EU_WEST_1_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260325/eu-west-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=test";

    @Test
    void requestSignedForAnotherRegionIsPermanentlyRedirected() {
        String bucket = "redirect-us-east-1-bucket";
        given().header("Authorization", US_EAST_1_AUTH).when().put("/" + bucket).then().statusCode(200);
        try {
            given().redirects().follow(false).header("Authorization", EU_WEST_1_AUTH)
            .when()
                .get("/" + bucket)
            .then()
                .statusCode(301)
                .header("x-amz-bucket-region", equalTo("us-east-1"))
                .body(containsString("<Code>PermanentRedirect</Code>"))
                .body(containsString("<Bucket>" + bucket + "</Bucket>"))
                .body(containsString("<Endpoint>" + bucket + ".s3.us-east-1.amazonaws.com</Endpoint>"));

            given().redirects().follow(false).header("Authorization", EU_WEST_1_AUTH)
                .body("data")
            .when()
                .put("/" + bucket + "/key.txt")
            .then()
                .statusCode(301)
                .header("x-amz-bucket-region", equalTo("us-east-1"));

            given().header("Authorization", US_EAST_1_AUTH)
            .when()
                .get("/" + bucket)
            .then()
                .statusCode(200)
                .body(containsString("<Name>" + bucket + "</Name>"));
        } finally {
            given().header("Authorization", US_EAST_1_AUTH).when().delete("/" + bucket).then().statusCode(204);
        }
    }

    @Test
    void headBucketSignedForAnotherRegionGetsTheRegionWithoutABody() {
        String bucket = "redirect-head-bucket";
        given().header("Authorization", US_EAST_1_AUTH).when().put("/" + bucket).then().statusCode(200);
        try {
            given().redirects().follow(false).header("Authorization", EU_WEST_1_AUTH)
            .when()
                .head("/" + bucket)
            .then()
                .statusCode(301)
                .header("x-amz-bucket-region", equalTo("us-east-1"))
                .body(emptyString());
        } finally {
            given().header("Authorization", US_EAST_1_AUTH).when().delete("/" + bucket).then().statusCode(204);
        }
    }

    @Test
    void bucketInARegionalHomeRedirectsAUsEast1Signer() {
        String bucket = "redirect-eu-west-1-bucket";
        given().header("Authorization", EU_WEST_1_AUTH)
            .contentType("application/xml")
            .body("<CreateBucketConfiguration><LocationConstraint>eu-west-1</LocationConstraint></CreateBucketConfiguration>")
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);
        try {
            given().redirects().follow(false).header("Authorization", US_EAST_1_AUTH)
            .when()
                .get("/" + bucket)
            .then()
                .statusCode(301)
                .header("x-amz-bucket-region", equalTo("eu-west-1"))
                .body(containsString("<Endpoint>" + bucket + ".s3.eu-west-1.amazonaws.com</Endpoint>"));
        } finally {
            given().header("Authorization", EU_WEST_1_AUTH).when().delete("/" + bucket).then().statusCode(204);
        }
    }

    @Test
    void locationAndListBucketsAreServedFromEveryRegion() {
        String bucket = "redirect-exempt-bucket";
        given().header("Authorization", US_EAST_1_AUTH).when().put("/" + bucket).then().statusCode(200);
        try {
            given().header("Authorization", EU_WEST_1_AUTH)
            .when()
                .get("/" + bucket + "?location")
            .then()
                .statusCode(200)
                .body(containsString("LocationConstraint"));

            given().header("Authorization", EU_WEST_1_AUTH)
            .when()
                .get("/")
            .then()
                .statusCode(200)
                .body(containsString("<Name>" + bucket + "</Name>"));
        } finally {
            given().header("Authorization", US_EAST_1_AUTH).when().delete("/" + bucket).then().statusCode(204);
        }
    }

    @Test
    void unsignedRequestIsServedWhateverTheBucketsRegion() {
        String bucket = "redirect-unsigned-bucket";
        given().header("Authorization", EU_WEST_1_AUTH)
            .contentType("application/xml")
            .body("<CreateBucketConfiguration><LocationConstraint>eu-west-1</LocationConstraint></CreateBucketConfiguration>")
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);
        try {
            given().when().get("/" + bucket).then().statusCode(200);
        } finally {
            given().when().delete("/" + bucket).then().statusCode(204);
        }
    }

    @Test
    void sdkClientWithCrossRegionAccessFollowsTheRedirect() {
        String bucket = "redirect-sdk-bucket";
        try (S3Client home = client(Region.US_EAST_1, false);
             S3Client wrongRegion = client(Region.EU_WEST_1, false);
             S3Client crossRegion = client(Region.EU_WEST_1, true)) {
            home.createBucket(request -> request.bucket(bucket));
            try {
                home.putObject(request -> request.bucket(bucket).key("hello.txt"), RequestBody.fromString("hello"));

                S3Exception redirected = assertThrows(S3Exception.class,
                        () -> wrongRegion.getObjectAsBytes(request -> request.bucket(bucket).key("hello.txt")));
                assertEquals(301, redirected.statusCode());
                assertEquals("us-east-1",
                        redirected.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-bucket-region").orElse(null));

                assertEquals("hello",
                        crossRegion.getObjectAsBytes(request -> request.bucket(bucket).key("hello.txt")).asUtf8String());
            } finally {
                home.deleteObject(request -> request.bucket(bucket).key("hello.txt"));
                home.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    private static S3Client client(Region region, boolean crossRegionAccess) {
        return S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(true)
                .region(region)
                .crossRegionAccessEnabled(crossRegionAccess)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
