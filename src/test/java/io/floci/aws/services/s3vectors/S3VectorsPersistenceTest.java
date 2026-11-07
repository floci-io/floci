package io.floci.aws.services.s3vectors;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.HybridStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.s3vectors.model.VectorBucket;
import io.floci.aws.services.s3vectors.model.VectorIndex;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3VectorsPersistenceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String KEY = ACCOUNT + "/" + REGION + "::legacy";
    private static final Map<String, String> DEFAULT_ENCRYPTION = Map.of("sseType", "AES256");
    private static final Instant KNOWN_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant MIGRATION_TIME = Instant.parse("2026-02-01T00:00:00Z");

    @ParameterizedTest
    @CsvSource({"persistent,false", "persistent,true", "hybrid,false", "hybrid,true", "wal,false", "wal,true"})
    void legacyMetadataIsSavedOnceAndSurvivesReload(String mode, boolean listFirst, @TempDir Path directory)
            throws Exception {
        Path snapshot = directory.resolve("s3vectors.json");
        Files.writeString(snapshot, """
                {
                  "000000000000/us-east-1::legacy": {
                    "vectorBucketName": "legacy",
                    "encryptionConfiguration": null,
                    "indexes": {
                      "old": {"indexName": "old", "dimension": 1, "dataType": "float32", "distanceMetric": "cosine"},
                      "configured": {
                        "indexName": "configured",
                        "creationTime": "2026-01-01T00:00:00Z",
                        "encryptionConfiguration": {"sseType": "aws:kms", "kmsKeyArn": "key"}
                      }
                    }
                  }
                }
                """);
        StorageBackend<String, VectorBucket> first = open(mode, snapshot);
        try {
            VectorBucket legacy = first.get(KEY).orElseThrow();
            assertNull(legacy.getCreationTime());
            assertNull(legacy.getIndexes().get("old").getCreationTime());
            S3VectorsService service = service(first, Clock.fixed(MIGRATION_TIME, ZoneOffset.UTC));
            VectorBucket bucket = listFirst ? service.listVectorBuckets(REGION).getFirst()
                    : service.getVectorBucket("legacy", REGION);
            VectorIndex index = service.getIndex("legacy", "old", REGION);
            assertEquals(MIGRATION_TIME, bucket.getCreationTime());
            assertEquals(MIGRATION_TIME, index.getCreationTime());
            assertEquals(DEFAULT_ENCRYPTION, bucket.getEncryptionConfiguration());
            assertEquals(DEFAULT_ENCRYPTION, index.getEncryptionConfiguration());
            assertEquals(KNOWN_TIME, bucket.getIndexes().get("configured").getCreationTime());
            assertEquals(Map.of("sseType", "aws:kms", "kmsKeyArn", "key"),
                    bucket.getIndexes().get("configured").getEncryptionConfiguration());
            assertEquals(MIGRATION_TIME, service.listVectorBuckets(REGION).getFirst().getCreationTime());
            assertEquals(MIGRATION_TIME, service.listIndexes("legacy", REGION).stream()
                    .filter(item -> "old".equals(item.getIndexName())).findFirst().orElseThrow().getCreationTime());
            verify(first, times(1)).put(eq(KEY), any());

            VectorIndex created = service.createIndex("legacy", "new", 1, "float32", "cosine", List.of(), REGION);
            assertEquals(DEFAULT_ENCRYPTION, created.getEncryptionConfiguration());
            assertEquals(MIGRATION_TIME, created.getCreationTime());
        } finally {
            close(first);
        }

        StorageBackend<String, VectorBucket> second = open(mode, snapshot);
        try {
            VectorBucket stored = second.get(KEY).orElseThrow();
            assertEquals(MIGRATION_TIME, stored.getCreationTime());
            assertEquals(MIGRATION_TIME, stored.getIndexes().get("old").getCreationTime());
            assertEquals(DEFAULT_ENCRYPTION, stored.getEncryptionConfiguration());
            S3VectorsService restarted = service(second, Clock.fixed(MIGRATION_TIME.plusSeconds(86_400), ZoneOffset.UTC));
            assertEquals(MIGRATION_TIME, restarted.getVectorBucket("legacy", REGION).getCreationTime());
            assertEquals(MIGRATION_TIME, restarted.listVectorBuckets(REGION).getFirst().getCreationTime());
            assertEquals(MIGRATION_TIME, restarted.getIndex("legacy", "old", REGION).getCreationTime());
            assertEquals(MIGRATION_TIME, restarted.getIndex("legacy", "new", REGION).getCreationTime());
            assertEquals(KNOWN_TIME, restarted.getIndex("legacy", "configured", REGION).getCreationTime());
            assertEquals(DEFAULT_ENCRYPTION, restarted.getIndex("legacy", "new", REGION).getEncryptionConfiguration());
            assertEquals(3, restarted.listIndexes("legacy", REGION).size());
            verify(second, never()).put(anyString(), any());
        } finally {
            close(second);
        }
    }

    private static S3VectorsService service(StorageBackend<String, VectorBucket> backend, Clock clock) {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<VectorBucket>create(anyString(), anyString(), any()))
                .thenReturn(new AccountAwareStorageBackend<>(backend, null, ACCOUNT));
        return new S3VectorsService(factory, new RegionResolver(REGION, ACCOUNT), clock);
    }

    private static StorageBackend<String, VectorBucket> open(String mode, Path snapshot) {
        TypeReference<Map<String, VectorBucket>> type = new TypeReference<>() {};
        StorageBackend<String, VectorBucket> backend = spy(switch (mode) {
            case "persistent" -> new PersistentStorage<>(snapshot, type);
            case "hybrid" -> new HybridStorage<>(snapshot, type, 60_000);
            case "wal" -> new WalStorage<>(snapshot, snapshot.resolveSibling("s3vectors.wal"), type, 60_000);
            default -> throw new IllegalArgumentException(mode);
        });
        backend.load();
        return backend;
    }

    private static void close(StorageBackend<String, VectorBucket> backend) {
        if (backend instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        } else if (backend instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        } else {
            backend.flush();
        }
    }
}
