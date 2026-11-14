package io.floci.aws.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.s3.model.Bucket;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3LegacyNumericBucketTest {

    private static final String OWNER = "111111111111";
    private static final String CALLER = "222222222222";
    private static final String BUCKET = "333333333333";
    private static final String KEY = "orders/report.csv";
    private static final String STORE_KEY = BUCKET + "/" + KEY;

    @TempDir
    Path directory;

    private final AtomicReference<String> caller = new AtomicReference<>(OWNER);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistedLegacyObjectIsReadAndMigratedIntoTheBucketOwnersAccount(boolean global) throws Exception {
        PersistentStorage<String, S3Object> raw = persistedObject(BUCKET, KEY);
        S3Service service = service(raw, global);
        service.createBucket(BUCKET, "us-east-1");
        Path legacyFile = directory.resolve(BUCKET).resolve(KEY + ".s3data");
        Files.createDirectories(legacyFile.getParent());
        Files.writeString(legacyFile, "legacy-body", UTF_8);
        if (global) {
            caller.set(CALLER);
        }

        assertArrayEquals("legacy-body".getBytes(UTF_8), service.getObject(BUCKET, KEY).getData());

        PersistentStorage<String, S3Object> reloaded = reload();
        assertFalse(reloaded.get(STORE_KEY).isPresent());
        assertTrue(reloaded.get(OWNER + "/" + STORE_KEY).isPresent());
        assertFalse(reloaded.get(CALLER + "/" + STORE_KEY).isPresent());
        assertArrayEquals("legacy-body".getBytes(UTF_8),
                Files.readAllBytes(directory.resolve(".accounts").resolve(OWNER)
                        .resolve(BUCKET).resolve(KEY + ".s3data")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anAccountScopedObjectAtTheSameRawKeyIsNeitherReadNorMoved(boolean global) {
        PersistentStorage<String, S3Object> raw = persistedObject("orders", "report.csv");
        S3Service service = service(raw, global);
        caller.set(BUCKET);
        service.createBucket("orders", "us-east-1");
        caller.set(OWNER);
        service.createBucket(BUCKET, "us-east-1");
        if (global) {
            caller.set(CALLER);
        }

        AwsException failure = assertThrows(AwsException.class, () -> service.getObject(BUCKET, KEY));

        assertEquals("NoSuchKey", failure.getErrorCode());
        assertEquals("orders", raw.get(STORE_KEY).orElseThrow().getBucketName());
        assertFalse(raw.get(OWNER + "/" + STORE_KEY).isPresent());
        assertFalse(raw.get(CALLER + "/" + STORE_KEY).isPresent());
        caller.set(BUCKET);
        assertEquals("report.csv", service.headObject("orders", "report.csv").getKey());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMismatchedObjectKeyIsNotMigrated(boolean global) {
        PersistentStorage<String, S3Object> raw = persistedObject(BUCKET, "different-key");
        S3Service service = service(raw, global);
        service.createBucket(BUCKET, "us-east-1");

        AwsException failure = assertThrows(AwsException.class, () -> service.getObject(BUCKET, KEY));

        assertEquals("NoSuchKey", failure.getErrorCode());
        assertEquals("different-key", raw.get(STORE_KEY).orElseThrow().getKey());
        assertFalse(raw.get(OWNER + "/" + STORE_KEY).isPresent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anotherAccountsSameNamedBucketCannotClaimLegacyData(boolean global) {
        PersistentStorage<String, S3Object> raw = persistedObject(BUCKET, KEY);
        S3Service service = service(raw, global);
        caller.set(CALLER);
        service.createBucket(BUCKET, "us-east-1");

        AwsException failure = assertThrows(AwsException.class, () -> service.getObject(BUCKET, KEY));

        assertEquals("NoSuchKey", failure.getErrorCode());
        assertTrue(raw.get(STORE_KEY).isPresent());
        assertFalse(raw.get(CALLER + "/" + STORE_KEY).isPresent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExistingScopedObjectWinsWithoutRemovingTheLegacyRecord(boolean global) {
        PersistentStorage<String, S3Object> raw = persistedObject(BUCKET, KEY);
        S3Service service = service(raw, global);
        service.createBucket(BUCKET, "us-east-1");
        service.putObject(BUCKET, KEY, "new-body".getBytes(UTF_8), "text/plain", Map.of());
        raw.put(STORE_KEY, new S3Object(BUCKET, KEY, "legacy-body".getBytes(UTF_8), "text/plain"));

        assertArrayEquals("new-body".getBytes(UTF_8), service.getObject(BUCKET, KEY).getData());
        assertTrue(raw.get(STORE_KEY).isPresent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPersistedLegacyVersionIsMatchedByItsObjectKeyAndVersion(boolean global) {
        PersistentStorage<String, S3Object> raw = reload();
        S3Object version = new S3Object(BUCKET, KEY, "old-version".getBytes(UTF_8), "text/plain");
        version.setVersionId("old-version-id");
        String versionKey = STORE_KEY + "#v#old-version-id";
        raw.put(versionKey, version);
        raw = reload();
        S3Service service = service(raw, global);
        service.createBucket(BUCKET, "us-east-1");
        if (global) {
            caller.set(CALLER);
        }

        assertEquals("old-version-id", service.headObject(BUCKET, KEY, "old-version-id").getVersionId());
        assertFalse(raw.get(versionKey).isPresent());
        assertTrue(raw.get(OWNER + "/" + versionKey).isPresent());
    }

    private PersistentStorage<String, S3Object> persistedObject(String bucket, String key) {
        PersistentStorage<String, S3Object> raw = reload();
        raw.put(STORE_KEY, new S3Object(bucket, key, "legacy-body".getBytes(UTF_8), "text/plain"));
        return reload();
    }

    private PersistentStorage<String, S3Object> reload() {
        PersistentStorage<String, S3Object> raw = new PersistentStorage<>(
                directory.resolve("objects.json"), new TypeReference<Map<String, S3Object>>() { });
        raw.load();
        return raw;
    }

    @SuppressWarnings("unchecked")
    private S3Service service(PersistentStorage<String, S3Object> raw, boolean global) {
        RequestContext request = mock(RequestContext.class);
        when(request.getAccountId()).thenAnswer(ignored -> caller.get());
        Instance<RequestContext> context = mock(Instance.class);
        when(context.get()).thenReturn(request);
        AccountAwareStorageBackend<Bucket> buckets =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), context, OWNER);
        AccountAwareStorageBackend<S3Object> objects = new AccountAwareStorageBackend<>(raw, context, OWNER);
        return new S3Service(buckets, objects, directory, false, global);
    }
}
