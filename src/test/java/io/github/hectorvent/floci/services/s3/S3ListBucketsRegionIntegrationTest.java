package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class S3ListBucketsRegionIntegrationTest {

    @Inject
    S3Service service;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void legacyBucketsWithoutARecordedRegionUseTheConfiguredDefault(String storedRegion) {
        String bucket = "s3-region-legacy-6241-" + UUID.randomUUID();
        service.createBucket(bucket, storedRegion);
        try {
            given().queryParam("max-buckets", "1000").get("/").then().statusCode(200)
                    .body("ListAllMyBucketsResult.Buckets.Bucket.find { it.Name == '" + bucket + "' }.BucketRegion",
                            equalTo("us-east-1"));
        } finally {
            service.deleteBucket(bucket);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-buckets", "prefix", "continuation-token", "bucket-region", "none"})
    void sdkReadsBucketRegionsOnlyWhenAListParameterIsPresent(String parameter) {
        String prefix = "s3-region-6241-" + UUID.randomUUID();
        String defaultBucket = prefix + "-us";
        String explicitBucket = prefix + "-eu";
        try (S3Client client = client()) {
            client.createBucket(request -> request.bucket(defaultBucket));
            try {
                client.createBucket(request -> request.bucket(explicitBucket)
                        .createBucketConfiguration(configuration -> configuration.locationConstraint("eu-central-1")));
                try {
                    ListBucketsResponse response = switch (parameter) {
                        case "max-buckets" -> client.listBuckets(request -> request.maxBuckets(1000));
                        case "prefix" -> client.listBuckets(request -> request.prefix(prefix));
                        case "continuation-token" -> client.listBuckets(request -> request.continuationToken(""));
                        case "bucket-region" -> client.listBuckets(request -> request.bucketRegion("us-east-1"));
                        case "none" -> client.listBuckets();
                        default -> throw new IllegalArgumentException(parameter);
                    };
                    Map<String, String> expected = "bucket-region".equals(parameter)
                            ? Map.of(defaultBucket, "us-east-1")
                            : Map.of(defaultBucket, "us-east-1", explicitBucket, "eu-central-1");
                    for (Map.Entry<String, String> entry : expected.entrySet()) {
                        Bucket bucket = response.buckets().stream().filter(value -> entry.getKey().equals(value.name()))
                                .findFirst().orElseThrow();
                        if ("none".equals(parameter)) {
                            assertNull(bucket.bucketRegion());
                        } else {
                            assertEquals(entry.getValue(), bucket.bucketRegion());
                        }
                        assertNull(bucket.bucketArn());
                    }
                } finally {
                    client.deleteBucket(request -> request.bucket(explicitBucket));
                }
            } finally {
                client.deleteBucket(request -> request.bucket(defaultBucket));
            }
        }
    }

    @Test
    void unrelatedQueryParametersDoNotAddRegionOrDirectoryBucketArn() {
        String bucket = "s3-region-control-6241-" + UUID.randomUUID();
        given().put("/" + bucket).then().statusCode(200);
        try {
            given().queryParam("unrelated", "1").get("/").then().statusCode(200)
                    .body(containsString("<Name>" + bucket + "</Name>"))
                    .body(not(containsString("<BucketRegion>")))
                    .body(not(containsString("<BucketArn>")));
        } finally {
            given().delete("/" + bucket).then().statusCode(204);
        }
    }

    private static S3Client client() {
        return S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("741624100001", "test")))
                .build();
    }
}
