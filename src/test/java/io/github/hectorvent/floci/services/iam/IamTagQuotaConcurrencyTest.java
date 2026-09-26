package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two tag requests that each fit the 50-tag quota must not together push one resource past it.
 * Each pair is released from a shared latch so both merge-and-check steps overlap.
 */
class IamTagQuotaConcurrencyTest {

    private static final int TRIALS = 200;
    private static final String POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
    private static final String POLICY_ARN = "arn:aws:iam::000000000000:policy/p";

    private static IamService newIamService() {
        return new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("eu-central-1", "000000000000"), false);
    }

    private static Map<String, String> thirtyTags(String prefix) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 1; i <= 30; i++) {
            tags.put(prefix + i, "v");
        }
        return tags;
    }

    private record Scenario(String name, Consumer<IamService> create,
            BiConsumer<IamService, Map<String, String>> tagger, ToIntFunction<IamService> count) {}

    @TestFactory
    Stream<DynamicTest> racingTagRequestsNeverExceedTheQuota() {
        List<Scenario> scenarios = List.of(
                new Scenario("user", iam -> iam.createUser("u", "/"),
                        (iam, tags) -> iam.tagUser("u", tags), iam -> iam.getUser("u").getTags().size()),
                new Scenario("role", iam -> iam.createRole("r", "/", "{}", null, 3600, null),
                        (iam, tags) -> iam.tagRole("r", tags), iam -> iam.getRole("r").getTags().size()),
                new Scenario("policy", iam -> iam.createPolicy("p", "/", null, POLICY_DOCUMENT, null),
                        (iam, tags) -> iam.tagPolicy(POLICY_ARN, tags), iam -> iam.getPolicy(POLICY_ARN).getTags().size()),
                new Scenario("instanceProfile", iam -> iam.createInstanceProfile("p", "/"),
                        (iam, tags) -> iam.tagInstanceProfile("p", tags),
                        iam -> iam.getInstanceProfile("p").getTags().size()));

        return scenarios.stream().map(s -> DynamicTest.dynamicTest(s.name(), () -> {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int trial = 0; trial < TRIALS; trial++) {
                    IamService iam = newIamService();
                    s.create().accept(iam);
                    CountDownLatch start = new CountDownLatch(1);
                    Future<Boolean> first = pool.submit(() -> tagAfter(start, () -> s.tagger().accept(iam, thirtyTags("a"))));
                    Future<Boolean> second = pool.submit(() -> tagAfter(start, () -> s.tagger().accept(iam, thirtyTags("b"))));
                    start.countDown();
                    int succeeded = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);

                    assertEquals(1, succeeded, s.name() + " trial " + trial + ": exactly one request fits");
                    int stored = s.count().applyAsInt(iam);
                    assertTrue(stored <= 50, s.name() + " trial " + trial + ": stored " + stored + " tags");
                }
            } finally {
                pool.shutdownNow();
            }
        }));
    }

    private static boolean tagAfter(CountDownLatch start, Runnable tag) throws InterruptedException {
        start.await();
        try {
            tag.run();
            return true;
        } catch (AwsException e) {
            assertEquals("LimitExceeded", e.getErrorCode());
            return false;
        }
    }
}
