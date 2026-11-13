package io.floci.aws.services.kms;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KmsImportGenerationConcurrencyServiceTest {
    private static final String REGION = "us-east-1";

    @Test
    void unrelatedLookupCompletesWhileWrappingKeyGenerationIsParked() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KmsKey key = fixture.externalKey();
            KmsKey other = fixture.service.createKey("unrelated", REGION);
            Future<KmsService.ImportParameters> pending = fixture.start(key.getKeyId());
            Future<KmsKey> lookup = fixture.workers.submit(() -> fixture.service.describeKey(other.getKeyId(), REGION));
            assertEquals(other.getArn(), lookup.get(5, TimeUnit.SECONDS).getArn());
            fixture.release.countDown();
            assertEquals(key.getArn(), pending.get(10, TimeUnit.SECONDS).keyArn());
        }
    }

    @Test
    void deletionDuringGenerationPreventsParameterPublication() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KmsKey key = fixture.externalKey();
            Future<KmsService.ImportParameters> pending = fixture.start(key.getKeyId());
            Future<?> deletion = fixture.workers.submit(() -> fixture.service.scheduleKeyDeletion(key.getKeyId(), 7, REGION));
            deletion.get(5, TimeUnit.SECONDS);
            fixture.release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
            assertInstanceOf(AwsException.class, failure.getCause());
            assertNull(fixture.service.describeKey(key.getKeyId(), REGION).getImportParameters());
            assertEquals("PendingDeletion", fixture.service.describeKey(key.getKeyId(), REGION).getKeyState());
        }
    }

    @Test
    void aliasRetargetDuringGenerationKeepsTheInitiallyResolvedKey() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KmsKey key = fixture.externalKey();
            KmsKey other = fixture.externalKey();
            fixture.service.createAlias("alias/import-target", key.getKeyId(), REGION);
            Future<KmsService.ImportParameters> pending = fixture.start("alias/import-target");
            Future<?> retarget = fixture.workers.submit(
                    () -> fixture.service.updateAlias("alias/import-target", other.getKeyId(), REGION));
            retarget.get(5, TimeUnit.SECONDS);
            fixture.release.countDown();
            assertEquals(key.getArn(), pending.get(10, TimeUnit.SECONDS).keyArn());
            assertNotNull(fixture.service.describeKey(key.getKeyId(), REGION).getImportParameters());
            assertNull(fixture.service.describeKey(other.getKeyId(), REGION).getImportParameters());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private final KmsService service = new KmsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000")) {
            @Override
            KmsKeyImport.WrappingKeyPair generateImportWrappingKeyPair(String wrappingKeySpec) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS), "Wrapping generation was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return super.generateImportWrappingKeyPair(wrappingKeySpec);
            }
        };

        private KmsKey externalKey() {
            return service.createKey("import", "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT", null,
                    Map.of(), "EXTERNAL", REGION);
        }

        private Future<KmsService.ImportParameters> start(String keyId) throws InterruptedException {
            Future<KmsService.ImportParameters> pending = workers.submit(
                    () -> service.getParametersForImport(keyId, "RSAES_OAEP_SHA_256", "RSA_2048", REGION));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Wrapping generation did not start");
            return pending;
        }

        @Override
        public void close() {
            release.countDown();
            workers.close();
        }
    }
}
