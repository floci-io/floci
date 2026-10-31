package io.github.hectorvent.floci.testing;

import io.restassured.RestAssured;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;

import java.net.URI;

/** Synchronous AWS SDK clients pointed at the Floci instance a {@code @QuarkusTest} runs. */
public final class AwsTestClients {

    private AwsTestClients() {
    }

    public static <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C> C create(
            B builder, String account, Region region) {
        return create(builder, account, region, UrlConnectionHttpClient.builder());
    }

    /** Selects a transport explicitly for operations requiring methods such as PATCH. */
    public static <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C> C create(
            B builder, String account, Region region, SdkHttpClient.Builder<?> httpClientBuilder) {
        return builder.endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(httpClientBuilder).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }
}
