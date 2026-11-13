package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.s3.model.Bucket;
import io.floci.aws.services.s3.model.CopyObjectOptions;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3RestoreStateServiceTest {
    private static final Instant START = Instant.parse("2026-11-12T12:00:00Z");
    private static final Instant ONE_DAY_EXPIRY = Instant.parse("2026-11-14T00:00:00Z");
    private static final String ONE_DAY = "<RestoreRequest><Days>1</Days></RestoreRequest>";

    @TempDir
    Path dataRoot;

    @Test
    void completionAndExpiryUseExactClockBoundaries() {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objects = new InMemoryStorage<>();
        S3Service start = service(buckets, objects, START, 10);
        start.createBucket("archive", "us-east-1");
        putArchived(start, "key");

        assertNull(start.restoreStatus(start.headObject("archive", "key")));
        assertTrue(start.restoreObject("archive", "key", null, ONE_DAY));
        assertEquals(new S3Service.RestoreStatus(true, null),
                start.restoreStatus(start.headObject("archive", "key")));
        AwsException inProgress = assertThrows(AwsException.class,
                () -> start.restoreObject("archive", "key", null, ONE_DAY));
        assertEquals("RestoreAlreadyInProgress", inProgress.getErrorCode());
        assertEquals(409, inProgress.getHttpStatus());

        Instant completion = START.plusSeconds(10);
        Instant expiry = ONE_DAY_EXPIRY;
        S3Service beforeCompletion = service(buckets, objects, completion.minusNanos(1), 10);
        assertTrue(beforeCompletion.restoreStatus(beforeCompletion.headObject("archive", "key")).inProgress());
        S3Service atCompletion = service(buckets, objects, completion, 10);
        assertEquals(new S3Service.RestoreStatus(false, expiry),
                atCompletion.restoreStatus(atCompletion.headObject("archive", "key")));
        S3Service beforeExpiry = service(buckets, objects, expiry.minusNanos(1), 10);
        assertFalse(beforeExpiry.restoreStatus(beforeExpiry.headObject("archive", "key")).inProgress());
        S3Service atExpiry = service(buckets, objects, expiry, 10);
        assertNull(atExpiry.restoreStatus(atExpiry.headObject("archive", "key")));
        assertTrue(atExpiry.restoreObject("archive", "key", null, ONE_DAY));
        assertTrue(atExpiry.restoreStatus(atExpiry.headObject("archive", "key")).inProgress());
    }

    @Test
    void exactMidnightExpiryDoesNotGainAnExtraDay() {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objects = new InMemoryStorage<>();
        Instant midnight = Instant.parse("2026-11-12T00:00:00Z");
        S3Service service = service(buckets, objects, midnight, 0);
        service.createBucket("archive", "us-east-1");
        putArchived(service, "key");

        assertTrue(service.restoreObject("archive", "key", null, ONE_DAY));
        assertEquals(midnight.plus(1, ChronoUnit.DAYS),
                service.restoreStatus(service.headObject("archive", "key")).expiryDate());
    }

    @Test
    void delayCrossingMidnightStartsExpiryPeriodAtCompletion() {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objects = new InMemoryStorage<>();
        Instant request = Instant.parse("2026-11-12T23:59:30Z");
        S3Service start = service(buckets, objects, request, 60);
        start.createBucket("archive", "us-east-1");
        putArchived(start, "key");

        assertTrue(start.restoreObject("archive", "key", null, ONE_DAY));
        S3Service completed = service(buckets, objects, request.plusSeconds(60), 60);
        assertEquals(new S3Service.RestoreStatus(false, Instant.parse("2026-11-15T00:00:00Z")),
                completed.restoreStatus(completed.headObject("archive", "key")));
    }

    @Test
    void completedRequestExtendsOnlyTheSelectedVersion() {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objects = new InMemoryStorage<>();
        S3Service start = service(buckets, objects, START, 0);
        start.createBucket("archive", "us-east-1");
        start.putBucketVersioning("archive", "Enabled");
        String older = putArchived(start, "key").getVersionId();
        String current = putArchived(start, "key").getVersionId();

        assertTrue(start.restoreObject("archive", "key", older, ONE_DAY));
        assertNull(start.restoreStatus(start.headObject("archive", "key", current)));
        assertTrue(start.restoreObject("archive", "key", current, ONE_DAY));

        Instant later = START.plusSeconds(3_600);
        S3Service extension = service(buckets, objects, later, 0);
        assertFalse(extension.restoreObject("archive", "key", older,
                "<RestoreRequest><Days>2</Days></RestoreRequest>"));
        assertEquals(Instant.parse("2026-11-15T00:00:00Z"),
                extension.restoreStatus(extension.headObject("archive", "key", older)).expiryDate());
        assertEquals(ONE_DAY_EXPIRY,
                extension.restoreStatus(extension.headObject("archive", "key", current)).expiryDate());
        assertEquals(ONE_DAY_EXPIRY,
                extension.restoreStatus(extension.headObject("archive", "key")).expiryDate());
    }

    @Test
    void archivedNullVersionRetainsStateAfterANewNumberedVersion() {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objects = new InMemoryStorage<>();
        S3Service service = service(buckets, objects, START, 0);
        service.createBucket("archive", "us-east-1");
        service.putBucketVersioning("archive", "Enabled");
        service.putBucketVersioning("archive", "Suspended");
        putArchived(service, "key");
        assertTrue(service.restoreObject("archive", "key", "null", ONE_DAY));

        service.putBucketVersioning("archive", "Enabled");
        putArchived(service, "key");
        assertNull(service.restoreStatus(service.headObject("archive", "key")));
        assertEquals(new S3Service.RestoreStatus(false, ONE_DAY_EXPIRY),
                service.restoreStatus(service.headObject("archive", "key", "null")));
    }

    @Test
    void overwriteAndPromotionKeepTheDelayedStateOnItsVersion() {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objects = new InMemoryStorage<>();
        S3Service service = service(buckets, objects, START, 10);
        service.createBucket("archive", "us-east-1");
        service.putBucketVersioning("archive", "Enabled");
        putArchived(service, "key");
        String restored = putArchived(service, "key").getVersionId();
        assertTrue(service.restoreObject("archive", "key", restored, ONE_DAY));

        String replacement = putArchived(service, "key").getVersionId();
        assertNull(service.restoreStatus(service.headObject("archive", "key")));
        assertTrue(service.restoreStatus(service.headObject("archive", "key", restored)).inProgress());

        service.deleteObject("archive", "key", replacement);
        assertTrue(service.restoreStatus(service.headObject("archive", "key")).inProgress());
        assertTrue(service.restoreStatus(service.headObject("archive", "key", restored)).inProgress());
    }

    @Test
    void copyIntoStandardDoesNotInheritSourceRestoreState() {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objects = new InMemoryStorage<>();
        S3Service service = service(buckets, objects, START, 0);
        service.createBucket("archive", "us-east-1");
        putArchived(service, "source");
        assertTrue(service.restoreObject("archive", "source", null, ONE_DAY));

        service.copyObject("archive", "source", "archive", "copy",
                new CopyObjectOptions().withStorageClass("STANDARD"));
        assertNull(service.restoreStatus(service.headObject("archive", "copy")));
        assertEquals("STANDARD", service.headObject("archive", "copy").getStorageClass());
        assertEquals(new S3Service.RestoreStatus(false, ONE_DAY_EXPIRY),
                service.restoreStatus(service.headObject("archive", "source")));
    }

    private S3Service service(InMemoryStorage<String, Bucket> buckets,
                              InMemoryStorage<String, S3Object> objects,
                              Instant now, long delaySeconds) {
        return new S3Service(buckets, objects, dataRoot, true,
                Clock.fixed(now, ZoneOffset.UTC), delaySeconds);
    }

    private static S3Object putArchived(S3Service service, String key) {
        return service.putObject("archive", key, "body".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of(), "GLACIER", null, null, null);
    }
}
