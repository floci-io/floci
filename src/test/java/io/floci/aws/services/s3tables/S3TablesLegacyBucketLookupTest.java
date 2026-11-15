package io.floci.aws.services.s3tables;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3tables.model.TableBucket;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3TablesLegacyBucketLookupTest {
    private static final String OWNER = "000000000000";
    private static final String OTHER = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String KEY = REGION + "::legacy-bucket";
    private static final String ARN = "arn:aws:s3tables:" + REGION + ":" + OWNER + ":bucket/legacy-bucket";
    private static final Map<String, String> CONFIGURATION = Map.of("sseAlgorithm", "aws:kms", "kmsKeyArn", "example-key");

    private final InMemoryStorage<String, TableBucket> raw = new InMemoryStorage<>();
    private final RequestContext context = mock(RequestContext.class);
    private S3TablesService service;
    private TableBucket bucket;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        when(context.getAccountId()).thenReturn(OWNER);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<TableBucket>create(anyString(), anyString(), any()))
                .thenReturn(new AccountAwareStorageBackend<>(raw, contexts, OWNER));
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenAnswer(invocation -> context.getAccountId());
        service = new S3TablesService(factory, resolver, mock(S3Service.class));
        bucket = new TableBucket("legacy-bucket", ARN, "2026-01-01T00:00:00Z", OWNER,
                CONFIGURATION, null, Map.of());
        raw.put(KEY, bucket);
    }

    @Test
    void foreignAccountCannotReadOrMigrateTheLegacyBucket() {
        when(context.getAccountId()).thenReturn(OTHER);

        AwsException failure = assertThrows(AwsException.class, () -> service.getTableBucket(ARN, REGION));

        assertEquals("NotFoundException", failure.getErrorCode());
        assertEquals(Set.of(KEY), raw.keys());
        assertSame(bucket, raw.get(KEY).orElseThrow());
    }

    @Test
    void wrongArnDoesNotMigrateTheOwnersLegacyBucket() {
        String wrongArn = ARN.replace(OWNER, OTHER);

        AwsException failure = assertThrows(AwsException.class, () -> service.getTableBucket(wrongArn, REGION));

        assertEquals("NotFoundException", failure.getErrorCode());
        assertEquals(Set.of(KEY), raw.keys());
        assertSame(bucket, raw.get(KEY).orElseThrow());
    }

    @Test
    void foreignAccountCreatesItsOwnBucketWithoutMovingLegacyBucket() {
        when(context.getAccountId()).thenReturn(OTHER);

        TableBucket created = service.createTableBucket("legacy-bucket", null, null, Map.of(), REGION);

        assertEquals(ARN.replace(OWNER, OTHER), created.getArn());
        assertEquals(Set.of(KEY, OTHER + "/" + KEY), raw.keys());
        assertSame(bucket, raw.get(KEY).orElseThrow());
        assertSame(created, service.getTableBucket(created.getArn(), REGION));
        AwsException failure = assertThrows(AwsException.class, () -> service.getTableBucket(ARN, REGION));
        assertEquals("NotFoundException", failure.getErrorCode());

        when(context.getAccountId()).thenReturn(OWNER);

        assertSame(bucket, service.getTableBucket(ARN, REGION));
        assertEquals(Set.of(OWNER + "/" + KEY, OTHER + "/" + KEY), raw.keys());
        assertSame(created, raw.get(OTHER + "/" + KEY).orElseThrow());
    }

    @Test
    void ownerCannotCreateDuplicateLegacyBucket() {
        AwsException failure = assertThrows(AwsException.class,
                () -> service.createTableBucket("legacy-bucket", null, null, Map.of(), REGION));

        assertEquals("ConflictException", failure.getErrorCode());
        assertEquals(Set.of(OWNER + "/" + KEY), raw.keys());
        assertSame(bucket, service.getTableBucket(ARN, REGION));
    }

    @Test
    void ownerReadsAndDeletesTheLegacyBucket() {
        assertSame(bucket, service.getTableBucket(ARN, REGION));
        assertEquals(Set.of(OWNER + "/" + KEY), raw.keys());

        service.deleteTableBucket(ARN, REGION);

        assertTrue(raw.isEmpty());
    }
}
