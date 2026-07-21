package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Concurrent PutRolePolicy on the same role must not lose or corrupt inline policies:
 * a policy that PutRolePolicy acked has to stay visible to a later GetRolePolicy.
 */
class IamRolePolicyConcurrencyTest {

    private static IamService newIamService() {
        return new IamService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("eu-central-1", "000000000000"),
                false
        );
    }

    @Test
    void concurrentPutRolePolicyOnSameRoleStaysReadable() throws Exception {
        int trials = 300;
        int policiesPerTrial = 32;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int trial = 0; trial < trials; trial++) {
                IamService iam = newIamService();
                String roleName = "repro-role-" + trial;
                iam.createRole(roleName, "/", "{}", null, 3600, null);

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(policiesPerTrial);
                List<String> lostOnReadBack = new CopyOnWriteArrayList<>();
                List<Throwable> corruption = new CopyOnWriteArrayList<>();

                for (int i = 0; i < policiesPerTrial; i++) {
                    String policyName = "inline-" + i;
                    String doc = "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"secretsmanager:GetSecretValue\","
                            + "\"Resource\":\"*\",\"Sid\":\"" + policyName + "\"}]}";
                    pool.submit(() -> {
                        try {
                            start.await();
                            iam.putRolePolicy(roleName, policyName, doc);
                            try {
                                iam.getRolePolicy(roleName, policyName);
                            } catch (AwsException e) {
                                lostOnReadBack.add(policyName);
                            }
                        } catch (Throwable t) {
                            corruption.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }

                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS),
                        "trial " + trial + ": workers stalled (possible HashMap CPU spin under concurrent resize)");

                int finalCount;
                try {
                    finalCount = iam.getRole(roleName).getInlinePolicies().size();
                } catch (Throwable t) {
                    finalCount = -1;
                    corruption.add(t);
                }

                if (!lostOnReadBack.isEmpty() || !corruption.isEmpty() || finalCount != policiesPerTrial) {
                    System.out.println("[repro] FAILED on trial " + trial);
                    System.out.println("[repro]   NoSuchEntity on read-back : " + lostOnReadBack.size()
                            + " " + sorted(lostOnReadBack));
                    System.out.println("[repro]   corruption exceptions     : " + corruption.size()
                            + (corruption.isEmpty() ? "" : " e.g. " + corruption.get(0)));
                    System.out.println("[repro]   inline policies retained  : " + finalCount + " / " + policiesPerTrial);
                    fail("Concurrent PutRolePolicy on one role lost/corrupted inline policies "
                            + "(unsynchronized read-modify-write in IamService.putRolePolicy). "
                            + "NoSuchEntity reads=" + sorted(lostOnReadBack)
                            + ", retained=" + finalCount + "/" + policiesPerTrial
                            + ", corruption=" + corruption.size());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<String> sorted(List<String> in) {
        List<String> copy = new java.util.ArrayList<>(in);
        Collections.sort(copy);
        return copy;
    }
}
