package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AddTags/RemoveTags and every other trail mutation are a read-modify-write against the same
 * stored {@link CloudTrailEntry}. Without a monitor shared across all of them, two overlapping
 * mutations of the same trail can each read before either writes, so whichever write lands last
 * silently discards the other's change (floci-io/floci#2904).
 */
@QuarkusTest
class CloudTrailTagConcurrencyTest {

    private static final String REGION = "us-east-1";

    @Inject
    CloudTrailService service;

    @Test
    void addTagsRacingStartLoggingLosesNeitherChange() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            String name = "race-trail-" + UUID.randomUUID().toString().substring(0, 8);
            Trail trail = service.createTrail(REGION, name, "some-bucket-" + attempt, null, null,
                    true, false, false, false);

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread tagger = new Thread(() -> {
                await(start);
                try {
                    service.addTags(trail.trailArn(), Map.of("env", "race"));
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
            Thread starter = new Thread(() -> {
                await(start);
                try {
                    service.startLogging(REGION, name);
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });

            tagger.start();
            starter.start();
            start.countDown();
            tagger.join(TimeUnit.SECONDS.toMillis(10));
            starter.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(tagger.isAlive() || starter.isAlive(), "a tag/logging call never finished");
            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());

            assertEquals("race", service.listTags(trail.trailArn()).get("env"),
                    "attempt " + attempt + ": AddTags was lost to a concurrent StartLogging");
            assertTrue(service.getTrailStatus(REGION, name).logging(),
                    "attempt " + attempt + ": StartLogging was lost to a concurrent AddTags");

            service.deleteTrail(REGION, name);
        }
    }

    @Test
    void taggingDuringADeleteDoesNotReviveTheTrail() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            String name = "del-race-trail-" + UUID.randomUUID().toString().substring(0, 8);
            Trail trail = service.createTrail(REGION, name, "some-bucket-" + attempt, null, null,
                    true, false, false, false);

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread tagger = new Thread(() -> {
                await(start);
                try {
                    service.addTags(trail.trailArn(), Map.of("env", "race"));
                } catch (AwsException expected) {
                    // Losing to the delete is a legitimate outcome; being answered wrong is not.
                    if (!"ResourceNotFoundException".equals(expected.getErrorCode())) {
                        unexpected.set(expected);
                    }
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
            Thread deleter = new Thread(() -> {
                await(start);
                try {
                    service.deleteTrail(REGION, name);
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });

            tagger.start();
            deleter.start();
            start.countDown();
            tagger.join(TimeUnit.SECONDS.toMillis(10));
            deleter.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(tagger.isAlive() || deleter.isAlive(),
                    "tagger/deleter did not finish, deadlock between the monitors");
            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());

            assertTrue(service.describeTrails(REGION, List.of(name)).isEmpty(),
                    "attempt " + attempt + ": trail " + name + " came back after being deleted");
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
