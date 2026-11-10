package io.floci.aws.services.s3;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ListBucketsPaginationIntegrationTest {

    // Buckets are listed per account, so an account no other test uses holds exactly these buckets.
    private static final String ACCOUNT = "444455556666";
    private static final String OTHER_ACCOUNT = "444455557777";
    private static final String PREFIX = "s3-page-" + UUID.randomUUID().toString().substring(0, 8);
    private static final List<String> BUCKETS = List.of(PREFIX + "-a", PREFIX + "-b", PREFIX + "-c");

    private static S3Client client(String account) {
        return AwsTestClients.create(S3Client.builder().forcePathStyle(true), account, Region.US_EAST_1);
    }

    @Test
    @Order(1)
    void createBuckets() {
        try (S3Client s3 = client(ACCOUNT)) {
            for (String bucket : BUCKETS) {
                s3.createBucket(r -> r.bucket(bucket));
            }
        }
    }

    @Test
    @Order(2)
    void listBucketsPagesOneBucketAtATimeWithATokenOnEveryPageButTheLast() {
        try (S3Client s3 = client(ACCOUNT)) {
            List<String> seen = new ArrayList<>();
            List<Boolean> tokens = new ArrayList<>();
            String token = null;
            do {
                String next = token;
                ListBucketsResponse page = s3.listBuckets(r -> r.maxBuckets(1).continuationToken(next));
                assertEquals(1, page.buckets().size());
                page.buckets().stream().map(Bucket::name).forEach(seen::add);
                token = page.continuationToken();
                tokens.add(token != null);
            } while (token != null && seen.size() < 10);

            assertEquals(BUCKETS, seen);
            assertEquals(List.of(true, true, false), tokens);
        }
    }

    @Test
    @Order(3)
    void listBucketsWithoutMaxBucketsReturnsEveryBucketWithoutAToken() {
        try (S3Client s3 = client(ACCOUNT)) {
            ListBucketsResponse page = s3.listBuckets();
            assertEquals(BUCKETS, page.buckets().stream().map(Bucket::name).toList());
            assertNull(page.continuationToken());
        }
    }

    @ParameterizedTest
    @Order(4)
    @ValueSource(ints = {0, 10001})
    void listBucketsRefusesAMaxBucketsOutsideOneToTenThousand(int maxBuckets) {
        try (S3Client s3 = client(ACCOUNT)) {
            S3Exception e = assertThrows(S3Exception.class, () -> s3.listBuckets(r -> r.maxBuckets(maxBuckets)));
            assertEquals(400, e.statusCode());
            assertEquals("InvalidArgument", e.awsErrorDetails().errorCode());
        }
    }

    @Test
    @Order(5)
    void listBucketsRefusesAnUnreadableToken() {
        try (S3Client s3 = client(ACCOUNT)) {
            S3Exception e = assertThrows(S3Exception.class,
                    () -> s3.listBuckets(r -> r.continuationToken("not-a-token")));
            assertEquals(400, e.statusCode());
            assertEquals("InvalidArgument", e.awsErrorDetails().errorCode());
        }
    }

    @Test
    @Order(6)
    void listBucketsRefusesATokenIssuedToAnotherAccount() {
        String token;
        try (S3Client s3 = client(ACCOUNT)) {
            token = s3.listBuckets(r -> r.maxBuckets(1)).continuationToken();
        }
        try (S3Client other = client(OTHER_ACCOUNT)) {
            S3Exception e = assertThrows(S3Exception.class, () -> other.listBuckets(r -> r.continuationToken(token)));
            assertEquals("InvalidArgument", e.awsErrorDetails().errorCode());
        }
    }

    @Test
    @Order(9)
    void deleteBuckets() {
        try (S3Client s3 = client(ACCOUNT)) {
            for (String bucket : BUCKETS) {
                s3.deleteBucket(r -> r.bucket(bucket));
            }
        }
    }
}
