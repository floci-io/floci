package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.configservice.model.ConfigEvaluation;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PutEvaluations for the same resource/rule pair must not let an older evaluation win a race
 * against a newer one. A separate get-then-put is not atomic on the backing ConcurrentHashMap:
 * both calls can read the current value before either writes, so whichever write lands last wins
 * regardless of OrderingTimestamp.
 */
class AwsConfigServiceEvaluationConcurrencyTest {

    private static final String REGION = "us-east-1";

    @Test
    void concurrentEvaluationsForTheSameResourceKeepTheNewerOrderingTimestamp() throws Exception {
        AwsConfigService service = new AwsConfigService(new RegionResolver(REGION, "000000000000"), null);
        service.putConfigRule(REGION, new ConfigRule("race-rule", null, null, null, null,
                new ConfigRuleSource("CUSTOM_LAMBDA", "arn:aws:lambda:us-east-1:000000000000:function:check",
                        null, null),
                null, null, null, null, null));

        int threadsPerAttempt = 16;
        for (int attempt = 0; attempt < 100; attempt++) {
            String resourceId = "bucket-" + attempt;
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            List<Thread> writers = new java.util.ArrayList<>();

            // Every writer races on the SAME resource key; only the last one in should win, and
            // by OrderingTimestamp rather than by write order. Half submit an older evaluation,
            // half a newer one, so any interleaving that lets an older write land last is a bug.
            for (int i = 0; i < threadsPerAttempt; i++) {
                boolean isNewer = i % 2 == 0;
                Thread writer = new Thread(() -> {
                    await(start);
                    try {
                        service.putEvaluations(REGION, "race-rule",
                                List.of(evaluation(resourceId, isNewer ? "COMPLIANT" : "NON_COMPLIANT",
                                        isNewer ? 1700000001.0 : 1700000000.0)), false);
                    } catch (Throwable t) {
                        unexpected.set(t);
                    }
                });
                writers.add(writer);
                writer.start();
            }

            start.countDown();
            for (Thread writer : writers) {
                writer.join(TimeUnit.SECONDS.toMillis(10));
                assertFalse(writer.isAlive(), "a PutEvaluations call never finished");
            }
            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());

            String recorded = service.getComplianceDetailsByResource(REGION, "AWS::S3::Bucket", resourceId,
                    null, null).items().getFirst().complianceType();
            assertEquals("COMPLIANT", recorded,
                    "attempt " + attempt + ": the newer evaluation must win regardless of write order");
        }
    }

    @Test
    void deletingARuleDuringPutEvaluationsDoesNotLeaveEvaluationsForAFutureSameNamedRule() throws Exception {
        AwsConfigService service = new AwsConfigService(new RegionResolver(REGION, "000000000000"), null);
        ConfigRuleSource source = new ConfigRuleSource("CUSTOM_LAMBDA",
                "arn:aws:lambda:us-east-1:000000000000:function:check", null, null);

        for (int attempt = 0; attempt < 200; attempt++) {
            String ruleName = "race-rule-" + attempt;
            service.putConfigRule(REGION, new ConfigRule(ruleName, null, null, null, null, source,
                    null, null, null, null, null));

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> putFailure = new AtomicReference<>();
            AtomicReference<Throwable> deleteFailure = new AtomicReference<>();

            Thread putter = new Thread(() -> {
                await(start);
                try {
                    service.putEvaluations(REGION, ruleName,
                            List.of(evaluation("bucket-1", "NON_COMPLIANT", 1700000000.0)), false);
                } catch (Throwable t) {
                    putFailure.set(t);
                }
            });
            Thread deleter = new Thread(() -> {
                await(start);
                try {
                    service.deleteConfigRule(REGION, ruleName);
                } catch (Throwable t) {
                    deleteFailure.set(t);
                }
            });

            putter.start();
            deleter.start();
            start.countDown();
            putter.join(TimeUnit.SECONDS.toMillis(10));
            deleter.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(putter.isAlive() || deleter.isAlive(), "a putEvaluations/deleteConfigRule call never finished");
            // Losing the race to the delete (NoSuchConfigRuleException) is a legitimate outcome for
            // the putter; any other failure, on either side, is not.
            if (putFailure.get() != null) {
                assertEquals("NoSuchConfigRuleException",
                        ((AwsException) putFailure.get()).getErrorCode(),
                        "unexpected putEvaluations failure: " + putFailure.get());
            }
            assertNull(deleteFailure.get(), () -> "unexpected deleteConfigRule failure: " + deleteFailure.get());

            // A brand new rule reusing the deleted rule's name must start with a clean evaluation
            // bucket -- it must never inherit compliance details left behind by the deleted rule.
            service.putConfigRule(REGION, new ConfigRule(ruleName, null, null, null, null, source,
                    null, null, null, null, null));
            assertEquals("INSUFFICIENT_DATA", service.complianceForRule(REGION, ruleName).complianceType(),
                    "attempt " + attempt + ": a same-named rule inherited the deleted rule's evaluations");
        }
    }

    private static ConfigEvaluation evaluation(String resourceId, String complianceType, double orderingTimestamp) {
        return new ConfigEvaluation("AWS::S3::Bucket", resourceId, complianceType, null,
                orderingTimestamp, null, null);
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
