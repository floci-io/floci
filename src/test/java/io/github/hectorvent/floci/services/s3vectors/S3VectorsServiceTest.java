package io.github.hectorvent.floci.services.s3vectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3vectors.model.VectorBucket;
import io.github.hectorvent.floci.services.s3vectors.model.VectorIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class S3VectorsServiceTest {

    private static final String REGION = "us-east-1";
    private S3VectorsService service;

    @BeforeEach
    void setUp() {
        StorageFactory factory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                            TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
        service = new S3VectorsService(factory, new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void persistsCreationTimesConfigurationAndReconciledTags() throws Exception {
        Map<String, String> encryption = Map.of("sseType", "aws:kms", "kmsKeyArn", "key");
        VectorBucket bucket = service.createVectorBucket("bucket", encryption, Map.of("old", "value"), REGION);
        VectorIndex index = service.createIndex("bucket", "index", 3, "float32", "cosine", List.of("text"),
                null, Map.of("old", "value"), REGION);
        service.replaceBucketTags("bucket", Map.of("new", "value"), REGION);
        service.replaceIndexTags("bucket", "index", Map.of(), REGION);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        VectorBucket restored = mapper.readValue(mapper.writeValueAsString(bucket), VectorBucket.class);
        VectorIndex restoredIndex = restored.getIndexes().get("index");
        assertEquals(bucket.getCreationTime(), restored.getCreationTime());
        assertEquals(index.getCreationTime(), restoredIndex.getCreationTime());
        assertEquals(encryption, restoredIndex.getEncryptionConfiguration());
        assertEquals(List.of("text"), restoredIndex.getNonFilterableMetadataKeys());
        assertEquals(Map.of("new", "value"), restored.getTags());
        assertEquals(Map.of(), restoredIndex.getTags());
    }

    @Test
    void indexEncryptionCanOverrideTheBucketDefault() {
        VectorBucket bucket = service.createVectorBucket("bucket", null, REGION);
        assertEquals(Map.of("sseType", "AES256"), bucket.getEncryptionConfiguration());
        Map<String, String> encryption = Map.of("sseType", "aws:kms", "kmsKeyArn", "key");
        VectorIndex index = service.createIndex("bucket", "index", 3, "float32", "cosine", List.of(),
                encryption, Map.of(), REGION);
        assertEquals(encryption, index.getEncryptionConfiguration());
    }

    @Test
    void nonFilterableMetadataKeysAreRejectedEvenInsideLogicalFilters() {
        service.createVectorBucket("bucket", null, REGION);
        service.createIndex("bucket", "index", 3, "float32", "cosine", List.of("text"), REGION);
        for (Object filter : List.of(Map.of("text", "hello"), Map.of("$and", List.of(Map.of("text", "hello"))))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.queryVectors("bucket", "index", List.of(1.0f, 0.0f, 0.0f), filter, 1, REGION));
            assertEquals("ValidationException", error.getErrorCode());
        }
        assertEquals(List.of(), service.queryVectors("bucket", "index", List.of(1.0f, 0.0f, 0.0f),
                Map.of("category", "hello"), 1, REGION));
    }

    @Test
    void rejectsInvalidIndexDimensions() {
        service.createVectorBucket("bucket", null, REGION);
        for (int dimension : List.of(0, 4097)) {
            assertThrows(AwsException.class,
                    () -> service.createIndex("bucket", "index", dimension, "float32", "cosine", List.of(), REGION));
        }
        assertTrue(service.listIndexes("bucket", REGION).isEmpty());
    }

    @Test
    void invalidMetadataKeysFailBeforeCreatingAnIndex() {
        service.createVectorBucket("bucket", null, REGION);
        for (List<String> keys : List.of(List.of(""), List.of(" "), List.of("a".repeat(64)),
                Collections.nCopies(11, "key"), Collections.<String>singletonList(null))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.createIndex("bucket", "index", 3, "float32", "cosine", keys, REGION));
            assertEquals("ValidationException", error.getErrorCode());
        }
        assertTrue(service.listIndexes("bucket", REGION).isEmpty());
    }

    @Test
    void metadataKeyLimitsAreInclusive() {
        service.createVectorBucket("bucket", null, REGION);
        List<String> keys = List.of("a".repeat(63), "b", "c", "d", "e", "f", "g", "h", "i", "j");
        VectorIndex index = service.createIndex("bucket", "index", 3, "float32", "euclidean", keys, REGION);
        assertEquals(keys, index.getNonFilterableMetadataKeys());
    }
}
