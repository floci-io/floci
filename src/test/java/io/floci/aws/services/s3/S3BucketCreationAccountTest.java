package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.s3.model.Bucket;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class S3BucketCreationAccountTest {

    private static final String ACCOUNT_A = "000000000001";
    private static final String ACCOUNT_B = "000000000002";
    private final ThreadLocal<String> caller = ThreadLocal.withInitial(() -> ACCOUNT_A);
    private AccountAwareStorageBackend<Bucket> buckets;
    private AccountAwareStorageBackend<S3Object> objects;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RequestContext request = mock(RequestContext.class);
        when(request.getAccountId()).thenAnswer(invocation -> caller.get());
        Instance<RequestContext> context = mock(Instance.class);
        when(context.get()).thenReturn(request);
        buckets = spy(new AccountAwareStorageBackend<>(new InMemoryStorage<>(), context, ACCOUNT_A));
        objects = new AccountAwareStorageBackend<>(new InMemoryStorage<>(), context, ACCOUNT_A);
    }

    private S3Service service(boolean global) {
        return new S3Service(buckets, objects, Path.of("s3-account-create-test"), true, global);
    }

    @Test
    void globalNamespaceRejectsForeignNamesWithoutCreatingAShadowBucket() {
        S3Service service = service(true);
        Bucket owner = service.createBucket("unique-bucket", "us-east-2");
        caller.set(ACCOUNT_B);
        AwsException rejected = assertThrows(AwsException.class,
                () -> service.createBucket("unique-bucket", "us-east-1"));
        assertEquals("BucketAlreadyExists", rejected.getErrorCode());
        assertEquals(409, rejected.getHttpStatus());
        assertEquals("The requested bucket name is not available. The bucket namespace is shared by all users "
                + "of the system. Please select a different name and try again.", rejected.getMessage());
        assertTrue(service.listBuckets().isEmpty());
        assertTrue(buckets.getForAccount(ACCOUNT_B, "unique-bucket").isEmpty());
        assertSame(owner, buckets.getForAccount(ACCOUNT_A, "unique-bucket").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"awslambda-us-east-1-tasks", "floci-internal-redshift-spectrum-scratch",
            "floci-firehose-staging", "floci-firehose-results", "floci-cur-staging"})
    void internalBucketsKeepTheirNamesAndObjectsInEachAccount(String name) {
        S3Service service = service(true);
        service.createInternalBucket(name, "us-east-1");
        service.putObject(name, "function.zip", "owner".getBytes(StandardCharsets.UTF_8),
                "application/zip", Map.of());
        caller.set(ACCOUNT_B);
        assertEquals("BucketAlreadyExists", assertThrows(AwsException.class,
                () -> service.createBucket(name, "us-east-1")).getErrorCode());
        service.createInternalBucket(name, "us-east-1");
        service.putObject(name, "function.zip", "second".getBytes(StandardCharsets.UTF_8),
                "application/zip", Map.of());
        assertEquals(name, buckets.getForAccount(ACCOUNT_A, name).orElseThrow().getName());
        assertEquals(name, buckets.getForAccount(ACCOUNT_B, name).orElseThrow().getName());
        assertTrue(objects.getForAccount(ACCOUNT_A, name + "/function.zip").isPresent());
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8),
                service.getObject(name, "function.zip").getData());
        caller.set(ACCOUNT_A);
        assertArrayEquals("owner".getBytes(StandardCharsets.UTF_8),
                service.getObject(name, "function.zip").getData());
    }

    @Test
    void internalCreationReportsExistingUserBucketsEvenInTheDefaultRegion() {
        S3Service service = service(true);
        Bucket userBucket = service.createBucket("floci-redshift-spectrum", "us-east-1");
        assertEquals("BucketAlreadyOwnedByYou", assertThrows(AwsException.class,
                () -> service.createInternalBucket("floci-redshift-spectrum", "us-east-1")).getErrorCode());
        assertSame(userBucket, buckets.getForAccount(ACCOUNT_A, "floci-redshift-spectrum").orElseThrow());
        assertTrue(service.getBucketTagging("floci-redshift-spectrum").isEmpty());
    }

    @Test
    void defaultNamespaceStillAllowsTheSameNameInDifferentAccounts() {
        S3Service service = service(false);
        service.createBucket("isolated-bucket", "us-east-2");
        caller.set(ACCOUNT_B);
        service.createBucket("isolated-bucket", "us-east-2");
        assertTrue(buckets.getForAccount(ACCOUNT_A, "isolated-bucket").isPresent());
        assertTrue(buckets.getForAccount(ACCOUNT_B, "isolated-bucket").isPresent());
    }

    @Test
    void sameOwnerRetainsTheDefaultRegionExceptionAndOtherRegionConflict() {
        S3Service service = service(true);
        Bucket east = service.createBucket("default-region-bucket", "us-east-1");
        assertSame(east, service.createBucket("default-region-bucket", "us-east-1"));
        Bucket regional = service.createBucket("regional-bucket", "us-east-2");
        AwsException rejected = assertThrows(AwsException.class,
                () -> service.createBucket("regional-bucket", "us-east-2"));
        assertEquals("BucketAlreadyOwnedByYou", rejected.getErrorCode());
        assertSame(regional, buckets.getForAccount(ACCOUNT_A, "regional-bucket").orElseThrow());
    }

    @Test
    void deletingTheOwnerBucketAllowsAnotherAccountToCreateTheName() {
        S3Service service = service(true);
        service.createBucket("reusable-bucket", "us-east-2");
        service.deleteBucket("reusable-bucket");
        caller.set(ACCOUNT_B);
        service.createBucket("reusable-bucket", "us-east-2");
        assertTrue(buckets.getForAccount(ACCOUNT_A, "reusable-bucket").isEmpty());
        assertTrue(buckets.getForAccount(ACCOUNT_B, "reusable-bucket").isPresent());
    }

    @Test
    void globalCreationKeepsTheCollisionCheckAndPublicationInOneCriticalSection() throws Exception {
        S3Service service = service(true);
        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (ACCOUNT_A.equals(caller.get())) {
                publicationEntered.countDown();
                assertTrue(publish.await(5, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(buckets).put(eq("contended-bucket"), any(Bucket.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Bucket> first = executor.submit(() -> service.createBucket("contended-bucket", "us-east-2"));
            assertTrue(publicationEntered.await(5, TimeUnit.SECONDS));
            Future<AwsException> second = executor.submit(() -> {
                caller.set(ACCOUNT_B);
                contenderStarted.countDown();
                return assertThrows(AwsException.class,
                        () -> service.createBucket("contended-bucket", "us-east-2"));
            });
            assertTrue(contenderStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            publish.countDown();
            assertEquals("contended-bucket", first.get(5, TimeUnit.SECONDS).getName());
            assertEquals("BucketAlreadyExists", second.get(5, TimeUnit.SECONDS).getErrorCode());
            assertTrue(buckets.getForAccount(ACCOUNT_A, "contended-bucket").isPresent());
            assertTrue(buckets.getForAccount(ACCOUNT_B, "contended-bucket").isEmpty());
        } finally {
            publish.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
