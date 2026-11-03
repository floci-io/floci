package io.github.hectorvent.floci.testing;

import io.restassured.RestAssured;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/** AWS SDK S3 clients pointed at the Floci instance a {@code @QuarkusTest} runs, and what a test does with them. */
public final class S3TestClients {

    private S3TestClients() {
    }

    /** A path-style client in us-east-1 for account {@code 741624100001}. */
    public static S3Client pathStyle() {
        return S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("741624100001", "test")))
                .build();
    }

    /** Empties {@code bucket}, every page of it, and deletes it, as a test cleans up the bucket it wrote to. */
    public static void deleteBucket(S3Client s3, String bucket) {
        s3.listObjectsV2Paginator(request -> request.bucket(bucket)).contents()
                .forEach(object -> s3.deleteObject(request -> request.bucket(bucket).key(object.key())));
        s3.deleteBucket(request -> request.bucket(bucket));
    }
}
