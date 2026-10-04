package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Totp;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.VirtualMfaDevice;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MFA operations that read state and then write it (assigning a device, deleting the user it
 * would be assigned to, spending the per-user quota) run over many trials, because each one is a
 * check-then-act that only misbehaves when two requests interleave inside it.
 */
class VirtualMfaConcurrencyTest {

    private static final int TRIALS = 150;

    private static IamService newIamService() {
        return new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("eu-central-1", "000000000000"), false, null);
    }

    private static String[] codesFor(String seed) {
        long step = Totp.stepAt(Instant.now());
        return new String[] {
                Totp.codeAt(seed, step - 1),
                Totp.codeAt(seed, step)
        };
    }

    /**
     * Runs the given actions at once and returns how many returned without an AwsException.
     * The count matters as much as the stored state: two callers each told they won is a bug
     * even when only one owner survives in the store.
     */
    private static int raceTogether(Runnable... actions) throws InterruptedException {
        AtomicInteger succeeded = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(actions.length);
        ExecutorService pool = Executors.newFixedThreadPool(actions.length);
        try {
            for (Runnable action : actions) {
                pool.submit(() -> {
                    try {
                        start.await();
                        action.run();
                        succeeded.incrementAndGet();
                    } catch (AwsException expected) {
                        // One side of a race is meant to lose; the assertions cover both halves.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "actions did not finish");
        } finally {
            pool.shutdownNow();
        }
        return succeeded.get();
    }

    /**
     * Deleting a user races enabling a device for that user. Either order is fine; what must never
     * happen is both succeeding, which would leave a device assigned to a user that no longer
     * exists, and one that could never be deactivated, since deactivation resolves the user first.
     */
    @Test
    void deletingAUserNeverRacesADeviceOntoIt() throws InterruptedException {
        List<String> orphans = new CopyOnWriteArrayList<>();

        for (int trial = 0; trial < TRIALS; trial++) {
            IamService iam = newIamService();
            iam.createUser("racer", "/");
            VirtualMfaDevice device = iam.createVirtualMfaDevice("dev", "/", Map.of());
            String[] codes = codesFor(device.getBase32Seed());

            raceTogether(
                    () -> iam.deleteUser("racer"),
                    () -> iam.enableMfaDevice("racer", device.getSerialNumber(), codes[0], codes[1]));

            boolean userGone = iam.findUser("racer").isEmpty();
            boolean deviceAssigned = iam.getVirtualMfaDevice(device.getSerialNumber()).isAssigned();
            if (userGone && deviceAssigned) {
                orphans.add("trial " + trial);
            }
        }

        assertTrue(orphans.isEmpty(), "device left assigned to a deleted user in: " + orphans);
    }

    /**
     * Renaming a user races a delete of the name it is being renamed to. The rename publishes the
     * user under the new name and moves its devices to match; if those are split across the MFA
     * lock, a delete can land between them, see no devices, and remove a user that a device is
     * about to be reassigned to. That device would then be unreachable: deactivating it resolves
     * the user first, and deleting it is refused while it is assigned.
     */
    @Test
    void renamingAUserNeverRacesADeleteIntoStrandingItsDevice() throws InterruptedException {
        List<String> stranded = new CopyOnWriteArrayList<>();
        int reachedTheRace = 0;

        for (int trial = 0; trial < TRIALS; trial++) {
            IamService iam = newIamService();
            iam.createUser("rename-from", "/");
            VirtualMfaDevice device = iam.createVirtualMfaDevice("dev" + trial, "/", Map.of());
            String[] codes = codesFor(device.getBase32Seed());
            iam.enableMfaDevice("rename-from", device.getSerialNumber(), codes[0], codes[1]);

            // Starting both at once never reaches the window: the delete runs before the rename has
            // published anything and fails on a missing user. So the deleting side waits for the
            // new name to appear and goes the instant it does, which is exactly where the two
            // halves of the rename could be split.
            //
            // The wait is on a condition, not a spin budget: it ends when the user appears, or when
            // the rename has finished and there is nothing left to race. A slow or loaded runner
            // therefore makes this thread wait longer rather than give up, which a fixed budget
            // would have turned into a scheduling-dependent failure of the assertion below.
            AtomicReference<String> deleteOutcome = new AtomicReference<>("never-ran");
            CountDownLatch renameFinished = new CountDownLatch(1);
            raceTogether(
                    () -> {
                        try {
                            iam.updateUser("rename-from", "rename-to", null);
                        } finally {
                            renameFinished.countDown();
                        }
                    },
                    () -> {
                        while (iam.findUser("rename-to").isEmpty() && renameFinished.getCount() > 0) {
                            Thread.yield();
                        }
                        try {
                            iam.deleteUser("rename-to");
                            deleteOutcome.set("deleted");
                        } catch (AwsException e) {
                            deleteOutcome.set(e.getErrorCode());
                        }
                    });

            // NoSuchEntity means the delete never saw the renamed user, so this trial got nowhere
            // near the window. Counted rather than ignored: without it the trial would assert
            // "nothing stranded" vacuously and the whole test could pass having raced nothing.
            if (!"NoSuchEntity".equals(deleteOutcome.get())) {
                reachedTheRace++;
            }
            VirtualMfaDevice after = iam.getVirtualMfaDevice(device.getSerialNumber());
            if (after.isAssigned() && iam.findUser(after.getUserName()).isEmpty()) {
                stranded.add("trial " + trial + " -> assigned to missing user " + after.getUserName()
                        + " (delete=" + deleteOutcome.get() + ")");
            }
        }

        assertTrue(stranded.isEmpty(), "device stranded on a deleted user in: " + stranded);
        assertTrue(reachedTheRace > TRIALS / 2,
                "only " + reachedTheRace + " of " + TRIALS + " trials reached the race, so this test "
                        + "is no longer exercising it: the delete has to observe the renamed user");
    }

    /**
     * Nine devices enabled at once against a user whose quota is eight. Checked and written under
     * one lock, exactly eight can win; an unguarded check would let several read "seven" together
     * and all write.
     */
    @Test
    void theDevicesPerUserQuotaHoldsUnderConcurrentEnables() throws InterruptedException {
        for (int trial = 0; trial < 20; trial++) {
            IamService iam = newIamService();
            iam.createUser("quota-racer", "/");
            Runnable[] enables = new Runnable[9];
            for (int i = 0; i < enables.length; i++) {
                VirtualMfaDevice device = iam.createVirtualMfaDevice("dev" + i, "/", Map.of());
                String[] codes = codesFor(device.getBase32Seed());
                enables[i] = () -> iam.enableMfaDevice("quota-racer", device.getSerialNumber(),
                        codes[0], codes[1]);
            }

            raceTogether(enables);

            assertEquals(8, iam.listMfaDevices("quota-racer").size(),
                    "the per-user quota let more than eight devices through");
        }
    }

    /**
     * Two users reach for the same unassigned device at once. Only one call may return success:
     * the loser has to get EntityAlreadyExists, because a caller told its EnableMFADevice
     * succeeded will go on believing it holds that device.
     */
    @Test
    void onlyOneOfTwoUsersIsToldItClaimedTheDevice() throws InterruptedException {
        for (int trial = 0; trial < TRIALS; trial++) {
            IamService iam = newIamService();
            iam.createUser("first", "/");
            iam.createUser("second", "/");
            VirtualMfaDevice device = iam.createVirtualMfaDevice("contended", "/", Map.of());
            String[] codes = codesFor(device.getBase32Seed());

            int won = raceTogether(
                    () -> iam.enableMfaDevice("first", device.getSerialNumber(), codes[0], codes[1]),
                    () -> iam.enableMfaDevice("second", device.getSerialNumber(), codes[0], codes[1]));

            assertEquals(1, won, "both callers were told they claimed the same device");
            assertEquals(1, iam.listMfaDevices("first").size() + iam.listMfaDevices("second").size());
        }
    }

    /**
     * Creating the same name twice at once must leave one device and tell the loser
     * EntityAlreadyExists. Two successes would mean the second create silently replaced the
     * first caller's device, invalidating a seed it had already been handed.
     */
    @Test
    void onlyOneOfTwoIdenticalCreatesSucceeds() throws InterruptedException {
        for (int trial = 0; trial < TRIALS; trial++) {
            IamService iam = newIamService();

            int won = raceTogether(
                    () -> iam.createVirtualMfaDevice("same", "/", Map.of()),
                    () -> iam.createVirtualMfaDevice("same", "/", Map.of()));

            assertEquals(1, won, "both creates succeeded, so one seed was silently replaced");
            assertEquals(1, iam.listVirtualMfaDevices("Any").size());
        }
    }
}
