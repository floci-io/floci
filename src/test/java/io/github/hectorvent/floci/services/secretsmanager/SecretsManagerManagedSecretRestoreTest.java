package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsManagerManagedSecretRestoreTest {

    private static final String REGION = "us-east-1";
    private static final String NAME = "redshift-serverless/namespace-id";

    private final SecretsManagerService service = new SecretsManagerService(new InMemoryStorage<>(), 30);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @CsvSource({"true,000000000000", "false,000000000000", "true,111122223333", "false,111122223333"})
    void createAndRollbackCannotOverwriteEachOther(boolean restoreFirst, String accountId) throws Exception {
        ParkingStorage storage = new ParkingStorage();
        AccountAwareStorageBackend<Secret> accountStore = new AccountAwareStorageBackend<>(storage, null, accountId);
        SecretsManagerService concurrentService = new SecretsManagerService(accountStore, 30,
                new RegionResolver(REGION, accountId), null, mapper);
        Secret original = concurrentService.createSecret(NAME, "original", null, null, null, null, "redshift", REGION);
        Secret snapshot = mapper.convertValue(original, Secret.class);
        concurrentService.deleteSecret(original.getArn(), null, true, REGION);
        AtomicReference<Secret> replacement = new AtomicReference<>();
        Runnable create = () -> {
            Secret created = concurrentService.createSecret(NAME, "replacement-v1", null, null, null, null, REGION);
            concurrentService.putSecretValue(created.getArn(), "replacement-v2", null, null, REGION,
                    List.of("AWSCURRENT", "custom-stage"));
            replacement.set(created);
        };
        Runnable restore = () -> concurrentService.restoreManagedSecret(snapshot, REGION);
        storage.parkNextWrite.set(true);
        Watched winner = new Watched(restoreFirst ? restore : create);
        Watched loser = new Watched(() -> {
            AwsException conflict = assertThrows(AwsException.class, restoreFirst ? create::run : restore::run);
            assertEquals("ResourceExistsException", conflict.getErrorCode());
        });

        winner.thread.start();
        try {
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS));
            loser.thread.start();
            assertTrue(loser.awaitBlocked(), "The competing operation must wait for the name's pending write");
        } finally {
            storage.released.countDown();
            winner.awaitSuccess();
            loser.awaitSuccess();
        }

        Secret expected = restoreFirst ? snapshot : replacement.get();
        assertEquals(mapper.valueToTree(expected), mapper.valueToTree(concurrentService.describeSecret(NAME, REGION)));
    }

    @Test
    void rollbackRefusesAReusedNameAndPreservesTheReplacementSecret() {
        Secret original = service.createSecret(NAME, "original", null, null, null, null,
                "redshift-serverless", REGION);
        Secret snapshot = mapper.convertValue(original, Secret.class);
        service.deleteSecret(original.getArn(), null, true, REGION);
        Secret replacement = service.createSecret(NAME, "replacement-v1", null, null, null, null,
                "redshift-serverless", REGION);
        service.putSecretValue(replacement.getArn(), "replacement-v2", null, null, REGION,
                List.of("AWSCURRENT", "custom-stage"));
        Secret replacementBefore = mapper.convertValue(replacement, Secret.class);
        assertNotEquals(snapshot.getArn(), replacement.getArn());

        AwsException conflict = assertThrows(AwsException.class,
                () -> service.restoreManagedSecret(snapshot, REGION));

        assertEquals("ResourceExistsException", conflict.getErrorCode());
        assertEquals(mapper.valueToTree(replacementBefore),
                mapper.valueToTree(service.describeSecret(NAME, REGION)));
    }

    @Test
    void rollbackChecksTheAccountNamedInTheSnapshotArn() {
        AccountAwareStorageBackend<Secret> store = AccountAwareStorageBackend.inMemory("000000000000");
        SecretsManagerService accountService = new SecretsManagerService(store, 30);
        Secret snapshot = accountService.createSecret(NAME, "original", null, null, null, null,
                "redshift", REGION);
        Secret replacement = mapper.convertValue(snapshot, Secret.class);
        replacement.setArn("arn:aws:secretsmanager:us-east-1:111122223333:secret:" + NAME + "-AbCdEf");
        store.putForAccount("111122223333", REGION + "::" + NAME, replacement);
        Secret foreignSnapshot = mapper.convertValue(snapshot, Secret.class);
        foreignSnapshot.setArn("arn:aws:secretsmanager:us-east-1:111122223333:secret:" + NAME + "-GhIjKl");

        AwsException conflict = assertThrows(AwsException.class,
                () -> accountService.restoreManagedSecret(foreignSnapshot, REGION));

        assertEquals("ResourceExistsException", conflict.getErrorCode());
        assertEquals(replacement.getArn(), store.getForAccount("111122223333", REGION + "::" + NAME)
                .orElseThrow().getArn());
        assertEquals(snapshot.getArn(), accountService.describeSecret(NAME, REGION).getArn());
    }

    @Test
    void rollbackRestoresADeletedSecretWithItsOriginalArnAndVersions() {
        Secret original = service.createSecret(NAME, "original-v1", null, null, null, null,
                "redshift-serverless", REGION);
        service.putSecretValue(original.getArn(), "original-v2", null, null, REGION, null);
        Secret snapshot = mapper.convertValue(original, Secret.class);
        service.deleteSecret(original.getArn(), null, true, REGION);

        service.restoreManagedSecret(snapshot, REGION);

        assertEquals(mapper.valueToTree(snapshot), mapper.valueToTree(service.describeSecret(NAME, REGION)));
    }

    @Test
    void rollbackRestoresTheSnapshotWhenTheCurrentArnMatches() {
        Secret original = service.createSecret(NAME, "original", null, null, null, null,
                "redshift-serverless", REGION);
        Secret snapshot = mapper.convertValue(original, Secret.class);
        SecretVersion changed = service.putSecretValue(original.getArn(), "changed", null, null, REGION, null);
        assertNotEquals(snapshot.getCurrentVersionId(), changed.getVersionId());

        service.restoreManagedSecret(snapshot, REGION);

        assertEquals(mapper.valueToTree(snapshot), mapper.valueToTree(service.describeSecret(NAME, REGION)));
    }

    private static final class ParkingStorage extends InMemoryStorage<String, Secret> {
        private final AtomicBoolean parkNextWrite = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public void put(String key, Secret value) {
            if (parkNextWrite.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    assertTrue(released.await(10, TimeUnit.SECONDS), "Pending write was not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            super.put(key, value);
        }
    }

    private static final class Watched {
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Watched(Runnable action) {
            thread = new Thread(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            thread.setDaemon(true);
        }

        boolean awaitBlocked() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (thread.getState() == Thread.State.BLOCKED) {
                    return true;
                }
                if (!thread.isAlive()) {
                    return false;
                }
                Thread.sleep(5);
            }
            return false;
        }

        void awaitSuccess() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            assertTrue(!thread.isAlive(), "Secret operation did not finish");
            assertNull(failure.get(), () -> "Secret operation failed: " + failure.get());
        }
    }
}
