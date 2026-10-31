package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ManagedSecretRotationServiceTest {
    private static final String REGION = "us-east-1";

    @Test
    void rotatesOwnerAndPublishesRequestedVersionExactlyOnce() throws Exception {
        AtomicInteger changes = new AtomicInteger();
        CountDownLatch firstRelease = new CountDownLatch(1);
        ManagedSecretRotator owner = owner(rotation -> rotation.commit(() -> {
            changes.incrementAndGet();
            return "new-password";
        }, () -> fail("unexpected rollback")), firstRelease::countDown);
        SecretsManagerService service = service(owner);
        try {
            Secret original = create(service);
            String previous = original.getCurrentVersionId();
            String token = UUID.randomUUID().toString();
            service.rotateSecret(original.getArn(), token, null, rules(), true, REGION);
            await(() -> service.describeSecret(original.getArn(), REGION).getLastRotatedDate() != null);
            assertEquals(token, service.describeSecret(original.getArn(), REGION).getCurrentVersionId());
            assertEquals("new-password", service.getSecretValue(original.getArn(), null, null, REGION).getSecretString());
            assertEquals(previous, service.getSecretValue(original.getArn(), null, "AWSPREVIOUS", REGION).getVersionId());
            service.rotateSecret(original.getArn(), token, null, null, true, REGION);
            assertEquals(1, changes.get());
            assertTrue(firstRelease.await(5, TimeUnit.SECONDS));
            String secondToken = UUID.randomUUID().toString();
            service.rotateSecret(original.getArn(), secondToken, null, null, true, REGION);
            await(() -> secondToken.equals(service.describeSecret(original.getArn(), REGION).getCurrentVersionId()));
            assertEquals(token, service.getSecretValue(original.getArn(), null, "AWSPREVIOUS", REGION).getVersionId());
            service.rotateSecret(original.getArn(), token, null, null, true, REGION);
            assertEquals(secondToken, service.describeSecret(original.getArn(), REGION).getCurrentVersionId());
            assertEquals(2, changes.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void sameTokenRetryDuringReservationDoesNotReserveOrRotateTwice() throws Exception {
        CountDownLatch reserving = new CountDownLatch(1);
        CountDownLatch finishReservation = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger reservations = new AtomicInteger();
        AtomicInteger changes = new AtomicInteger();
        ManagedSecretRotator owner = new ManagedSecretRotator() {
            public boolean supports(String arn, String region) {
                return true;
            }

            public void reserve(String arn, String region) {
                if (reservations.incrementAndGet() != 1) {
                    throw new AwsException("InvalidRequestException", "Owner already reserved", 400);
                }
                blockReserve(reserving, finishReservation);
            }

            public void rotate(String arn, String region, Rotation rotation) {
                rotation.commit(() -> {
                    changes.incrementAndGet();
                    return "new-password";
                }, () -> fail("unexpected rollback"));
            }

            public void release(String arn, String region) {
                released.countDown();
            }
        };
        SecretsManagerService service = service(owner);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Secret original = create(service);
            String token = UUID.randomUUID().toString();
            Future<Secret> first = executor.submit(() -> service.rotateSecret(
                    original.getArn(), token, null, rules(), true, REGION));
            try {
                assertTrue(reserving.await(5, TimeUnit.SECONDS));
                assertDoesNotThrow(() -> service.rotateSecret(original.getArn(), token, null, rules(), true, REGION));
                assertThrows(AwsException.class, () -> service.rotateSecret(original.getArn(),
                        UUID.randomUUID().toString(), null, rules(), true, REGION));
                assertDoesNotThrow(() -> service.putSecretValue(
                        original.getArn(), "updated-password", null, null, REGION, null));
                assertEquals("updated-password",
                        service.getSecretValue(original.getArn(), null, null, REGION).getSecretString());
            } finally {
                finishReservation.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            assertTrue(released.await(5, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> service.rotateSecret(original.getArn(), token, null, rules(), true, REGION));
            assertEquals(1, reservations.get());
            assertEquals(1, changes.get());
            assertEquals(token, service.describeSecret(original.getArn(), REGION).getCurrentVersionId());
        } finally {
            finishReservation.countDown();
            service.shutdown();
        }
    }

    @Test
    void ownerCanDeleteSecretWhileRotationWaitsForReservation() throws Exception {
        CountDownLatch reserving = new CountDownLatch(1);
        CountDownLatch finishReservation = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger();
        SecretsManagerService service = service(new ManagedSecretRotator() {
            public boolean supports(String arn, String region) {
                return true;
            }

            public void reserve(String arn, String region) {
                blockReserve(reserving, finishReservation);
                throw new AwsException("ResourceNotFoundException", "Owner deleted", 400);
            }

            public void rotate(String arn, String region, Rotation rotation) {
                fail("deleted owner's secret must not rotate");
            }

            public void release(String arn, String region) {
                releases.incrementAndGet();
            }
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Secret original = create(service);
            Future<AwsException> rotation = executor.submit(() -> assertThrows(AwsException.class,
                    () -> service.rotateSecret(original.getArn(), UUID.randomUUID().toString(),
                            null, rules(), true, REGION)));
            try {
                assertTrue(reserving.await(5, TimeUnit.SECONDS));
                assertTrue(service.deleteManagedSecretAfter(original.getArn(), REGION, () -> { }));
                AwsException missing = assertThrows(AwsException.class,
                        () -> service.describeSecret(original.getArn(), REGION));
                assertEquals("ResourceNotFoundException", missing.getErrorCode());
            } finally {
                finishReservation.countDown();
            }
            assertEquals("ResourceNotFoundException", rotation.get(5, TimeUnit.SECONDS).getErrorCode());
            assertEquals(0, releases.get());
        } finally {
            finishReservation.countDown();
            service.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void versionSavedDuringReservationSurvivesWithoutRunningOwner(boolean ownerFails) throws Exception {
        CountDownLatch reserving = new CountDownLatch(1);
        CountDownLatch finishReservation = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger changes = new AtomicInteger();
        AtomicInteger reservations = new AtomicInteger();
        SecretsManagerService service = service(new ManagedSecretRotator() {
            public boolean supports(String arn, String region) {
                return true;
            }

            public void reserve(String arn, String region) {
                if (reservations.incrementAndGet() > 1) {
                    throw new AwsException("InvalidRequestException", "Owner unavailable", 400);
                }
                blockReserve(reserving, finishReservation);
            }

            public void rotate(String arn, String region, Rotation rotation) {
                changes.incrementAndGet();
                if (ownerFails) {
                    throw new IllegalStateException("backend refused password");
                }
                rotation.commit(() -> "rotated-password", () -> fail("unexpected rollback"));
            }

            public void release(String arn, String region) {
                released.countDown();
            }
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Secret original = create(service);
            String token = UUID.randomUUID().toString();
            Future<Secret> rotation = executor.submit(() -> service.rotateSecret(
                    original.getArn(), token, null, rules(), true, REGION));
            try {
                assertTrue(reserving.await(5, TimeUnit.SECONDS));
                service.putSecretValue(original.getArn(), "saved-password", null, token, REGION, null);
            } finally {
                finishReservation.countDown();
            }
            Secret result = rotation.get(5, TimeUnit.SECONDS);
            assertTrue(released.await(5, TimeUnit.SECONDS));
            assertEquals("saved-password",
                    service.getSecretValue(original.getArn(), token, null, REGION).getSecretString());
            assertEquals(token, result.getCurrentVersionId());
            assertEquals(0, changes.get());
            assertFalse(result.isRotationEnabled());
            assertNull(result.getLastRotatedDate());
            service.rotateSecret(original.getArn(), token, null, rules(), true, REGION);
            assertEquals(1, reservations.get());
            assertDoesNotThrow(() -> service.putSecretValue(
                    original.getArn(), "updated-password", null, null, REGION, null));
            AwsException unavailable = assertThrows(AwsException.class, () -> service.rotateSecret(
                    original.getArn(), UUID.randomUUID().toString(), null, rules(), true, REGION));
            assertEquals("Owner unavailable", unavailable.getMessage());
            assertEquals(2, reservations.get());
        } finally {
            finishReservation.countDown();
            service.shutdown();
        }
    }

    @Test
    void failedReservationAllowsCredentialUpdatesAndSameTokenRetry() throws Exception {
        AtomicInteger reservations = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch released = new CountDownLatch(1);
        SecretsManagerService service = service(new ManagedSecretRotator() {
            public boolean supports(String arn, String region) {
                return true;
            }

            public void reserve(String arn, String region) {
                if (reservations.incrementAndGet() == 1) {
                    throw new AwsException("InvalidRequestException", "Owner unavailable", 400);
                }
            }

            public void rotate(String arn, String region, Rotation rotation) {
                rotation.commit(() -> "new-password", () -> fail("unexpected rollback"));
            }

            public void release(String arn, String region) {
                releases.incrementAndGet();
                released.countDown();
            }
        });
        try {
            Secret original = create(service);
            String token = UUID.randomUUID().toString();
            assertThrows(AwsException.class, () -> service.rotateSecret(
                    original.getArn(), token, null, rules(), true, REGION));
            assertEquals(0, releases.get());
            assertFalse(service.describeSecret(original.getArn(), REGION).isRotationEnabled());
            assertDoesNotThrow(() -> service.putSecretValue(original.getArn(), "updated-password", null, null,
                    REGION, null));
            service.rotateSecret(original.getArn(), token, null, rules(), true, REGION);
            assertTrue(released.await(5, TimeUnit.SECONDS));
            assertEquals(2, reservations.get());
            assertEquals(1, releases.get());
            assertEquals(token, service.describeSecret(original.getArn(), REGION).getCurrentVersionId());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void activeRotationBlocksMutationsAndCancellationSurvivesPublication() throws Exception {
        CountDownLatch changing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        SecretsManagerService service = service(owner(rotation -> rotation.commit(() -> {
            changing.countDown();
            try {
                assertTrue(finish.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return "new-password";
        }, () -> { })));
        try {
            Secret original = create(service);
            String activeToken = UUID.randomUUID().toString();
            service.rotateSecret(original.getArn(), activeToken, null, rules(), true, REGION);
            assertTrue(changing.await(5, TimeUnit.SECONDS));
            assertThrows(AwsException.class, () -> service.rotateSecret(original.getArn(),
                    UUID.randomUUID().toString(), null, rules(), true, REGION));
            assertThrows(AwsException.class, () -> service.putSecretValue(original.getArn(), "other", null, null, REGION, null));
            assertThrows(AwsException.class, () -> service.updateSecret(original.getArn(), "other", null, REGION));
            assertThrows(AwsException.class, () -> service.deleteSecret(original.getArn(), null, true, REGION));
            service.cancelRotateSecret(original.getArn(), REGION);
            finish.countDown();
            await(() -> service.describeSecret(original.getArn(), REGION).getLastRotatedDate() != null);
            assertFalse(service.describeSecret(original.getArn(), REGION).isRotationEnabled());
            assertNull(service.describeSecret(original.getArn(), REGION).getNextRotationDate());
        } finally {
            finish.countDown();
            service.shutdown();
        }
    }

    @Test
    void failedOwnerChangeRollsBackAndSameTokenCanRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        CountDownLatch firstFailure = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        SecretsManagerService service = service(owner(rotation -> {
            try {
                rotation.commit(() -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("backend refused password");
                    }
                    return "new-password";
                }, rollbacks::incrementAndGet);
            } catch (IllegalStateException e) {
                firstFailure.countDown();
                throw e;
            }
        }, firstRelease::countDown));
        try {
            Secret original = create(service);
            String token = UUID.randomUUID().toString();
            service.rotateSecret(original.getArn(), token, null, rules(), true, REGION);
            assertTrue(firstFailure.await(5, TimeUnit.SECONDS));
            assertTrue(firstRelease.await(5, TimeUnit.SECONDS));
            await(() -> rollbacks.get() == 1);
            assertTrue(service.describeSecret(original.getArn(), REGION).isRotationEnabled());
            assertEquals("old-password", service.getSecretValue(original.getArn(), null, null, REGION).getSecretString());
            assertNull(service.describeSecret(original.getArn(), REGION).getLastRotatedDate());
            assertEquals(1, rollbacks.get());
            service.rotateSecret(original.getArn(), token, null, null, true, REGION);
            await(() -> service.describeSecret(original.getArn(), REGION).getLastRotatedDate() != null);
            assertEquals(token, service.describeSecret(original.getArn(), REGION).getCurrentVersionId());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void configureOnlyDoesNotChangePasswordAndSchedulerRotatesSupportedOwners() throws Exception {
        AtomicInteger changes = new AtomicInteger();
        SecretsManagerService service = service(owner(rotation -> rotation.commit(() -> {
            changes.incrementAndGet();
            return "new-password";
        }, () -> { })));
        try {
            Secret original = create(service);
            service.rotateSecret(original.getArn(), UUID.randomUUID().toString(), null, rules(), false, REGION);
            assertEquals(0, changes.get());
            assertNull(original.getLastRotatedDate());
            new SecretRotationScheduler(service).tick(Instant.now().plusSeconds(31L * 86400));
            await(() -> service.describeSecret(original.getArn(), REGION).getLastRotatedDate() != null);
            assertEquals(1, changes.get());
        } finally {
            service.shutdown();
        }
    }

    private static SecretsManagerService service(ManagedSecretRotator owner) {
        return new SecretsManagerService(new InMemoryStorage<>(), 30,
                new RegionResolver(REGION, "000000000000"), null, new ObjectMapper(), null, List.of(owner));
    }

    private static Secret create(SecretsManagerService service) {
        return service.createSecret("managed", "old-password", null, null, null, null, "redshift", REGION);
    }

    private static Secret.RotationRules rules() {
        return new Secret.RotationRules(30, null, null);
    }

    private static ManagedSecretRotator owner(Consumer<ManagedSecretRotator.Rotation> action) {
        return owner(action, () -> { });
    }

    private static ManagedSecretRotator owner(Consumer<ManagedSecretRotator.Rotation> action, Runnable releaseAction) {
        return new ManagedSecretRotator() {
            public boolean supports(String arn, String region) {
                return true;
            }

            public void rotate(String arn, String region, Rotation rotation) {
                action.accept(rotation);
            }

            public void release(String arn, String region) {
                releaseAction.run();
            }
        };
    }

    private static void blockReserve(CountDownLatch reserving, CountDownLatch finishReservation) {
        reserving.countDown();
        try {
            assertTrue(finishReservation.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "rotation did not finish");
    }
}
