package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S3 Pre-signed URLs")
class S3PresignTest {

    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

    private static S3Client s3;
    private static S3Presigner presigner;
    private static final String BUCKET = TestFixtures.uniqueName("sdk-s3-presign");
    private static final String KEY = "presigned-tagged-put.txt";

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        presigner = S3Presigner.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();

        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());
            } catch (Exception ignored) {
            }
            try {
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
            } catch (Exception ignored) {
            }
            s3.close();
        }
        if (presigner != null) {
            presigner.close();
        }
    }

    @Test
    @DisplayName("#932 presigned PUT with tagging signed header uploads successfully")
    void presignedPutWithTaggingSignedHeaderUploadsSuccessfully() throws Exception {
        String content = "uploaded via presigned PUT with tagging signed header";

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(BUCKET)
                .key(KEY)
                .tagging("tag=test")
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(10))
                        .putObjectRequest(putObjectRequest)
                        .build()
        );

        String rawQuery = presigned.url().toURI().getRawQuery();
        assertThat(rawQuery).contains("X-Amz-SignedHeaders=");
        assertThat(rawQuery).contains("x-amz-tagging");
        assertThat(presigned.httpRequest().headers()).containsKey("x-amz-tagging");

        HttpURLConnection connection = (HttpURLConnection) presigned.url().openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);

        for (Map.Entry<String, List<String>> entry : presigned.httpRequest().headers().entrySet()) {
            for (String value : entry.getValue()) {
                connection.addRequestProperty(entry.getKey(), value);
            }
        }

        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(body);
        }

        int status = connection.getResponseCode();
        String responseBody;
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            responseBody = "";
        } else {
            try (InputStream is = stream) {
                responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        assertThat(status)
                .withFailMessage("Expected presigned PUT with x-amz-tagging signed header to succeed, got %s: %s", status, responseBody)
                .isEqualTo(200);

        String downloaded = new String(
                s3.getObject(GetObjectRequest.builder().bucket(BUCKET).key(KEY).build()).readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertThat(downloaded).isEqualTo(content);
    }
}
