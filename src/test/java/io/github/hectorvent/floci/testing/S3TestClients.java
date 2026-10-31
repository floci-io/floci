package io.github.hectorvent.floci.testing;

import io.restassured.RestAssured;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/** AWS SDK S3 clients pointed at the Floci instance a {@code @QuarkusTest} runs. */
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
}
