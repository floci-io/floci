package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A rotation Lambda calls PutSecretValue and UpdateSecretVersionStage on the secret its own
 * rotation is staging, so those calls race RotateSecret's "a previous rotation isn't complete"
 * guard. These tests park each of them halfway through its update and assert that another caller
 * either waits for it or still sees a coherent set of staging labels.
 */
class SecretsManagerRotationLockTest {

    private static final String REGION = "us-east-1";
    private static final String SECRET_NAME = "rotating-secret";
    private static final String PENDING_TOKEN = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String SECOND_TOKEN = "b1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String THIRD_TOKEN = "c1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String LAMBDA_ARN = "arn:aws:lambda:us-east-1:000000000000:function:rotate";

    private SecretsManagerService service;
    private Secret secret;

    @BeforeEach
    void setUp() {
        service = new SecretsManagerService(new InMemoryStorage<>(), 30,
                new RegionResolver(REGION, "000000000000"), mock(LambdaService.class), new ObjectMapper());
        service.createSecret(SECRET_NAME, "current-value", null, null, null, null, REGION);
        // The version a rotation registers before it invokes the Lambda: staged AWSPENDING, no value yet.
        service.putSecretValue(SECRET_NAME, null, null, PENDING_TOKEN, REGION, List.of("AWSPENDING"));
        secret = service.describeSecret(SECRET_NAME, REGION);
    }

    @Test
    void awsPendingStaysReadableWhilePutSecretValueReplacesThePlaceholder() throws Exception {
        BlockingVersions versions = installBlockingVersions();
        Watched writer = startBlockedPutSecretValue(versions);

        SecretVersion pending = service.getSecretValue(SECRET_NAME, null, "AWSPENDING", REGION);
        assertNotNull(pending);
        assertEquals(PENDING_TOKEN, pending.getVersionId());

        versions.release();
        writer.awaitSuccess();
    }

    @Test
    void rotateSecretStillRejectsASecondRotationWhilePutSecretValueIsMidUpdate() throws Exception {
        BlockingVersions versions = installBlockingVersions();
        Watched writer = startBlockedPutSecretValue(versions);

        Watched rotator = start("rotator", () ->
                service.rotateSecret(SECRET_NAME, SECOND_TOKEN, LAMBDA_ARN, null, true, REGION));
        assertTrue(rotator.awaitBlocked(), "RotateSecret did not wait for the in-flight PutSecretValue");

        versions.release();
        writer.awaitSuccess();
        rotator.awaitTermination();

        AwsException failure = assertInstanceOf(AwsException.class, rotator.thrown.get());
        assertEquals("InvalidRequestException", failure.getErrorCode());
    }

    @Test
    void putSecretValueWaitsForAnInFlightStagingLabelMove() throws Exception {
        ParkingSecretVersion current = installParkingCurrentVersion();
        String currentVersionId = current.getVersionId();

        current.arm();
        // The move a rotation Lambda's finishSecret step makes: AWSCURRENT from the old version
        // onto the version it just staged as AWSPENDING.
        Watched mover = start("stage-mover", () ->
                service.updateSecretVersionStage(SECRET_NAME, PENDING_TOKEN, currentVersionId, "AWSCURRENT", REGION));
        assertTrue(current.awaitEntered(), "UpdateSecretVersionStage never reached the label move");

        Watched writer = start("writer", () ->
                service.putSecretValue(SECRET_NAME, "later-value", null, THIRD_TOKEN, REGION, null));
        assertTrue(writer.awaitBlocked(), "PutSecretValue did not wait for the in-flight label move");

        current.release();
        mover.awaitSuccess();
        writer.awaitSuccess();

        // Serialized, so the put applied on top of the completed move: one AWSCURRENT, and it is
        // the value the put wrote.
        SecretVersion latest = service.getSecretValue(SECRET_NAME, null, "AWSCURRENT", REGION);
        assertEquals(THIRD_TOKEN, latest.getVersionId());
        assertEquals("later-value", latest.getSecretString());
        assertEquals(1, secret.getVersions().values().stream()
                .filter(v -> v.getVersionStages() != null && v.getVersionStages().contains("AWSCURRENT"))
                .count());
    }

    @Test
    void putSecretValueWaitsForTheRotationLifecycleRetiringAwsPending() throws Exception {
        // A rotation started with RotateImmediately=false retires AWSPENDING from its background
        // thread. That service has no Lambda, so the lifecycle runs its steps as no-ops and
        // reaches the retirement directly.
        SecretsManagerService lambdaless = new SecretsManagerService(new InMemoryStorage<>(), 30);
        lambdaless.createSecret(SECRET_NAME, "current-value", null, null, null, null, REGION);
        Secret target = lambdaless.describeSecret(SECRET_NAME, REGION);
        SecretVersion current = target.getVersions().get(target.getCurrentVersionId());
        // The state a finishing rotation leaves behind: one version holding both labels, which
        // RotateSecret's guard accepts.
        current.setVersionStages(List.of("AWSCURRENT", "AWSPENDING"));
        ParkingSecretVersion parking = new ParkingSecretVersion(current);
        target.getVersions().put(parking.getVersionId(), parking);

        parking.arm();
        lambdaless.rotateSecret(SECRET_NAME, SECOND_TOKEN, LAMBDA_ARN, null, false, REGION);
        assertTrue(parking.awaitEntered(), "the rotation lifecycle never retired AWSPENDING");

        Watched writer = start("writer", () ->
                lambdaless.putSecretValue(SECRET_NAME, "staged-value", null, THIRD_TOKEN, REGION, List.of("AWSPENDING")));
        assertTrue(writer.awaitBlocked(), "PutSecretValue did not wait for the rotation lifecycle");

        parking.release();
        writer.awaitSuccess();

        SecretVersion pending = lambdaless.getSecretValue(SECRET_NAME, null, "AWSPENDING", REGION);
        assertEquals(THIRD_TOKEN, pending.getVersionId());
        assertEquals(1, target.getVersions().values().stream()
                .filter(v -> v.getVersionStages() != null && v.getVersionStages().contains("AWSPENDING"))
                .count());
    }

    @Test
    void describeSecretIterationSurvivesAVersionAddedByARotation() throws Exception {
        // A rotation adds its AWSPENDING version from the rotation executor while a request thread
        // is building DescribeSecret's VersionIdsToStages from the same map. Park the reader one
        // entry into that iteration, add the version, and let the reader continue.
        SecretsManagerJsonHandler handler = new SecretsManagerJsonHandler(service, new ObjectMapper());
        ParkingIterationVersions versions = new ParkingIterationVersions(secret.getVersions());
        secret.setVersions(versions);
        assertTrue(secret.getVersions() instanceof ConcurrentMap,
                "the installed map must survive setVersions so the parked iteration is the one under test");

        ObjectNode describe = new ObjectMapper().createObjectNode().put("SecretId", SECRET_NAME);
        AtomicReference<Response> response = new AtomicReference<>();
        versions.arm();
        Watched reader = start("reader", () -> response.set(handler.handle("DescribeSecret", describe, REGION)));
        assertTrue(versions.awaitEntered(), "DescribeSecret never started iterating the versions");

        Watched writer = start("writer", () ->
                service.putSecretValue(SECRET_NAME, "staged-value", null, SECOND_TOKEN, REGION, List.of("AWSPENDING")));
        writer.awaitSuccess();

        versions.release();
        reader.awaitSuccess();

        assertEquals(200, response.get().getStatus());
        ObjectNode body = (ObjectNode) response.get().getEntity();
        assertTrue(body.get("VersionIdsToStages").has(secret.getCurrentVersionId()),
                "the version that existed before the rotation must be reported");
    }

    @Test
    void versionsReloadedFromPersistenceAreNotFailFast() throws Exception {
        // Persisted secrets come back through Jackson as plain maps; the rotation thread mutates
        // whatever map is installed, so a reloaded secret must get the same guarantee.
        Secret reloaded = new ObjectMapper().findAndRegisterModules().readValue("""
                {"name":"reloaded","arn":"arn:aws:secretsmanager:us-east-1:000000000000:secret:reloaded-abc123",
                 "versions":{"v1":{"versionId":"v1","secretString":"x","versionStages":["AWSCURRENT"]}}}
                """, Secret.class);
        assertTrue(reloaded.getVersions() instanceof ConcurrentMap);
        assertEquals("x", reloaded.getVersions().get("v1").getSecretString());
    }

    private BlockingVersions installBlockingVersions() {
        BlockingVersions versions = new BlockingVersions(secret.getVersions());
        secret.setVersions(versions);
        return versions;
    }

    private ParkingSecretVersion installParkingCurrentVersion() {
        SecretVersion current = secret.getVersions().get(secret.getCurrentVersionId());
        ParkingSecretVersion parking = new ParkingSecretVersion(current);
        secret.getVersions().put(parking.getVersionId(), parking);
        return parking;
    }

    private Watched startBlockedPutSecretValue(BlockingVersions versions) throws InterruptedException {
        versions.arm();
        Watched writer = start("writer", () ->
                service.putSecretValue(SECRET_NAME, "rotated-value", null, PENDING_TOKEN, REGION, List.of("AWSPENDING")));
        assertTrue(versions.awaitEntered(), "PutSecretValue never reached the version swap");
        return writer;
    }

    private static Watched start(String name, Runnable body) {
        Watched watched = new Watched(name, body);
        watched.thread.start();
        return watched;
    }

    /** A thread whose failure and whose wait on the secret's monitor the test can assert on. */
    private static final class Watched {

        private final Thread thread;
        private final AtomicReference<Throwable> thrown = new AtomicReference<>();

        Watched(String name, Runnable body) {
            this.thread = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }, name);
            this.thread.setDaemon(true);
        }

        /** Waits for the thread to sit on a monitor rather than for a fixed interval to pass. */
        boolean awaitBlocked() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (thread.getState() == Thread.State.BLOCKED) {
                    return true;
                }
                if (!thread.isAlive()) {
                    return false;
                }
                Thread.sleep(5);
            }
            return false;
        }

        void awaitTermination() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            assertTrue(!thread.isAlive(), thread.getName() + " did not finish within the join timeout");
        }

        void awaitSuccess() throws InterruptedException {
            awaitTermination();
            assertNull(thrown.get(), () -> thread.getName() + " failed: " + thrown.get());
        }
    }

    /**
     * Forwards to the secret's real versions map, and parks the first armed entry-set iteration
     * after its first element so another thread can add a version mid-iteration. Implements
     * {@link ConcurrentMap} only so {@code Secret.setVersions} installs it unchanged; whether the
     * iteration then survives is decided by the map it forwards to.
     */
    private static final class ParkingIterationVersions extends AbstractMap<String, SecretVersion>
            implements ConcurrentMap<String, SecretVersion> {

        private final Map<String, SecretVersion> delegate;
        private final Park park = new Park();

        ParkingIterationVersions(Map<String, SecretVersion> delegate) {
            this.delegate = delegate;
        }

        void arm() {
            park.arm();
        }

        boolean awaitEntered() throws InterruptedException {
            return park.awaitEntered();
        }

        void release() {
            park.release();
        }

        @Override
        public SecretVersion put(String key, SecretVersion value) {
            return delegate.put(key, value);
        }

        @Override
        public SecretVersion get(Object key) {
            return delegate.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return delegate.containsKey(key);
        }

        @Override
        public SecretVersion putIfAbsent(String key, SecretVersion value) {
            return delegate.putIfAbsent(key, value);
        }

        @Override
        public boolean remove(Object key, Object value) {
            return delegate.remove(key, value);
        }

        @Override
        public boolean replace(String key, SecretVersion oldValue, SecretVersion newValue) {
            return delegate.replace(key, oldValue, newValue);
        }

        @Override
        public SecretVersion replace(String key, SecretVersion value) {
            return delegate.replace(key, value);
        }

        @Override
        public Set<Map.Entry<String, SecretVersion>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Map.Entry<String, SecretVersion>> iterator() {
                    Iterator<Map.Entry<String, SecretVersion>> iterator = delegate.entrySet().iterator();
                    return new Iterator<>() {
                        private boolean first = true;

                        @Override
                        public boolean hasNext() {
                            return iterator.hasNext();
                        }

                        @Override
                        public Map.Entry<String, SecretVersion> next() {
                            Map.Entry<String, SecretVersion> entry = iterator.next();
                            if (first) {
                                first = false;
                                park.parkIfArmed();
                            }
                            return entry;
                        }
                    };
                }

                @Override
                public int size() {
                    return delegate.size();
                }
            };
        }
    }

    /** Parks the first armed {@code put} so another thread can observe the half-applied swap. */
    private static final class BlockingVersions extends ConcurrentHashMap<String, SecretVersion> {

        private final Park park = new Park();

        BlockingVersions(Map<String, SecretVersion> initial) {
            super(initial);
        }

        void arm() {
            park.arm();
        }

        boolean awaitEntered() throws InterruptedException {
            return park.awaitEntered();
        }

        void release() {
            park.release();
        }

        @Override
        public SecretVersion put(String key, SecretVersion value) {
            park.parkIfArmed();
            return super.put(key, value);
        }
    }

    /** Parks after the first armed staging-label change, with that change already applied. */
    private static final class ParkingSecretVersion extends SecretVersion {

        private final Park park = new Park();

        ParkingSecretVersion(SecretVersion source) {
            setVersionId(source.getVersionId());
            setSecretString(source.getSecretString());
            setSecretBinary(source.getSecretBinary());
            setVersionStages(source.getVersionStages());
            setCreatedDate(source.getCreatedDate());
        }

        void arm() {
            park.arm();
        }

        boolean awaitEntered() throws InterruptedException {
            return park.awaitEntered();
        }

        void release() {
            park.release();
        }

        @Override
        public void setVersionStages(List<String> versionStages) {
            super.setVersionStages(versionStages);
            park.parkIfArmed();
        }
    }

    /** One-shot rendezvous: the armed thread stops here until the test releases it. */
    private static final class Park {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean armed;

        void arm() {
            armed = true;
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        void parkIfArmed() {
            if (!armed) {
                return;
            }
            armed = false;
            entered.countDown();
            try {
                released.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
