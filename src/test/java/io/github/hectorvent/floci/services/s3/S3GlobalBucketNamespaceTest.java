package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S3 bucket names are globally unique in AWS: a bucket lives in one account but is legitimately
 * reachable cross-account (subject to policy). The Landing Zone Accelerator relies on this — every
 * member account publishes CDK assets to one shared {@code cdk-accel-assets-<mgmt>-<region>} bucket
 * owned by the management account. floci's default per-account bucket isolation breaks that path.
 *
 * <p>With {@code globalBucketNamespace} enabled, bucket/object <em>resolution</em> spans accounts
 * while <em>listing</em> stays owner-scoped. Default-off preserves the existing isolation model
 * (guarded by {@code PreSignedUrlAccountResolutionIntegrationTest}).
 */
class S3GlobalBucketNamespaceTest {

    private static final String DEFAULT_ACCT = "000000000000";
    private static final String ACCOUNT_A = "000000000001";
    private static final String ACCOUNT_B = "000000000002";

    private final AtomicReference<String> caller = new AtomicReference<>(DEFAULT_ACCT);

    @SuppressWarnings("unchecked")
    private Instance<RequestContext> mutableContext() {
        RequestContext rc = mock(RequestContext.class);
        when(rc.getAccountId()).thenAnswer(inv -> caller.get());
        Instance<RequestContext> inst = mock(Instance.class);
        when(inst.get()).thenReturn(rc);
        return inst;
    }

    @Test
    void bucketOwnedByOneAccountIsResolvableCrossAccountButNotListed() {
        Instance<RequestContext> ctx = mutableContext();
        AccountAwareStorageBackend<Bucket> buckets =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), ctx, DEFAULT_ACCT);
        AccountAwareStorageBackend<S3Object> objects =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), ctx, DEFAULT_ACCT);

        S3Service globalNs = new S3Service(buckets, objects, Path.of("s3-gns-test"), true, true);
        S3Service isolatedNs = new S3Service(buckets, objects, Path.of("s3-gns-test"), true, false);

        // Account A owns the shared bucket and publishes a template into it.
        caller.set(ACCOUNT_A);
        globalNs.createBucket("shared-assets-bucket", "us-west-2");
        byte[] template = "{\"Resources\":{}}".getBytes(UTF_8);
        globalNs.putObject("shared-assets-bucket", "template.json", template, "application/json", Map.of());

        // Account B is a different account (a member account in LZA terms).
        caller.set(ACCOUNT_B);

        // (1) HeadBucket resolves cross-account.
        assertDoesNotThrow(() -> globalNs.headBucket("shared-assets-bucket"));

        // (1) GetBucketLocation returns the owner's region cross-account.
        assertEquals("us-west-2", globalNs.getBucketRegion("shared-assets-bucket"));

        // (2) GetObject reads the owner's object cross-account.
        assertArrayEquals(template,
                globalNs.getObject("shared-assets-bucket", "template.json").getData());

        // (2b) The LZA central-assets path: a member account publishes its own asset into the
        // management-owned bucket (existence check must resolve cross-account), then reads it back.
        byte[] memberAsset = "member-payload".getBytes(UTF_8);
        assertDoesNotThrow(() ->
                globalNs.putObject("shared-assets-bucket", "member-asset.json", memberAsset,
                        "application/json", Map.of()));
        assertArrayEquals(memberAsset,
                globalNs.getObject("shared-assets-bucket", "member-asset.json").getData());

        // (3) Listing stays owner-scoped: account B does not see account A's bucket.
        assertTrue(globalNs.listBuckets().stream()
                        .noneMatch(b -> "shared-assets-bucket".equals(b.getName())),
                "ListBuckets must remain owner-scoped even with global namespace enabled");

        // (4) Default-off preserves isolation: the same cross-account HeadBucket is NoSuchBucket.
        AwsException notFound = assertThrows(AwsException.class,
                () -> isolatedNs.headBucket("shared-assets-bucket"));
        assertEquals("NoSuchBucket", notFound.getErrorCode());
    }

    /**
     * Cross-account bucket-config <em>mutation</em>. LZA's {@code Custom::S3PutBucketReplication}
     * custom resource runs a Lambda in the source (member) account that owns the bucket, but that
     * Lambda calls back into floci under the default/management account context. The mutation must
     * resolve the bucket cross-account (not 404) AND persist back to the <em>owner's</em> partition —
     * a naive resolve-then-{@code put} would fork a phantom bucket into the caller's partition and
     * silently lose the config on the real bucket.
     */
    @Test
    void putBucketReplicationResolvesAndWritesBackToOwnerCrossAccount() {
        Instance<RequestContext> ctx = mutableContext();
        AccountAwareStorageBackend<Bucket> buckets =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), ctx, DEFAULT_ACCT);
        AccountAwareStorageBackend<S3Object> objects =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), ctx, DEFAULT_ACCT);
        S3Service globalNs = new S3Service(buckets, objects, Path.of("s3-gns-repl-test"), true, true);

        // The source (member) account owns the ELB access-logs bucket.
        caller.set(ACCOUNT_A);
        globalNs.createBucket("elb-access-logs-bucket", "us-east-1");

        // The replication custom-resource Lambda calls back under the default/management context.
        caller.set(DEFAULT_ACCT);
        String xml = "<ReplicationConfiguration>"
                + "<Role>arn:aws:iam::000000000001:role/replication</Role>"
                + "<Rule><Status>Enabled</Status>"
                + "<Destination><Bucket>arn:aws:s3:::central-logs</Bucket></Destination></Rule>"
                + "</ReplicationConfiguration>";
        assertDoesNotThrow(() -> globalNs.putBucketReplication("elb-access-logs-bucket", xml));

        // Persisted on the OWNER's bucket (account A), not the caller's partition.
        Bucket owned = buckets.getForAccount(ACCOUNT_A, "elb-access-logs-bucket")
                .orElseThrow(() -> new AssertionError("bucket vanished from owner partition"));
        assertEquals(xml, owned.getReplicationConfiguration());

        // No phantom bucket forked into the caller's default partition.
        assertTrue(buckets.getForAccount(DEFAULT_ACCT, "elb-access-logs-bucket").isEmpty(),
                "cross-account replication write must not fork a bucket into the caller's account");
    }

    /**
     * The write side of {@code Custom::S3PutBucketReplication} resolves and persists cross-account
     * (see {@link #putBucketReplicationResolvesAndWritesBackToOwnerCrossAccount}); the read side
     * must resolve just as far, or the custom resource's own follow-up
     * {@code GetBucketReplication} — and any other management-account reader — sees
     * {@code NoSuchBucket} on a bucket that plainly exists in its owning account.
     */
    @Test
    void getBucketReplicationResolvesCrossAccount() {
        Instance<RequestContext> ctx = mutableContext();
        AccountAwareStorageBackend<Bucket> buckets =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), ctx, DEFAULT_ACCT);
        AccountAwareStorageBackend<S3Object> objects =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), ctx, DEFAULT_ACCT);
        S3Service globalNs = new S3Service(buckets, objects, Path.of("s3-gns-repl-read-test"), true, true);

        caller.set(ACCOUNT_A);
        globalNs.createBucket("elb-access-logs-bucket-2", "us-east-1");
        String xml = "<ReplicationConfiguration>"
                + "<Role>arn:aws:iam::000000000001:role/replication</Role>"
                + "<Rule><Status>Enabled</Status>"
                + "<Destination><Bucket>arn:aws:s3:::central-logs</Bucket></Destination></Rule>"
                + "</ReplicationConfiguration>";
        globalNs.putBucketReplication("elb-access-logs-bucket-2", xml);

        caller.set(DEFAULT_ACCT);
        assertEquals(xml, globalNs.getBucketReplication("elb-access-logs-bucket-2"));
    }

    /**
     * Bucket-policy operations were left on the direct {@code bucketStore.get}/{@code put} path when
     * global namespace resolution was added — unlike replication and object read/write, they never got
     * wired through {@code resolveBucket}/{@code mutateBucket}. A management-account caller (as the LZA
     * Logging stage's policy-application step runs) must be able to put/get/delete a policy on a bucket
     * owned by a member account.
     */
    @Test
    void bucketPolicyResolvesAndPersistsCrossAccount() {
        Instance<RequestContext> ctx = mutableContext();
        AccountAwareStorageBackend<Bucket> buckets =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), ctx, DEFAULT_ACCT);
        AccountAwareStorageBackend<S3Object> objects =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), ctx, DEFAULT_ACCT);
        S3Service globalNs = new S3Service(buckets, objects, Path.of("s3-gns-policy-test"), true, true);

        caller.set(ACCOUNT_A);
        globalNs.createBucket("central-logs-bucket", "us-east-1");

        caller.set(DEFAULT_ACCT);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        assertDoesNotThrow(() -> globalNs.putBucketPolicy("central-logs-bucket", policy));
        assertEquals(policy, globalNs.getBucketPolicy("central-logs-bucket"));

        // Persisted on the OWNER's bucket (account A), not forked into the caller's partition.
        Bucket owned = buckets.getForAccount(ACCOUNT_A, "central-logs-bucket")
                .orElseThrow(() -> new AssertionError("bucket vanished from owner partition"));
        assertEquals(policy, owned.getPolicy());
        assertTrue(buckets.getForAccount(DEFAULT_ACCT, "central-logs-bucket").isEmpty(),
                "cross-account putBucketPolicy must not fork a bucket into the caller's account");

        assertDoesNotThrow(() -> globalNs.deleteBucketPolicy("central-logs-bucket"));
        AwsException notFound = assertThrows(AwsException.class,
                () -> globalNs.getBucketPolicy("central-logs-bucket"));
        assertEquals("NoSuchBucketPolicy", notFound.getErrorCode());
    }
}
