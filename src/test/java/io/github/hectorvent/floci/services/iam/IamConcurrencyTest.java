package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
                new InMemoryStorage<>(), new RegionResolver("eu-central-1", "000000000000"), false);
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
                    return i -> iam.tagRole("r", java.util.Map.of("k" + i, "v" + i));
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
                    return i -> iam.tagUser("u", java.util.Map.of("k" + i, "v" + i));
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
                    return i -> iam.tagPolicy(p.getArn(), java.util.Map.of("k" + i, "v" + i));
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
                        } catch (io.github.hectorvent.floci.core.common.AwsException expected) {
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
