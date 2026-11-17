package io.floci.aws.testing;

import io.restassured.RestAssured;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

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

    /**
     * What a Spark or MapReduce job wrote under {@code prefix} in {@code bucket}: its part files, in key order, one after
     * the other.
     */
    public static String partFiles(S3Client s3, String bucket, String prefix) {
        StringBuilder text = new StringBuilder();
        for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket).prefix(prefix)).contents()) {
            if (object.key().substring(prefix.length()).startsWith("part-")) {
                text.append(s3.getObjectAsBytes(request -> request.bucket(bucket).key(object.key())).asUtf8String());
            }
        }
        return text.toString();
    }

    /** The object at {@code key} in {@code bucket}, gunzipped, as a test reads back a log Floci wrote there. */
    public static String gunzipped(S3Client s3, String bucket, String key) {
        try (GZIPInputStream in = new GZIPInputStream(s3.getObject(request -> request.bucket(bucket).key(key)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
