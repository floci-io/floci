package io.github.hectorvent.floci.testing;

import io.restassured.RestAssured;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;

import java.net.URI;

public final class IotDataRequests {
    private IotDataRequests() {}

    /** An IoT Data client on the test instance in us-east-1, signing with {@code accessKey}, which Floci reads as the account when it is one. */
    public static IotDataPlaneClient iotDataClient(String accessKey) {
        return IotDataPlaneClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, "test")))
                .build();
    }
}
