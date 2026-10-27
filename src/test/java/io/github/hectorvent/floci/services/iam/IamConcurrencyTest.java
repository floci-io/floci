package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Concurrent mutation of every shared IAM entity collection (tags, inline policies,
 * attachments, group membership, policy versions, instance-profile roles) must not lose
 * or corrupt entries. Each scenario runs over many trials to surface the race reliably.
 */
class IamConcurrencyTest {

    private static final int TRIALS = 200;
    private static final int N = 24;
    private static final int THREADS = 8;

    private static IamService newIamService() {
        return new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("eu-central-1", "000000000000"), false);
    }

    /** Sets up one entity on the given service and returns the i-th concurrent mutation. */
    private interface RaceSetup {
        IntConsumer prepare(IamService iam);
    }

    @TestFactory
    Stream<DynamicTest> everyIamCollectionStaysRaceFree() {
        record Scenario(String name, RaceSetup setup, ToIntFunction<IamService> finalCount) {}

        List<Scenario> scenarios = List.of(
                new Scenario("role.tags", iam -> {
                    iam.createRole("r", "/", "{}", null, 3600, null);
                    return i -> iam.tagRole("r", Map.of("k" + i, "v" + i));
                }, iam -> iam.getRole("r").getTags().size()),

                new Scenario("role.inlinePolicies", iam -> {
                    iam.createRole("r", "/", "{}", null, 3600, null);
                    return i -> iam.putRolePolicy("r", "p" + i, "{\"S\":" + i + "}");
                }, iam -> iam.listRolePolicies("r").size()),

                new Scenario("role.attachedPolicyArns", iam -> {
                    iam.createRole("r", "/", "{}", null, 3600, null);
                    String[] arns = createPolicies(iam, "rp");
                    return i -> iam.attachRolePolicy("r", arns[i]);
                }, iam -> iam.listAttachedRolePolicies("r", null).size()),

                new Scenario("user.tags", iam -> {
                    iam.createUser("u", "/");
                    return i -> iam.tagUser("u", Map.of("k" + i, "v" + i));
                }, iam -> iam.getUser("u").getTags().size()),

                new Scenario("user.inlinePolicies", iam -> {
                    iam.createUser("u", "/");
                    return i -> iam.putUserPolicy("u", "p" + i, "{\"S\":" + i + "}");
                }, iam -> iam.listUserPolicies("u").size()),

                new Scenario("user.attachedPolicyArns", iam -> {
                    iam.createUser("u", "/");
                    String[] arns = createPolicies(iam, "up");
                    return i -> iam.attachUserPolicy("u", arns[i]);
                }, iam -> iam.listAttachedUserPolicies("u", null).size()),

                new Scenario("user.groupNames", iam -> {
                    iam.createUser("u", "/");
                    for (int i = 0; i < N; i++) iam.createGroup("g" + i, "/");
                    return i -> iam.addUserToGroup("g" + i, "u");
                }, iam -> iam.getUser("u").getGroupNames().size()),

                new Scenario("group.userNames", iam -> {
                    iam.createGroup("g", "/");
                    for (int i = 0; i < N; i++) iam.createUser("u" + i, "/");
                    return i -> iam.addUserToGroup("g", "u" + i);
                }, iam -> iam.getGroup("g").getUserNames().size()),

                new Scenario("group.inlinePolicies", iam -> {
                    iam.createGroup("g", "/");
                    return i -> iam.putGroupPolicy("g", "p" + i, "{\"S\":" + i + "}");
                }, iam -> iam.listGroupPolicies("g").size()),

                new Scenario("group.attachedPolicyArns", iam -> {
                    iam.createGroup("g", "/");
                    String[] arns = createPolicies(iam, "gp");
                    return i -> iam.attachGroupPolicy("g", arns[i]);
                }, iam -> iam.listAttachedGroupPolicies("g", null).size()),

                new Scenario("policy.tags", iam -> {
                    IamPolicy p = iam.createPolicy("pol", "/", null, "{}", null);
                    return i -> iam.tagPolicy(p.getArn(), Map.of("k" + i, "v" + i));
                }, iam -> iam.getPolicy(onlyPolicyArn(iam)).getTags().size())
        );

        return scenarios.stream().map(s -> DynamicTest.dynamicTest(s.name(), () -> runRace(s.name(), s.setup(), s.finalCount())));
    }

    private void runRace(String name, RaceSetup setup, ToIntFunction<IamService> finalCount) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int trial = 0; trial < TRIALS; trial++) {
                IamService iam = newIamService();
                IntConsumer op = setup.prepare(iam);

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(N);
                List<Throwable> errors = new CopyOnWriteArrayList<>();

                for (int i = 0; i < N; i++) {
                    int idx = i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            op.accept(idx);
                        } catch (Throwable t) {
                            errors.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), name + ": workers stalled (HashMap CPU spin)");

                int actual;
                try {
                    actual = finalCount.applyAsInt(iam);
                } catch (Throwable t) {
                    fail(name + " trial " + trial + ": reading final state threw (corrupted collection): " + t);
                    return;
                }
                if (actual != N || !errors.isEmpty()) {
                    fail(name + " trial " + trial + ": expected " + N + " entries, got " + actual
                            + "; mutation errors=" + errors.size()
                            + (errors.isEmpty() ? "" : " e.g. " + errors.get(0)));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void policyVersionsStayRaceFree() throws Exception {
        int trials = 400;
        int extraVersions = 4; // v1 is seeded by createPolicy; cap is 5
        ExecutorService pool = Executors.newFixedThreadPool(extraVersions);
        try {
            for (int trial = 0; trial < trials; trial++) {
                IamService iam = newIamService();
                IamPolicy p = iam.createPolicy("pol", "/", null, "{}", null);
                String arn = p.getArn();

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(extraVersions);
                List<Throwable> errors = new CopyOnWriteArrayList<>();
                for (int i = 0; i < extraVersions; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            iam.createPolicyVersion(arn, "{\"v\":true}", false);
                        } catch (Throwable t) {
                            errors.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "version workers stalled");

                List<?> versions = iam.listPolicyVersions(arn);
                Set<String> ids = new HashSet<>();
                iam.getPolicy(arn).getVersions().keySet().forEach(ids::add);

                if (!errors.isEmpty() || versions.size() != 1 + extraVersions || ids.size() != 1 + extraVersions) {
                    fail("policy.versions trial " + trial + ": expected " + (1 + extraVersions)
                            + " distinct versions, got list=" + versions.size() + " distinctIds=" + ids.size()
                            + "; errors=" + errors.size() + (errors.isEmpty() ? "" : " e.g. " + errors.get(0)));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void instanceProfileRoleNamesStayRaceFree() throws Exception {
        int trials = 400;
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int trial = 0; trial < trials; trial++) {
                IamService iam = newIamService();
                iam.createInstanceProfile("prof", "/");
                for (int i = 0; i < racers; i++) iam.createRole("role" + i, "/", "{}", null, 3600, null);

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(racers);
                List<Throwable> unexpected = new CopyOnWriteArrayList<>();
                for (int i = 0; i < racers; i++) {
                    int idx = i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            iam.addRoleToInstanceProfile("prof", "role" + idx);
                        } catch (AwsException expected) {
                            // LimitExceeded once one role is in
                        } catch (Throwable t) {
                            unexpected.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "profile workers stalled");

                int roles = iam.getInstanceProfile("prof").getRoleNames().size();
                if (roles != 1 || !unexpected.isEmpty()) {
                    fail("instanceProfile.roleNames trial " + trial + ": cap violated/corrupted, roleNames="
                            + roles + " unexpectedErrors=" + unexpected.size());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Only one caller may create a provider for a given URL. Racing creates carry different
     * client IDs, so an unguarded check-then-write would report success to several callers while
     * storing one arbitrary payload.
     */
    @Test
    void concurrentOidcProviderCreatesLeaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int trial = 0; trial < TRIALS; trial++) {
                IamService iam = newIamService();
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(N);
                List<String> winners = new CopyOnWriteArrayList<>();
                List<String> unexpected = new CopyOnWriteArrayList<>();

                for (int i = 0; i < N; i++) {
                    String clientId = "client-" + i;
                    pool.execute(() -> {
                        try {
                            start.await(10, TimeUnit.SECONDS);
                            iam.createOpenIDConnectProvider("https://oidc.example.com/id/RACE",
                                    List.of(clientId), List.of("thumb"), Map.of());
                            winners.add(clientId);
                        } catch (AwsException e) {
                            if (!"EntityAlreadyExists".equals(e.getErrorCode())) {
                                unexpected.add(e.getErrorCode());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "oidc workers stalled");

                List<String> storedClientIds = iam.listOpenIDConnectProviders().getFirst().getClientIdList();
                if (winners.size() != 1 || !storedClientIds.equals(winners) || !unexpected.isEmpty()) {
                    fail("oidcProvider trial " + trial + ": winners=" + winners
                            + " stored=" + storedClientIds + " unexpectedErrors=" + unexpected);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Creating a free alias replaces whatever is set, so concurrent creates of different aliases
     * all succeed and the last write wins — that is AWS behaviour, not a race to be prevented.
     * What must hold is that the store ends on exactly one of the requested values rather than a
     * torn or absent one.
     */
    @Test
    void concurrentAccountAliasCreatesLeaveOneRequestedValue() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int trial = 0; trial < TRIALS; trial++) {
                IamService iam = newIamService();
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(N);
                List<String> winners = new CopyOnWriteArrayList<>();
                List<String> unexpected = new CopyOnWriteArrayList<>();

                for (int i = 0; i < N; i++) {
                    String alias = "alias-" + i;
                    pool.execute(() -> {
                        try {
                            start.await(10, TimeUnit.SECONDS);
                            iam.createAccountAlias(alias);
                            winners.add(alias);
                        } catch (AwsException e) {
                            if (!"EntityAlreadyExists".equals(e.getErrorCode())) {
                                unexpected.add(e.getErrorCode());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "alias workers stalled");

                String stored = iam.getAccountAlias().orElse(null);
                if (winners.size() != N || stored == null || !winners.contains(stored)
                        || !unexpected.isEmpty()) {
                    fail("accountAlias trial " + trial + ": winners=" + winners.size() + "/" + N
                            + " stored=" + stored + " unexpectedErrors=" + unexpected);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Every racing ChangePassword presents the same valid old password, so exactly one can be
     * right: once the first write lands, the old password the others hold is no longer the current
     * one. A request told it succeeded has to be the one whose password is in the store, since the
     * caller has no way to find out otherwise, the operation echoing nothing back.
     */
    @Test
    void concurrentPasswordChangesLeaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int trial = 0; trial < TRIALS; trial++) {
                IamService iam = newIamService();
                iam.createUser("racer", "/");
                iam.createLoginProfile("racer", "Passw0rd$start", false);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(N);
                List<String> winners = new CopyOnWriteArrayList<>();
                List<String> unexpected = new CopyOnWriteArrayList<>();

                for (int i = 0; i < N; i++) {
                    String candidate = "Passw0rd$" + i;
                    pool.execute(() -> {
                        try {
                            start.await(10, TimeUnit.SECONDS);
                            iam.changePassword("racer", "Passw0rd$start", candidate);
                            winners.add(candidate);
                        } catch (AwsException e) {
                            if (!"InvalidParameterValue".equals(e.getErrorCode())) {
                                unexpected.add(e.getErrorCode());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "password workers stalled");

                String stored = iam.getLoginProfile("racer").getPassword();
                if (winners.size() != 1 || !winners.contains(stored) || !unexpected.isEmpty()) {
                    fail("changePassword trial " + trial + ": winners=" + winners
                            + " stored=" + stored + " unexpectedErrors=" + unexpected);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anUpdateCannotResurrectADeletedAccessKey() throws Exception {
        // UpdateAccessKey reads a key, sets its status and writes it back, so without a lock the
        // write lands after a delete that ran in between and the credential comes back. Measured
        // at 80 of 2000 trials before the lock.
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int trial = 0; trial < 2000; trial++) {
                IamService iam = newIamService();
                iam.createUser("racer", "/");
                String keyId = iam.createAccessKey("racer").getAccessKeyId();
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<String> unexpected = new CopyOnWriteArrayList<>();

                pool.execute(raceStep(start, done, unexpected,
                        () -> iam.updateAccessKey("racer", keyId, "Inactive")));
                pool.execute(raceStep(start, done, unexpected,
                        () -> iam.deleteAccessKey("racer", keyId)));
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "access key workers stalled");

                if (!iam.listAccessKeys("racer").isEmpty() || !unexpected.isEmpty()) {
                    fail("access key trial " + trial + ": the deleted key is still listed"
                            + ", unexpectedErrors=" + unexpected);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentAccessKeyCreatesStayInsideTheQuota() throws Exception {
        // AccessKeysPerUser is 2, counted and then written, so without a lock four callers racing
        // the count all pass it. Measured at 226 of 500 trials before the lock.
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int trial = 0; trial < 500; trial++) {
                IamService iam = newIamService();
                iam.createUser("racer", "/");
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(4);
                List<String> unexpected = new CopyOnWriteArrayList<>();

                for (int i = 0; i < 4; i++) {
                    pool.execute(raceStep(start, done, unexpected, () -> iam.createAccessKey("racer")));
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "access key workers stalled");

                int stored = iam.listAccessKeys("racer").size();
                if (stored != 2 || !unexpected.isEmpty()) {
                    fail("access key quota trial " + trial + ": stored=" + stored
                            + " unexpectedErrors=" + unexpected);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRenameCannotResurrectADeletedAccessKey() throws Exception {
        // UpdateUser rewrites each key's owner. Reading that list before taking the lock lets a
        // DeleteAccessKey land in between, and the loop then writes the deleted key back under the
        // new name, where it authenticates again.
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int trial = 0; trial < 2000; trial++) {
                IamService iam = newIamService();
                iam.createUser("racer", "/");
                String keyId = iam.createAccessKey("racer").getAccessKeyId();
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<String> unexpected = new CopyOnWriteArrayList<>();
                AtomicBoolean deleted = new AtomicBoolean();

                pool.execute(raceStep(start, done, unexpected,
                        () -> iam.updateUser("racer", "renamed", null)));
                pool.execute(raceStep(start, done, unexpected, () -> {
                    iam.deleteAccessKey("racer", keyId);
                    deleted.set(true);
                }));
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "rename workers stalled");

                // The delete can lose the race, the key having moved to the new name before it
                // looked, and then the key is meant to still be there. What may never happen is a
                // delete that answered 200 and a key that is still stored.
                boolean stored = iam.findUserNameByAccessKeyId(keyId).isPresent();
                if ((deleted.get() && stored) || !unexpected.isEmpty()) {
                    fail("rename trial " + trial + ": deleted=" + deleted.get()
                            + " stillStored=" + stored + " unexpectedErrors=" + unexpected);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anAccessKeyCannotBeCreatedForAUserBeingDeleted() throws Exception {
        // CreateAccessKey confirming the user before taking the lock, and DeleteUser checking for
        // keys without holding it, leaves the pair free to interleave: the create passes the check
        // and stores a key for a user the delete has just removed.
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int trial = 0; trial < 2000; trial++) {
                IamService iam = newIamService();
                iam.createUser("racer", "/");
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<String> unexpected = new CopyOnWriteArrayList<>();
                AtomicReference<String> created = new AtomicReference<>();

                pool.execute(raceStep(start, done, unexpected,
                        () -> created.set(iam.createAccessKey("racer").getAccessKeyId())));
                pool.execute(raceStep(start, done, unexpected, () -> iam.deleteUser("racer")));
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "delete-user workers stalled");

                boolean userGone = userIsGone(iam);
                boolean orphan = userGone && created.get() != null
                        && iam.findUserNameByAccessKeyId(created.get()).isPresent();
                if (orphan || !unexpected.isEmpty()) {
                    fail("delete-user trial " + trial + ": a key is stored for a user that is gone"
                            + ", unexpectedErrors=" + unexpected);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean userIsGone(IamService iam) {
        try {
            iam.getUser("racer");
            return false;
        } catch (AwsException e) {
            return true;
        }
    }

    /**
     * One racer: waits for the start, runs the step, and keeps any error that is not one of the
     * refusals the race is allowed to produce, a key already gone, a quota already full or a user
     * that still has a key when the delete looks.
     */
    private static Runnable raceStep(CountDownLatch start, CountDownLatch done,
                                     List<String> unexpected, Runnable step) {
        return () -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                step.run();
            } catch (AwsException e) {
                if (!"NoSuchEntity".equals(e.getErrorCode())
                        && !"LimitExceeded".equals(e.getErrorCode())
                        && !"DeleteConflict".equals(e.getErrorCode())) {
                    unexpected.add(e.getErrorCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };
    }

    private static String[] createPolicies(IamService iam, String prefix) {
        String[] arns = new String[N];
        for (int i = 0; i < N; i++) {
            arns[i] = iam.createPolicy(prefix + "-" + i, "/", null, "{}", null).getArn();
        }
        return arns;
    }

    private static String onlyPolicyArn(IamService iam) {
        return iam.listPolicies(null, null).get(0).getArn();
    }
}
