package io.floci.aws.services.s3tables;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3tables.model.TableBucket;
import io.floci.aws.testing.StorageTestBackends;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class S3TablesEncryptionPersistenceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final Map<String, String> KMS = Map.of("sseAlgorithm", "aws:kms",
            "kmsKeyArn", "arn:aws:kms:us-east-1:000000000000:key/9f135f7d-d012-4be8-951a-e8517782f780");

    @ParameterizedTest
    @CsvSource({"persistent,false", "persistent,true", "hybrid,false", "hybrid,true", "wal,false", "wal,true"})
    void defaultAndConfiguredEncryptionReadBackAfterReload(String mode, boolean configured, @TempDir Path directory) {
        Path snapshot = directory.resolve("s3tables.json");
        String arn;
        Map<String, String> expected = configured ? KMS : Map.of("sseAlgorithm", "AES256");
        StorageBackend<String, TableBucket> first = open(mode, snapshot);
        try {
            S3TablesService service = service(first);
            arn = service.createTableBucket("warehouse", configured ? KMS : null, null, Map.of(), REGION).getArn();
            assertEquals(expected, service.getTableBucketEncryption(arn, REGION));
        } finally {
            StorageTestBackends.close(first);
        }

        StorageBackend<String, TableBucket> second = open(mode, snapshot);
        try {
            assertEquals(expected, service(second).getTableBucketEncryption(arn, REGION));
        } finally {
            StorageTestBackends.close(second);
        }
    }

    private static S3TablesService service(StorageBackend<String, TableBucket> backend) {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<TableBucket>create(anyString(), anyString(), any()))
                .thenReturn(new AccountAwareStorageBackend<>(backend, null, ACCOUNT));
        return new S3TablesService(factory, new RegionResolver(REGION, ACCOUNT), mock(S3Service.class));
    }

    private static StorageBackend<String, TableBucket> open(String mode, Path snapshot) {
        TypeReference<Map<String, TableBucket>> type = new TypeReference<>() {};
        StorageBackend<String, TableBucket> backend = StorageTestBackends.create(
                mode, snapshot, snapshot.resolveSibling("s3tables.wal"), type, 60_000);
        backend.load();
        return backend;
    }
}
