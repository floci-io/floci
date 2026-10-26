package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle sweep, driven directly with a chosen "now" instead of waiting on the scheduler.
 */
class S3LifecycleServiceTest {

    private static final String BUCKET = "lifecycle-bucket";

    @TempDir
    Path tempDir;

    private RecordingLambdaInvoker notifications;
    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        notifications = new RecordingLambdaInvoker();
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir.resolve("s3"), false,
                notifications, new RegionResolver("us-east-1", "000000000000"));
        s3Service.createBucket(BUCKET, "us-east-1");
    }

    @Test
    void aDaysRuleDeletesMatchingObjectsOnlyOnceTheRoundedExpiryHasPassed() {
        put("tmp/a.txt");
        put("keep/b.txt");
        lifecycle(rule("Enabled", "<Filter><Prefix>tmp/</Prefix></Filter>", "<Expiration><Days>1</Days></Expiration>"));
        Instant created = s3Service.headObject(BUCKET, "tmp/a.txt").getLastModified();
        Instant due = S3LifecycleConfiguration.afterDays(created, 1);

        s3Service.applyLifecycleExpiration(due.minusMillis(1));
        assertTrue(s3Service.objectExists(BUCKET, "tmp/a.txt"));

        s3Service.applyLifecycleExpiration(due);
        assertFalse(s3Service.objectExists(BUCKET, "tmp/a.txt"));
        assertTrue(s3Service.objectExists(BUCKET, "keep/b.txt"));
    }

    @Test
    void aDisabledRuleIsNotApplied() {
        put("a.txt");
        lifecycle(rule("Disabled", "<Filter></Filter>", "<Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>"));

        s3Service.applyLifecycleExpiration(Instant.now());

        assertTrue(s3Service.objectExists(BUCKET, "a.txt"));
    }

    @Test
    void aTagFilterExpiresOnlyObjectsCarryingTheTag() {
        put("tagged.txt");
        put("untagged.txt");
        s3Service.putObjectTagging(BUCKET, "tagged.txt", Map.of("env", "ephemeral"));
        lifecycle(rule("Enabled", "<Filter><Tag><Key>env</Key><Value>ephemeral</Value></Tag></Filter>",
                "<Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>"));

        s3Service.applyLifecycleExpiration(Instant.now());

        assertFalse(s3Service.objectExists(BUCKET, "tagged.txt"));
        assertTrue(s3Service.objectExists(BUCKET, "untagged.txt"));
    }

    @Test
    void inAVersionedBucketTheCurrentVersionGetsADeleteMarkerOnce() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object original = put("a.txt");
        lifecycle(rule("Enabled", "<Filter></Filter>", "<Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>"));

        s3Service.applyLifecycleExpiration(Instant.now());
        s3Service.applyLifecycleExpiration(Instant.now());

        List<S3Object> versions = versions("a.txt");
        assertEquals(2, versions.size(), "the original version and one delete marker");
        assertTrue(versions.getFirst().isDeleteMarker());
        assertEquals(original.getVersionId(), versions.get(1).getVersionId());
        assertFalse(s3Service.objectExists(BUCKET, "a.txt"));
    }

    @Test
    void noncurrentVersionsBeyondTheRetainedCountExpireOnceOldEnough() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object v1 = put("a.txt");
        S3Object v2 = put("a.txt");
        S3Object v3 = put("a.txt");
        lifecycle(rule("Enabled", "<Filter></Filter>", "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays>"
                + "<NewerNoncurrentVersions>1</NewerNoncurrentVersions></NoncurrentVersionExpiration>"));

        s3Service.applyLifecycleExpiration(Instant.now());
        assertThat(versionIds("a.txt"), contains(v3.getVersionId(), v2.getVersionId(), v1.getVersionId()));

        s3Service.applyLifecycleExpiration(Instant.now().plus(Duration.ofDays(3)));
        assertThat(versionIds("a.txt"), contains(v3.getVersionId(), v2.getVersionId()));
    }

    @Test
    void noncurrentExpirationDeletesAnArchivedNullVersionWithoutChangingTheCurrentVersion() {
        put("a.txt");
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object current = put("a.txt");
        lifecycle(rule("Enabled", "<Filter></Filter>",
                "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays>"
                        + "</NoncurrentVersionExpiration>"));

        s3Service.applyLifecycleExpiration(Instant.now().plus(Duration.ofDays(3)));

        assertThat(versionIds("a.txt"), contains(current.getVersionId()));
        assertEquals("data", new String(s3Service.getObject(BUCKET, "a.txt").getData(), StandardCharsets.UTF_8));
        AwsException missing = assertThrows(AwsException.class,
                () -> s3Service.getObject(BUCKET, "a.txt", "null"));
        assertEquals("NoSuchVersion", missing.getErrorCode());
    }

    @Test
    void aNoncurrentVersionUnderALegalHoldIsKept() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        s3Service.setBucketObjectLockEnabled(BUCKET);
        S3Object held = s3Service.putObject(BUCKET, "a.txt", "held".getBytes(StandardCharsets.UTF_8), "text/plain",
                Map.of(), null, null, "ON");
        S3Object current = put("a.txt");
        lifecycle(rule("Enabled", "<Filter></Filter>",
                "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays></NoncurrentVersionExpiration>"));

        s3Service.applyLifecycleExpiration(Instant.now().plus(Duration.ofDays(3)));

        assertThat(versionIds("a.txt"), containsInAnyOrder(current.getVersionId(), held.getVersionId()));
    }

    @Test
    void anExpiredObjectDeleteMarkerIsRemoved() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object only = put("a.txt");
        s3Service.deleteObject(BUCKET, "a.txt");
        s3Service.deleteObject(BUCKET, "a.txt", only.getVersionId());
        assertEquals(1, versions("a.txt").size(), "only the delete marker remains");
        lifecycle(rule("Enabled", "<Filter></Filter>",
                "<Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>"));

        s3Service.applyLifecycleExpiration(Instant.now());

        assertThat(versions("a.txt"), empty());
    }

    @Test
    void aDeleteMarkerWithNoncurrentVersionsIsKept() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        put("a.txt");
        s3Service.deleteObject(BUCKET, "a.txt");
        lifecycle(rule("Enabled", "<Filter></Filter>",
                "<Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>"));

        s3Service.applyLifecycleExpiration(Instant.now());

        assertEquals(2, versions("a.txt").size());
    }

    @Test
    void incompleteMultipartUploadsAreAbortedAfterTheirDays() {
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "big/a.bin", "application/octet-stream");
        lifecycle(rule("Enabled", "<Filter><Prefix>big/</Prefix></Filter>",
                "<AbortIncompleteMultipartUpload><DaysAfterInitiation>1</DaysAfterInitiation>"
                        + "</AbortIncompleteMultipartUpload>"));
        Instant due = S3LifecycleConfiguration.afterDays(upload.getInitiated(), 1);

        s3Service.applyLifecycleExpiration(due.minusMillis(1));
        assertEquals(1, s3Service.listMultipartUploads(BUCKET).size());

        s3Service.applyLifecycleExpiration(due);
        assertThat(s3Service.listMultipartUploads(BUCKET), empty());
    }

    @Test
    void lifecycleDeletionsAreReportedAsLifecycleExpirationEvents() throws IOException {
        put("a.txt");
        NotificationConfiguration config = new NotificationConfiguration();
        config.getLambdaFunctionConfigurations().add(new LambdaNotification("removed",
                "arn:aws:lambda:us-east-1:000000000000:function:removed", List.of("s3:ObjectRemoved:*"), List.of()));
        config.getLambdaFunctionConfigurations().add(new LambdaNotification("lifecycle",
                "arn:aws:lambda:us-east-1:000000000000:function:lifecycle",
                List.of("s3:LifecycleExpiration:*"), List.of()));
        s3Service.putBucketNotificationConfiguration(BUCKET, config, true);
        lifecycle(rule("Enabled", "<Filter></Filter>", "<Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>"));

        s3Service.applyLifecycleExpiration(Instant.now());

        assertEquals(List.of("arn:aws:lambda:us-east-1:000000000000:function:lifecycle"),
                notifications.functionNames);
        JsonNode record = new ObjectMapper().readTree(notifications.payloads.getFirst()).path("Records").get(0);
        assertEquals("LifecycleExpiration:Delete", record.path("eventName").asText());
        // The user guide's example names S3 itself, not a requester.
        assertEquals("s3.amazonaws.com", record.path("userIdentity").path("principalId").asText());
        assertEquals("s3.amazonaws.com", record.path("requestParameters").path("sourceIPAddress").asText());
    }

    @Test
    void versionsWrittenInTheSameMillisecondKeepTheOrderTheyWereWrittenIn() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object v1 = put("a.txt");
        S3Object v2 = put("a.txt");
        S3Object v3 = put("a.txt");
        Instant sameMillisecond = Instant.parse("2026-10-01T10:00:00Z");
        for (S3Object version : List.of(v1, v2, v3)) {
            version.setLastModified(sameMillisecond);
        }
        lifecycle(rule("Enabled", "<Filter></Filter>", "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays>"
                + "<NewerNoncurrentVersions>1</NewerNoncurrentVersions></NoncurrentVersionExpiration>"));

        assertThat(versionIds("a.txt"), contains(v3.getVersionId(), v2.getVersionId(), v1.getVersionId()));
        s3Service.applyLifecycleExpiration(sameMillisecond.plus(Duration.ofDays(3)));

        assertThat(versionIds("a.txt"), contains(v3.getVersionId(), v2.getVersionId()));
    }

    @Test
    void aKeyLeftWithOnlyVersionsInASuspendedBucketHasItsOlderVersionsExpired() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object v1 = put("a.txt");
        S3Object v2 = put("a.txt");
        s3Service.putBucketVersioning(BUCKET, "Suspended");
        s3Service.deleteObject(BUCKET, "a.txt");
        assertThat(versionIds("a.txt"), contains(v2.getVersionId(), v1.getVersionId()));
        lifecycle(rule("Enabled", "<Filter></Filter>",
                "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays></NoncurrentVersionExpiration>"));

        s3Service.applyLifecycleExpiration(Instant.now().plus(Duration.ofDays(3)));

        // v1 has been noncurrent since v2 was written. When v2 stopped being current is not recorded.
        assertThat(versionIds("a.txt"), contains(v2.getVersionId()));
    }

    @Test
    void aRuleWithAValueS3WouldRefuseExpiresNothing() {
        put("small.txt");
        lifecycle(rule("Enabled", "<Filter><ObjectSizeGreaterThan>large</ObjectSizeGreaterThan></Filter>",
                "<Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>")
                + rule("Enabled", "<Filter></Filter>", "<Expiration><Days>0</Days></Expiration>"));

        s3Service.applyLifecycleExpiration(Instant.now().plus(Duration.ofDays(3)));

        assertTrue(s3Service.objectExists(BUCKET, "small.txt"));
        assertNull(s3Service.expirationHeader(s3Service.headObject(BUCKET, "small.txt")));
    }

    @Test
    void aBucketThatFailsDoesNotStopTheOthersAndTheRunReportsIt() {
        S3Service failing = new S3Service(new InMemoryStorage<>(), new FailingDeletes("broken/"),
                tempDir.resolve("failing"), false, notifications, new RegionResolver("us-east-1", "000000000000"));
        for (String bucket : List.of("broken", "healthy")) {
            failing.createBucket(bucket, "us-east-1");
            failing.putObject(bucket, "a.txt", "data".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
            failing.putBucketLifecycle(bucket, "<LifecycleConfiguration>" + rule("Enabled", "<Filter></Filter>",
                    "<Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>") + "</LifecycleConfiguration>", null);
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> failing.applyLifecycleExpiration(Instant.now()));

        assertTrue(failure.getMessage().contains("[broken]"), failure.getMessage());
        assertEquals("disk failure", failure.getSuppressed()[0].getMessage());
        assertFalse(failing.objectExists("healthy", "a.txt"));
        assertTrue(failing.objectExists("broken", "a.txt"));
    }

    @Test
    void theExpirationHeaderDescribesOnlyTheCurrentVersion() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object older = put("a.txt");
        put("a.txt");
        lifecycle(rule("Enabled", "<Filter></Filter>", "<Expiration><Date>2099-01-01T00:00:00Z</Date></Expiration>"));

        assertEquals("expiry-date=\"Thu, 01 Jan 2099 00:00:00 GMT\", rule-id=\"rule\"",
                s3Service.expirationHeader(s3Service.headObject(BUCKET, "a.txt")));
        assertNull(s3Service.expirationHeader(s3Service.headObject(BUCKET, "a.txt", older.getVersionId())));
    }

    @Test
    void aBucketWithoutALifecycleConfigurationIsLeftAlone() {
        put("a.txt");

        s3Service.applyLifecycleExpiration(Instant.now().plus(Duration.ofDays(10_000)));

        assertTrue(s3Service.objectExists(BUCKET, "a.txt"));
        assertNull(s3Service.expirationHeader(s3Service.headObject(BUCKET, "a.txt")));
        assertNotNull(s3Service.headObject(BUCKET, "a.txt"));
    }

    private S3Object put(String key) {
        return s3Service.putObject(BUCKET, key, "data".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
    }

    private void lifecycle(String rules) {
        s3Service.putBucketLifecycle(BUCKET, "<LifecycleConfiguration>" + rules + "</LifecycleConfiguration>", null);
    }

    private static String rule(String status, String filter, String actions) {
        return "<Rule><ID>rule</ID>" + filter + "<Status>" + status + "</Status>" + actions + "</Rule>";
    }

    /** Newest first. */
    private List<S3Object> versions(String key) {
        return s3Service.listObjectVersions(BUCKET, key, 1000, null).versions();
    }

    private List<String> versionIds(String key) {
        return versions(key).stream().map(S3Object::getVersionId).toList();
    }

    /** An object store whose deletes fail under one bucket, as a disk error would. */
    private static final class FailingDeletes extends InMemoryStorage<String, S3Object> {
        private final String failingPrefix;

        FailingDeletes(String failingPrefix) {
            this.failingPrefix = failingPrefix;
        }

        @Override
        public void delete(String key) {
            if (key.startsWith(failingPrefix)) {
                throw new IllegalStateException("disk failure");
            }
            super.delete(key);
        }
    }

    private static final class RecordingLambdaInvoker implements S3Service.LambdaInvoker {
        private final List<String> functionNames = new ArrayList<>();
        private final List<byte[]> payloads = new ArrayList<>();

        @Override
        public void invoke(String region, String functionName, byte[] payload, InvocationType type) {
            functionNames.add(functionName);
            payloads.add(payload);
        }
    }
}
