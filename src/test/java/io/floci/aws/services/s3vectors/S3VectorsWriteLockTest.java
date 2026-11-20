package io.floci.aws.services.s3vectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3vectors.model.VectorBucket;
import io.floci.aws.services.s3vectors.model.VectorData;
import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3VectorsWriteLockTest {

    private static final String REGION = "us-east-1";
    private ParkingStorage storage;
    private S3VectorsService service;

    private void setUp(boolean copyOnAccess) {
        storage = new ParkingStorage(copyOnAccess);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<VectorBucket>create(anyString(), anyString(), any()))
                .thenReturn(new AccountAwareStorageBackend<>(storage, null, "000000000000"));
        service = new S3VectorsService(factory, new RegionResolver(REGION, "000000000000"));
        service.createVectorBucket("bucket", null, REGION);
    }

    private void createIndex() {
        service.createIndex("bucket", "index", 1, "float32", "cosine", List.of(), REGION);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void vectorWritesPreserveConcurrentIndexCreation(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        createIndex();
        overlap(() -> putVector("first"), () -> service.createIndex("bucket", "second", 1,
                "float32", "cosine", List.of(), REGION));
        assertEquals("second", service.getIndex("bucket", "second", REGION).getIndexName());
        assertEquals(1, service.getVectors("bucket", "index", List.of("first"), REGION).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentVectorWritesPreserveBothVectors(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        createIndex();
        overlap(() -> putVector("first"), () -> putVector("second"));
        assertEquals(2, service.getVectors("bucket", "index", List.of("first", "second"), REGION).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void vectorWritesDoNotReviveDeletedVectorsOrIndexes(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        createIndex();
        putVector("old");
        overlap(() -> putVector("new"), () -> service.deleteVectors("bucket", "index", List.of("old"), REGION));
        assertTrue(service.getVectors("bucket", "index", List.of("old"), REGION).isEmpty());
        assertEquals(1, service.getVectors("bucket", "index", List.of("new"), REGION).size());

        overlap(() -> putVector("new"), () -> service.deleteIndex("bucket", "index", REGION));
        AwsException error = assertThrows(AwsException.class, () -> service.getIndex("bucket", "index", REGION));
        assertEquals("NotFoundException", error.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void indexDeletionCannotResurrectADeletedOrRecreatedBucket(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        createIndex();
        overlap(() -> service.deleteIndex("bucket", "index", REGION), () -> service.deleteVectorBucket("bucket", REGION));
        assertThrows(AwsException.class, () -> service.getVectorBucket("bucket", REGION));

        service.createVectorBucket("bucket", null, REGION);
        createIndex();
        overlap(() -> service.deleteIndex("bucket", "index", REGION), () -> {
            service.deleteVectorBucket("bucket", REGION);
            service.createVectorBucket("bucket", null, Map.of("new", "tag"), REGION);
        });
        assertEquals(Map.of("new", "tag"), service.getVectorBucket("bucket", REGION).getTags());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bucketAndIndexTagUpdatesPreserveEachOther(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        createIndex();
        overlap(() -> service.replaceBucketTags("bucket", Map.of("bucket", "tag"), REGION),
                () -> service.replaceIndexTags("bucket", "index", Map.of("index", "tag"), REGION));
        assertEquals(Map.of("bucket", "tag"), service.getVectorBucket("bucket", REGION).getTags());
        assertEquals(Map.of("index", "tag"), service.getIndex("bucket", "index", REGION).getTags());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bucketTagsDoNotOverwriteConcurrentIndexCreation(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        overlap(() -> service.replaceBucketTags("bucket", Map.of("bucket", "tag"), REGION), this::createIndex);
        assertEquals(Map.of("bucket", "tag"), service.getVectorBucket("bucket", REGION).getTags());
        assertEquals("index", service.getIndex("bucket", "index", REGION).getIndexName());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void indexTagsPreserveVectorWritesAndDoNotReviveDeletedData(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        createIndex();
        Runnable tagUpdate = () -> service.replaceIndexTags("bucket", "index", Map.of("index", "tag"), REGION);
        overlap(tagUpdate, () -> service.putVectors("bucket", "index",
                List.of(new VectorData("key", List.of(1.0f), Map.of())), REGION));
        assertEquals(Map.of("index", "tag"), service.getIndex("bucket", "index", REGION).getTags());
        assertEquals(1, service.getVectors("bucket", "index", List.of("key"), REGION).size());

        overlap(tagUpdate, () -> service.deleteVectors("bucket", "index", List.of("key"), REGION));
        assertTrue(service.getVectors("bucket", "index", List.of("key"), REGION).isEmpty());

        overlap(tagUpdate, () -> service.deleteIndex("bucket", "index", REGION));
        AwsException error = assertThrows(AwsException.class, () -> service.getIndex("bucket", "index", REGION));
        assertEquals("NotFoundException", error.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagUpdateCannotResurrectADeletedOrRecreatedBucket(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        overlap(() -> service.replaceBucketTags("bucket", Map.of("old", "tag"), REGION),
                () -> service.deleteVectorBucket("bucket", REGION));
        assertThrows(AwsException.class, () -> service.getVectorBucket("bucket", REGION));

        service.createVectorBucket("bucket", null, REGION);
        overlap(() -> service.replaceBucketTags("bucket", Map.of("old", "tag"), REGION), () -> {
            service.deleteVectorBucket("bucket", REGION);
            service.createVectorBucket("bucket", null, Map.of("new", "tag"), REGION);
        });
        assertEquals(Map.of("new", "tag"), service.getVectorBucket("bucket", REGION).getTags());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bucketPolicyDoesNotOverwriteConcurrentIndexCreation(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        overlap(() -> service.putVectorBucketPolicy("bucket", null, policy, REGION), this::createIndex);
        assertEquals(policy, service.getVectorBucketPolicy("bucket", null, REGION));
        assertEquals("index", service.getIndex("bucket", "index", REGION).getIndexName());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bucketPolicyUpdateCannotResurrectADeletedOrRecreatedBucket(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        overlap(() -> service.putVectorBucketPolicy("bucket", null, policy, REGION),
                () -> service.deleteVectorBucket("bucket", REGION));
        assertThrows(AwsException.class, () -> service.getVectorBucket("bucket", REGION));

        service.createVectorBucket("bucket", null, REGION);
        overlap(() -> service.putVectorBucketPolicy("bucket", null, policy, REGION), () -> {
            service.deleteVectorBucket("bucket", REGION);
            service.createVectorBucket("bucket", null, Map.of("new", "tag"), REGION);
        });
        assertEquals(Map.of("new", "tag"), service.getVectorBucket("bucket", REGION).getTags());
        assertThrows(AwsException.class, () -> service.getVectorBucketPolicy("bucket", null, REGION));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resourceTagMergesPreserveConcurrentTagsAndVectors(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        createIndex();
        String arn = service.getIndex("bucket", "index", REGION).getIndexArn();
        overlap(() -> service.tagResource(arn, Map.of("first", "value")),
                () -> service.tagResource(arn, Map.of("second", "value")));
        assertEquals(Map.of("first", "value", "second", "value"), service.listTagsForResource(arn));
        overlap(() -> service.untagResource(arn, List.of("first")), () -> putVector("stored"));
        assertEquals(Map.of("second", "value"), service.listTagsForResource(arn));
        assertEquals(1, service.getVectors("bucket", "index", List.of("stored"), REGION).size());
    }

    private void putVector(String key) {
        service.putVectors("bucket", "index", List.of(new VectorData(key, List.of(1.0f), Map.of())), REGION);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyMetadataMigrationDoesNotOverwriteConcurrentWrites(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        VectorBucket legacy = new VectorBucket("bucket", "bucket-arn", null);
        storage.put("000000000000/" + REGION + "::bucket", legacy);
        overlap(() -> service.listVectorBuckets(REGION), this::createIndex);
        assertNotNull(service.getVectorBucket("bucket", REGION).getCreationTime());
        assertEquals(Map.of("sseType", "AES256"), service.getIndex("bucket", "index", REGION).getEncryptionConfiguration());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyMetadataMigrationDoesNotReviveADeletedBucket(boolean copyOnAccess) throws Exception {
        setUp(copyOnAccess);
        storage.put("000000000000/" + REGION + "::bucket", new VectorBucket("bucket", "bucket-arn", null));
        overlap(() -> service.getVectorBucket("bucket", REGION), () -> service.deleteVectorBucket("bucket", REGION));
        assertTrue(service.listVectorBuckets(REGION).isEmpty());
    }

    private void overlap(Runnable first, Runnable second) throws Exception {
        Gate gate = new Gate();
        storage.nextWrite.set(gate);
        FutureTask<Void> firstTask = new FutureTask<>(first, null);
        FutureTask<Void> secondTask = new FutureTask<>(second, null);
        Thread firstThread = Thread.ofPlatform().daemon().unstarted(firstTask);
        Thread secondThread = Thread.ofPlatform().daemon().unstarted(secondTask);
        firstThread.start();
        try {
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "First writer did not reach storage");
            secondThread.start();
            ThreadStates.awaitBlockedOrDone(secondThread);
        } finally {
            gate.release.countDown();
        }
        firstTask.get(5, TimeUnit.SECONDS);
        secondTask.get(5, TimeUnit.SECONDS);
    }

    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
    }

    private static final class ParkingStorage extends InMemoryStorage<String, VectorBucket> {
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final boolean copyOnAccess;
        private final AtomicReference<Gate> nextWrite = new AtomicReference<>();

        private ParkingStorage(boolean copyOnAccess) {
            this.copyOnAccess = copyOnAccess;
        }

        @Override
        public Optional<VectorBucket> get(String key) {
            return super.get(key).map(this::copy);
        }

        @Override
        public void put(String key, VectorBucket value) {
            Gate gate = nextWrite.getAndSet(null);
            if (gate != null) {
                gate.entered.countDown();
                try {
                    assertTrue(gate.release.await(5, TimeUnit.SECONDS), "Paused write was not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Paused write was interrupted", e);
                }
            }
            super.put(key, copy(value));
        }

        private VectorBucket copy(VectorBucket value) {
            return copyOnAccess ? mapper.convertValue(value, VectorBucket.class) : value;
        }
    }
}
