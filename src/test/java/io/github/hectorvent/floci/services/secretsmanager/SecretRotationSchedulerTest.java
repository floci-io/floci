package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Covers the background sweep that makes a rotation schedule actually fire. Rotation itself runs
 * asynchronously, so these assert on the scheduling state the sweep drives - which secret is due
 * and where its next rotation lands - rather than on the rotation's outcome.
 */
class SecretRotationSchedulerTest {

    private static final String REGION = "us-east-1";
    private static final String LAMBDA_ARN = "arn:aws:lambda:us-east-1:000000000000:function:rotate";
    private static final String TOKEN = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

    private SecretsManagerService service;
    private SecretRotationScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = new SecretsManagerService(new InMemoryStorage<>(), 30,
                new RegionResolver(REGION, "000000000000"), mock(LambdaService.class), new ObjectMapper());
        scheduler = new SecretRotationScheduler(service);
    }

    private void enableRotation(String name, Secret.RotationRules rules) {
        service.createSecret(name, "v1", null, null, null, null, REGION);
        service.rotateSecret(name, TOKEN, LAMBDA_ARN, rules, false, REGION);
    }

    // ─── Next-rotation-date computation ────────────────────────────────────────

    @Test
    void enablingRotationWithAutomaticallyAfterDaysSchedulesThatManyDaysOut() {
        Instant before = Instant.now();
        enableRotation("daily", new Secret.RotationRules(14, null, null));

        Instant next = service.describeSecret("daily", REGION).getNextRotationDate();
        assertNotNull(next, "enabling rotation must schedule the next one");
        assertTrue(next.isAfter(before.plus(13, ChronoUnit.DAYS)));
        assertTrue(next.isBefore(before.plus(15, ChronoUnit.DAYS)));
    }

    @Test
    void enablingRotationWithARateExpressionSchedulesThatInterval() {
        Instant before = Instant.now();
        enableRotation("rated", new Secret.RotationRules(null, null, "rate(10 days)"));

        Instant next = service.describeSecret("rated", REGION).getNextRotationDate();
        assertNotNull(next);
        assertTrue(next.isAfter(before.plus(9, ChronoUnit.DAYS)));
        assertTrue(next.isBefore(before.plus(11, ChronoUnit.DAYS)));
    }

    @Test
    void enablingRotationWithAnHourlyRateSchedulesInHours() {
        Instant before = Instant.now();
        enableRotation("hourly", new Secret.RotationRules(null, null, "rate(12 hours)"));

        Instant next = service.describeSecret("hourly", REGION).getNextRotationDate();
        assertNotNull(next);
        assertTrue(next.isAfter(before.plus(11, ChronoUnit.HOURS)));
        assertTrue(next.isBefore(before.plus(13, ChronoUnit.HOURS)));
    }

    @Test
    void enablingRotationWithACronExpressionSchedulesTheNextMatchingTime() {
        // Every day at 16:00 UTC; the next fire is always within a day.
        Instant before = Instant.now();
        enableRotation("crony", new Secret.RotationRules(null, null, "cron(0 16 * * ? *)"));

        Instant next = service.describeSecret("crony", REGION).getNextRotationDate();
        assertNotNull(next);
        assertTrue(next.isAfter(before));
        assertTrue(next.isBefore(before.plus(25, ChronoUnit.HOURS)));
    }

    @Test
    void anUnparseableScheduleExpressionIsRejected() {
        service.createSecret("bad-cron", "v1", null, null, null, null, REGION);
        io.github.hectorvent.floci.core.common.AwsException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        io.github.hectorvent.floci.core.common.AwsException.class,
                        () -> service.rotateSecret("bad-cron", TOKEN, LAMBDA_ARN,
                                new Secret.RotationRules(null, null, "every other tuesday"),
                                false, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void cancellingRotationClearsTheSchedule() {
        enableRotation("cancelled", new Secret.RotationRules(14, null, null));
        service.cancelRotateSecret("cancelled", REGION);

        assertNull(service.describeSecret("cancelled", REGION).getNextRotationDate());
    }

    // ─── The sweep ─────────────────────────────────────────────────────────────

    /** Drags the schedule into the past so the next tick sees the secret as due. */
    private void makeDue(String name) {
        Secret secret = service.describeSecret(name, REGION);
        secret.setNextRotationDate(Instant.now().minus(1, ChronoUnit.MINUTES));
    }

    @Test
    void tickRotatesASecretWhoseScheduleHasPassed() {
        enableRotation("due", new Secret.RotationRules(14, null, null));
        makeDue("due");

        scheduler.tick(Instant.now());

        Instant next = service.describeSecret("due", REGION).getNextRotationDate();
        assertTrue(next.isAfter(Instant.now()), "a fired rotation must reschedule into the future");
    }

    @Test
    void tickLeavesASecretThatIsNotDueYet() {
        enableRotation("not-due", new Secret.RotationRules(14, null, null));
        Instant before = service.describeSecret("not-due", REGION).getNextRotationDate();

        scheduler.tick(Instant.now());

        assertEquals(before, service.describeSecret("not-due", REGION).getNextRotationDate());
    }

    @Test
    void tickIgnoresSecretsWithRotationTurnedOff() {
        service.createSecret("plain", "v1", null, null, null, null, REGION);

        scheduler.tick(Instant.now());

        assertNull(service.describeSecret("plain", REGION).getNextRotationDate());
    }

    @Test
    void tickIgnoresSecretsManagedByAnotherService() {
        // The owning service rotates these itself; floci must not invoke a Lambda for them.
        service.createSecret("rds!db-1", "v1", null, null, null, null, "rds", REGION);
        service.rotateSecret("rds!db-1", TOKEN, null, new Secret.RotationRules(1, null, null), false, REGION);
        makeDue("rds!db-1");
        Instant due = service.describeSecret("rds!db-1", REGION).getNextRotationDate();

        scheduler.tick(Instant.now());

        assertEquals(due, service.describeSecret("rds!db-1", REGION).getNextRotationDate());
    }

    @Test
    void tickIgnoresReplicas() {
        enableRotation("replicated", new Secret.RotationRules(14, null, null));
        service.replicateSecretToRegions("replicated",
                List.of(new SecretsManagerService.ReplicaRegion("eu-west-1", null)), false, REGION);

        Secret replica = service.describeSecret("replicated", "eu-west-1");
        replica.setNextRotationDate(Instant.now().minus(1, ChronoUnit.MINUTES));

        // The replica is due on paper, but rotating it would write to a read-only copy.
        scheduler.tick(Instant.now());

        assertTrue(service.describeSecret("replicated", "eu-west-1").isReplica());
    }

    @Test
    void tickIgnoresSecretsScheduledForDeletion() {
        enableRotation("doomed", new Secret.RotationRules(14, null, null));
        makeDue("doomed");
        service.deleteSecret("doomed", 7, false, REGION);
        Instant due = service.describeSecret("doomed", REGION).getNextRotationDate();

        scheduler.tick(Instant.now());

        assertEquals(due, service.describeSecret("doomed", REGION).getNextRotationDate());
    }

    @Test
    void tickRotatesASecretOwnedByANonDefaultAccount() {
        // The sweep has no request to resolve an account from, so it falls back to the default
        // one. Enumerating and writing through that fallback would leave every other account's
        // secrets unrotated - and worse, write a phantom copy into the default account.
        String foreignAccount = "111122223333";
        io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend<Secret> store =
                io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.inMemory("000000000000");
        SecretsManagerService accountAware = new SecretsManagerService(store, 30,
                new RegionResolver(REGION, "000000000000"), mock(LambdaService.class), new ObjectMapper());
        SecretRotationScheduler sweep = new SecretRotationScheduler(accountAware);

        accountAware.createSecret("foreign", "v1", null, null, null, null, REGION);
        accountAware.rotateSecret("foreign", TOKEN, LAMBDA_ARN,
                new Secret.RotationRules(14, null, null), false, REGION);

        // Re-home the secret into the other account, ARN included, and clear the default copy.
        Secret secret = accountAware.describeSecret("foreign", REGION);
        secret.setArn(secret.getArn().replace("000000000000", foreignAccount));
        secret.setNextRotationDate(Instant.now().minus(1, ChronoUnit.MINUTES));
        store.putForAccount(foreignAccount, REGION + "::foreign", secret);
        store.deleteForAccount("000000000000", REGION + "::foreign");

        sweep.tick(Instant.now());

        Secret afterSweep = store.getForAccount(foreignAccount, REGION + "::foreign").orElseThrow();
        assertTrue(afterSweep.getNextRotationDate().isAfter(Instant.now()),
                "a secret in another account must still be rotated");
        assertTrue(store.getForAccount("000000000000", REGION + "::foreign").isEmpty(),
                "rotating it must not leave a phantom copy in the default account");
    }

    @Test
    void tickSurvivesASecretThatCannotRotate() {
        // An in-flight rotation makes RotateSecret throw; the sweep must carry on to the rest.
        enableRotation("blocked", new Secret.RotationRules(14, null, null));
        service.putSecretValue("blocked", "half", null,
                "b1b2c3d4-e5f6-7890-abcd-ef1234567890", REGION, List.of("AWSPENDING"));
        makeDue("blocked");

        enableRotation("healthy", new Secret.RotationRules(14, null, null));
        makeDue("healthy");

        scheduler.tick(Instant.now());

        assertTrue(service.describeSecret("healthy", REGION).getNextRotationDate().isAfter(Instant.now()));
    }
}
