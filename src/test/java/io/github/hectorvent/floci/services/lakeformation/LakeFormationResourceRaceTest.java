package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lakeformation.model.ResourceInfo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A register racing an update for the same resource must not be silently reverted.
 *
 * <p>The update is a read-modify-write under the storage monitor: read the record, mutate it,
 * write it back. A register landing between the read and the write would be overwritten by that
 * write, losing the fresh registration, so the register takes the same monitor the update and
 * deregister already hold.
 */
class LakeFormationResourceRaceTest {

    private static final String REGION = "us-east-1";
    private static final String RESOURCE_ARN = "arn:aws:s3:::race-bucket";
    private static final String REGISTER_ROLE = "arn:aws:iam::000000000000:role/RegisterRole";
    private static final String UPDATE_ROLE = "arn:aws:iam::000000000000:role/UpdateRole";

    private MemoryLakeFormationStorage newStorage() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenReturn(AccountAwareStorageBackend.inMemory("000000000000"));
        return new MemoryLakeFormationStorage(storageFactory);
    }

    @Test
    void aRegisterRacingAnUpdateIsNeverReverted() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            MemoryLakeFormationStorage storage = newStorage();

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread registerer = new Thread(() -> {
                await(start);
                try {
                    storage.registerResource(REGION, RESOURCE_ARN, REGISTER_ROLE, false, null);
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
            Thread updater = new Thread(() -> {
                await(start);
                try {
                    storage.updateResource(REGION, RESOURCE_ARN, UPDATE_ROLE, null, null, null);
                } catch (io.github.hectorvent.floci.core.common.AwsException expected) {
                    if (!"EntityNotFoundException".equals(expected.getErrorCode())) {
                        unexpected.set(expected);
                    }
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });

            registerer.start();
            updater.start();
            start.countDown();
            registerer.join(TimeUnit.SECONDS.toMillis(10));
            updater.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(registerer.isAlive() || updater.isAlive(), "a thread never finished");
            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());

            // The register always lands, so the resource is there. The role names the last writer:
            // the register, or the update if it ran after. Anything else means a stale write
            // reverted the registration.
            Optional<ResourceInfo> stored = storage.describeResource(REGION, RESOURCE_ARN);
            assertTrue(stored.isPresent(), "the registration was lost (attempt " + attempt + ")");
            assertTrue(Set.of(REGISTER_ROLE, UPDATE_ROLE).contains(stored.get().getRoleArn()),
                    "unexpected role " + stored.get().getRoleArn() + " (attempt " + attempt + ")");
        }
    }

    @Test
    void aConcurrentDescribeNeverObservesAPartialUpdate() throws Exception {
        String initialRole = "arn:aws:iam::000000000000:role/Initial";
        String updatedRole = "arn:aws:iam::000000000000:role/Updated";

        for (int attempt = 0; attempt < 50; attempt++) {
            MemoryLakeFormationStorage storage = newStorage();
            storage.registerResource(REGION, RESOURCE_ARN, initialRole, false, true);

            final int attemptNo = attempt;
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            AtomicInteger observations = new AtomicInteger(0);

            Thread updater = new Thread(() -> {
                await(start);
                try {
                    storage.updateResource(REGION, RESOURCE_ARN, updatedRole, null, null, false);
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });

            Thread reader = new Thread(() -> {
                await(start);
                try {
                    for (int i = 0; i < 200; i++) {
                        Optional<ResourceInfo> info = storage.describeResource(REGION, RESOURCE_ARN);
                        if (info.isPresent()) {
                            ResourceInfo r = info.get();
                            // Every observation must be a fully consistent snapshot:
                            // either (initialRole, federation=true) or (updatedRole, federation=false).
                            // A mixed state (e.g. updatedRole with federation=true) means the
                            // reader observed a partially-mutated object.
                            boolean isInitialState = updatedRole.equals(r.getRoleArn()) == false
                                    && Boolean.TRUE.equals(r.getWithFederation());
                            boolean isUpdatedState = updatedRole.equals(r.getRoleArn())
                                    && Boolean.FALSE.equals(r.getWithFederation());
                            assertTrue(isInitialState || isUpdatedState,
                                    "partial update observed: role=" + r.getRoleArn()
                                            + ", withFederation=" + r.getWithFederation()
                                            + " (attempt " + attemptNo + ")");
                            observations.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });

            updater.start();
            reader.start();
            start.countDown();
            updater.join(TimeUnit.SECONDS.toMillis(10));
            reader.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(updater.isAlive() || reader.isAlive(), "a thread never finished");
            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());
            assertTrue(observations.get() > 0, "reader never observed the resource");

            // After the update completes, the final state must be fully consistent.
            Optional<ResourceInfo> stored = storage.describeResource(REGION, RESOURCE_ARN);
            assertTrue(stored.isPresent());
            assertEquals(updatedRole, stored.get().getRoleArn());
            assertEquals(false, stored.get().getWithFederation());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
