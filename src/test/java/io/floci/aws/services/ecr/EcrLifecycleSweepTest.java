package io.floci.aws.services.ecr;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.ecr.model.ImageMetadata;
import io.floci.aws.services.ecr.model.Repository;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecr.registry.RegistryHttpClient;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.Answers;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** {@link EcrService#applyLifecyclePolicies} against an in-memory registry speaking the distribution API. */
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EcrLifecycleSweepTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String REPO = "app";
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final String KEEP_TWO = policy("{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"any\","
            + "\"countType\":\"imageCountMoreThan\",\"countNumber\":2},\"action\":{\"type\":\"expire\"}}");
    private static final String OLDER_THAN_FIVE_DAYS = policy("{\"rulePriority\":1,\"selection\":{"
            + "\"tagStatus\":\"any\",\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":5},"
            + "\"action\":{\"type\":\"expire\"}}");

    private FakeRegistry registry;
    private EcrRegistryManager registryManager;
    private InMemoryStorage<String, Repository> repositories;
    private InMemoryStorage<String, ImageMetadata> pushRecords;
    private EcrService service;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() throws IOException {
        registry = new FakeRegistry();
        registryManager = Mockito.mock(EcrRegistryManager.class);
        when(registryManager.getRepositoryUri(anyString(), anyString(), anyString())).thenReturn("uri");
        when(registryManager.internalRepoName(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "/" + inv.getArgument(1) + "/" + inv.getArgument(2));
        when(registryManager.isStarted()).thenReturn(true);
        when(registryManager.httpClient()).thenReturn(new RegistryHttpClient("http://127.0.0.1:" + registry.port()));
        when(registryManager.heldManifestDigests(anyString())).thenAnswer(inv -> registry.digests(inv.getArgument(0)));
        config = Mockito.mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(config.services().ecr().enabled()).thenReturn(true);
        repositories = new InMemoryStorage<>();
        pushRecords = new InMemoryStorage<>();
        service = new EcrService(repositories, pushRecords, new InMemoryStorage<>(), registryManager, config,
                new RegionResolver(REGION, ACCOUNT));
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void theSweepExpiresWhatThePolicySelectsAndRecordsWhenItRan() {
        Repository repo = repository(REPO, KEEP_TWO);
        String v1 = push(REGION, REPO, "v1", image("v1"), 4);
        String v2 = push(REGION, REPO, "v2", image("v2"), 3);
        String v3 = push(REGION, REPO, "v3", image("v3"), 2);
        String v4 = push(REGION, REPO, "v4", image("v4"), 1);
        assertNull(service.lifecyclePolicyEvaluatedAt(repo, REGION));

        service.applyLifecyclePolicies(NOW);

        assertEquals(Set.of(v3, v4), registry.digests(internal(REGION, REPO)));
        assertEquals(Set.of(v3, v4), recordedDigests(REGION, REPO));
        assertEquals(NOW, service.lifecyclePolicyEvaluatedAt(repo, REGION));
        assertEquals(Set.of(v1, v2), Set.copyOf(registry.deleted));
    }

    /**
     * Untagged images are every manifest the registry holds without a tag: one with no push record is taken as pushed
     * when the sweep first sees it, as DescribeImages does, and a push record of a manifest that is gone names nothing.
     */
    @Test
    void untaggedImagesAreTheManifestsTheRegistryHoldsWithoutATag() {
        repository(REPO, policy("{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"untagged\","
                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1},\"action\":{\"type\":\"expire\"}}"));
        String tagged = push(REGION, REPO, "latest", image("tagged"), 9);
        String oldest = push(REGION, REPO, null, image("u1"), 8);
        String older = push(REGION, REPO, null, image("u2"), 7);
        String unrecorded = registry.put(internal(REGION, REPO), null, image("u3"));
        service.recordImagePush(ACCOUNT, REGION, REPO, digest(image("gone")), NOW.minus(Duration.ofDays(20)));

        service.applyLifecyclePolicies(NOW);

        assertEquals(Set.of(tagged, unrecorded), registry.digests(internal(REGION, REPO)));
        assertEquals(Set.of(oldest, older), Set.copyOf(registry.deleted));
    }

    /**
     * "If an image is referenced by a manifest list, it cannot be expired or archived without the manifest list being
     * deleted or archived first."
     */
    @Test
    void anImageAManifestListNamesExpiresOnlyOnceTheListIsGone() {
        repository(REPO, OLDER_THAN_FIVE_DAYS);
        String amd64 = push(REGION, REPO, null, image("amd64"), 10);
        String arm64 = push(REGION, REPO, null, image("arm64"), 10);
        String index = push(REGION, REPO, "v1", "{\"schemaVersion\":2,\"mediaType\":"
                + "\"application/vnd.oci.image.index.v1+json\",\"manifests\":[{\"digest\":\"" + amd64 + "\"},"
                + "{\"digest\":\"" + arm64 + "\"}]}", 10);

        service.applyLifecyclePolicies(NOW);
        assertEquals(List.of(index), registry.deleted);

        service.applyLifecyclePolicies(NOW);
        assertEquals(Set.of(), registry.digests(internal(REGION, REPO)));
    }

    /**
     * "When reference artifacts are present in a repository, Amazon ECR lifecycle policies automatically expire or
     * archive those artifacts within 24 hours of the deletion or archival of the subject image."
     */
    @Test
    void anArtifactExpiresWithItsSubjectAndIsSelectedByNoRule() {
        repository(REPO, OLDER_THAN_FIVE_DAYS);
        String old = push(REGION, REPO, "old", image("old"), 10);
        String current = push(REGION, REPO, "current", image("current"), 1);
        String oldSignature = push(REGION, REPO, null, referrer(old, "old-sig"), 1);
        String currentSignature = push(REGION, REPO, null, referrer(current, "current-sig"), 10);

        service.applyLifecyclePolicies(NOW);

        assertEquals(Set.of(current, currentSignature), registry.digests(internal(REGION, REPO)));
        assertEquals(Set.of(old, oldSignature), Set.copyOf(registry.deleted));
    }

    /** The subject's "deletion", in the user guide's words, whatever deleted it: here a BatchDeleteImage before. */
    /** An image the registry holds counts as held whether or not Floci recorded its push. */
    @Test
    void anArtifactOfAnImageWithNoPushRecordIsKept() {
        repository(REPO, OLDER_THAN_FIVE_DAYS);
        String subject = registry.put(internal(REGION, REPO), null, image("unrecorded"));
        String signature = push(REGION, REPO, null, referrer(subject, "sig"), 10);

        service.applyLifecyclePolicies(NOW);

        assertEquals(Set.of(subject, signature), registry.digests(internal(REGION, REPO)));
    }

    @Test
    void aManifestListWithNoPushRecordStillKeepsItsImages() {
        repository(REPO, OLDER_THAN_FIVE_DAYS);
        String amd64 = push(REGION, REPO, null, image("amd64"), 10);
        String index = registry.put(internal(REGION, REPO), null, "{\"schemaVersion\":2,\"mediaType\":"
                + "\"application/vnd.oci.image.index.v1+json\",\"manifests\":[{\"digest\":\"" + amd64 + "\"}]}");

        service.applyLifecyclePolicies(NOW);

        assertEquals(Set.of(amd64, index), registry.digests(internal(REGION, REPO)));
    }

    /** A delete the registry refuses stops the sweep: no artifact goes while the image it names is still there. */
    @Test
    void aDeleteTheRegistryRefusesStopsTheSweepOfThatRepository() {
        Repository repo = repository(REPO, OLDER_THAN_FIVE_DAYS);
        String old = push(REGION, REPO, "old", image("old"), 10);
        String signature = push(REGION, REPO, null, referrer(old, "sig"), 1);
        registry.refusedDeletes.add(old);

        assertThrows(IllegalStateException.class, () -> service.applyLifecyclePolicies(NOW));

        assertEquals(Set.of(old, signature), registry.digests(internal(REGION, REPO)));
        assertNull(service.lifecyclePolicyEvaluatedAt(repo, REGION));
    }

    @Test
    void anArtifactWhoseSubjectIsAlreadyGoneExpires() {
        repository(REPO, KEEP_TWO);
        String kept = push(REGION, REPO, "v1", image("v1"), 1);
        String orphan = push(REGION, REPO, null, referrer(digest(image("deleted")), "sig"), 1);

        service.applyLifecyclePolicies(NOW);

        assertEquals(Set.of(kept), registry.digests(internal(REGION, REPO)));
        assertEquals(List.of(orphan), registry.deleted);
    }

    @Test
    void eachRepositoryIsSweptInItsOwnRegion() {
        repository(REPO, KEEP_TWO);
        repository("eu-west-1", "other", OLDER_THAN_FIVE_DAYS);
        repository("plain", null);
        String here = push(REGION, REPO, "v1", image("here"), 30);
        String there = push("eu-west-1", "other", "v1", image("there"), 30);
        String untouched = push(REGION, "plain", "v1", image("plain"), 30);

        service.applyLifecyclePolicies(NOW);

        assertEquals(Set.of(here), registry.digests(internal(REGION, REPO)));
        assertEquals(Set.of(), registry.digests(internal("eu-west-1", "other")));
        assertEquals(Set.of(untouched), registry.digests(internal(REGION, "plain")));
        assertEquals(List.of(there), registry.deleted);
    }

    @Test
    void nothingIsSweptWhileTheRegistryIsNotRunning() {
        Repository repo = repository(REPO, OLDER_THAN_FIVE_DAYS);
        String old = push(REGION, REPO, "v1", image("v1"), 30);
        when(registryManager.isStarted()).thenReturn(false);

        service.applyLifecyclePolicies(NOW);

        assertEquals(Set.of(old), registry.digests(internal(REGION, REPO)));
        assertEquals(0, registry.requests.get());
        assertNull(service.lifecyclePolicyEvaluatedAt(repo, REGION));
    }

    /** A policy stored before every rule was checked, which PutLifecyclePolicy now refuses, is left unapplied. */
    @Test
    void aStoredPolicyThatIsNowRefusedIsNotApplied() {
        Repository repo = repository(REPO, null);
        repo.setLifecyclePolicyText(policy("{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"tagged\","
                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1},\"action\":{\"type\":\"expire\"}}"));
        repositories.put(REGION + "::" + ACCOUNT + "::" + REPO, repo);
        String v1 = push(REGION, REPO, "v1", image("v1"), 30);
        String v2 = push(REGION, REPO, "v2", image("v2"), 20);

        service.applyLifecyclePolicies(NOW);

        assertEquals(Set.of(v1, v2), registry.digests(internal(REGION, REPO)));
        assertNull(service.lifecyclePolicyEvaluatedAt(repo, REGION));
    }

    @Test
    void aRepositoryTheRegistryFailsForIsRetriedLaterWhileTheOthersAreSwept() {
        repository("broken", OLDER_THAN_FIVE_DAYS);
        repository(REPO, OLDER_THAN_FIVE_DAYS);
        String old = push(REGION, REPO, "v1", image("v1"), 30);
        push(REGION, "broken", "v1", image("broken"), 30);
        registry.failing.add(internal(REGION, "broken"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.applyLifecyclePolicies(NOW));

        assertTrue(failure.getMessage().contains(REGION + "::" + ACCOUNT + "::broken"), failure.getMessage());
        assertEquals(List.of(old), registry.deleted);
    }

    @Test
    void aPolicyPutSinceTheSweepReadsAsNeverApplied() {
        Repository repo = repository(REPO, KEEP_TWO);
        service.applyLifecyclePolicies(NOW);
        assertEquals(NOW, service.lifecyclePolicyEvaluatedAt(repo, REGION));

        service.putLifecyclePolicy(REPO, null, OLDER_THAN_FIVE_DAYS, REGION);

        assertNull(service.lifecyclePolicyEvaluatedAt(service.getLifecyclePolicy(REPO, null, REGION), REGION));
    }

    @Test
    void deletingThePolicyAnswersWithItAndWhenItWasLastApplied() {
        repository(REPO, KEEP_TWO);
        service.applyLifecyclePolicies(NOW);

        EcrService.DeletedLifecyclePolicy deleted = service.deleteLifecyclePolicy(REPO, null, REGION);

        assertEquals(new EcrService.DeletedLifecyclePolicy(ACCOUNT, REPO, KEEP_TWO, NOW), deleted);
        service.putLifecyclePolicy(REPO, null, KEEP_TWO, REGION);
        assertNull(service.lifecyclePolicyEvaluatedAt(service.getLifecyclePolicy(REPO, null, REGION), REGION));
    }

    /**
     * The images are read without the repository's write lock, so a push is never held up by the reads. A write that
     * ends between the reads and the deletes puts the expiry off to the next sweep, which reads the images again.
     */
    @Test
    void aWriteWhileThePolicyIsEvaluatedPutsTheExpiryOffToTheNextSweep() throws InterruptedException {
        Repository repo = repository(REPO, OLDER_THAN_FIVE_DAYS);
        String old = push(REGION, REPO, "v1", image("v1"), 30);
        service.lockRepositoryWrites(internal(REGION, REPO));
        Thread sweep = new Thread(() -> service.applyLifecyclePolicies(NOW));
        try {
            sweep.start();
            awaitParkedOnALock(sweep);
            assertEquals(1, registry.tagListings.get());
            assertEquals(List.of(), registry.deleted);
        } finally {
            service.unlockRepositoryWrites(internal(REGION, REPO));
        }
        sweep.join(Duration.ofSeconds(10).toMillis());

        assertEquals(List.of(), registry.deleted);
        assertNull(service.lifecyclePolicyEvaluatedAt(repo, REGION));
        service.applyLifecyclePolicies(NOW);
        assertEquals(List.of(old), registry.deleted);
    }

    @Test
    void aSweepThatExpiresNothingNeverTakesTheWriteLock() throws InterruptedException {
        Repository repo = repository(REPO, OLDER_THAN_FIVE_DAYS);
        push(REGION, REPO, "v1", image("v1"), 1);
        service.lockRepositoryWrites(internal(REGION, REPO));
        try {
            Thread sweep = new Thread(() -> service.applyLifecyclePolicies(NOW));
            sweep.start();
            sweep.join(Duration.ofSeconds(10).toMillis());

            assertEquals(NOW, service.lifecyclePolicyEvaluatedAt(repo, REGION));
        } finally {
            service.unlockRepositoryWrites(internal(REGION, REPO));
        }
    }

    /**
     * The policy is applied as it stood when the sweep came to the repository: a policy put meanwhile waits for the
     * sweep, then reads as never applied.
     */
    @Test
    void aPolicyPutWhileTheSweepAppliesTheOldOneWaitsForIt() throws InterruptedException {
        repository(REPO, OLDER_THAN_FIVE_DAYS);
        String old = push(REGION, REPO, "v1", image("v1"), 30);
        CountDownLatch gate = registry.holdTagListings();
        Thread sweep = new Thread(() -> service.applyLifecyclePolicies(NOW));
        Thread put = new Thread(() -> service.putLifecyclePolicy(REPO, null, KEEP_TWO, REGION));
        try {
            sweep.start();
            registry.awaitHeldTagListing();
            put.start();
            awaitParkedOnALock(put);
        } finally {
            gate.countDown();
        }
        sweep.join(Duration.ofSeconds(10).toMillis());
        put.join(Duration.ofSeconds(10).toMillis());

        assertEquals(List.of(old), registry.deleted);
        assertEquals(KEEP_TWO, service.getLifecyclePolicy(REPO, null, REGION).getLifecyclePolicyText());
        assertNull(service.lifecyclePolicyEvaluatedAt(service.getLifecyclePolicy(REPO, null, REGION), REGION));
    }

    @Test
    void aPolicyDeletedWhileTheSweepAppliesItWaitsForIt() throws InterruptedException {
        repository(REPO, OLDER_THAN_FIVE_DAYS);
        String old = push(REGION, REPO, "v1", image("v1"), 30);
        CountDownLatch gate = registry.holdTagListings();
        Thread sweep = new Thread(() -> service.applyLifecyclePolicies(NOW));
        Thread delete = new Thread(() -> service.deleteLifecyclePolicy(REPO, null, REGION));
        try {
            sweep.start();
            registry.awaitHeldTagListing();
            delete.start();
            awaitParkedOnALock(delete);
        } finally {
            gate.countDown();
        }
        sweep.join(Duration.ofSeconds(10).toMillis());
        delete.join(Duration.ofSeconds(10).toMillis());

        assertEquals(List.of(old), registry.deleted);
        assertThrows(AwsException.class, () -> service.getLifecyclePolicy(REPO, null, REGION));
    }

    /** A repository deleted while the sweep applies its policy waits for it, and a new one by its name starts afresh. */
    @Test
    void aRepositoryDeletedWhileTheSweepAppliesItsPolicyWaitsForIt() throws InterruptedException {
        repository(REPO, KEEP_TWO);
        CountDownLatch gate = registry.holdTagListings();
        Thread sweep = new Thread(() -> service.applyLifecyclePolicies(NOW));
        Thread delete = new Thread(() -> service.deleteRepository(REPO, null, true, REGION));
        try {
            sweep.start();
            registry.awaitHeldTagListing();
            delete.start();
            awaitParkedOnALock(delete);
        } finally {
            gate.countDown();
        }
        sweep.join(Duration.ofSeconds(10).toMillis());
        delete.join(Duration.ofSeconds(10).toMillis());

        assertNull(service.lifecyclePolicyEvaluatedAt(repository(REPO, KEEP_TWO), REGION));
    }

    @Test
    void aResetForgetsWhenEachPolicyWasApplied() {
        Repository repo = repository(REPO, KEEP_TWO);
        service.applyLifecyclePolicies(NOW);

        service.clear();

        assertNull(service.lifecyclePolicyEvaluatedAt(repo, REGION));
    }

    /** A manifest's content never changes, so each is read once; the catalog is read once a sweep. */
    @Test
    void eachManifestIsReadOnceAndTheCatalogOnceASweep() {
        repository(REPO, OLDER_THAN_FIVE_DAYS);
        repository("other", OLDER_THAN_FIVE_DAYS);
        push(REGION, REPO, "v1", image("v1"), 1);
        push(REGION, REPO, null, image("u1"), 1);
        push(REGION, "other", "v1", image("other"), 1);

        service.applyLifecyclePolicies(NOW);
        service.applyLifecyclePolicies(NOW);

        assertEquals(3, registry.manifestReads.get());
        assertEquals(2, registry.catalogReads.get());
    }

    private static void awaitParkedOnALock(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!parkedOnALock(thread) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(parkedOnALock(thread), thread.getName() + " should wait for a repository lock");
    }

    /** Both repository locks are a semaphore per repository, which a waiting thread parks on; a registry call is not. */
    private static boolean parkedOnALock(Thread thread) {
        Object blocker = LockSupport.getBlocker(thread);
        return blocker != null && blocker.getClass().getEnclosingClass() == Semaphore.class;
    }

    @Test
    void nothingIsSweptWhileEcrIsDisabled() {
        repository(REPO, OLDER_THAN_FIVE_DAYS);
        String old = push(REGION, REPO, "v1", image("v1"), 30);
        int before = registry.requests.get();
        when(config.services().ecr().enabled()).thenReturn(false);

        service.applyLifecyclePolicies(NOW);

        assertEquals(Set.of(old), registry.digests(internal(REGION, REPO)));
        assertEquals(before, registry.requests.get());
    }

    @Test
    void aRepositoryCreatedAgainUnderTheSameNameStartsNeverApplied() {
        repository(REPO, KEEP_TWO);
        service.applyLifecyclePolicies(NOW);

        service.deleteRepository(REPO, null, true, REGION);
        Repository again = repository(REPO, KEEP_TWO);

        assertNull(service.lifecyclePolicyEvaluatedAt(again, REGION));
    }

    private Repository repository(String name, String policy) {
        return repository(REGION, name, policy);
    }

    private Repository repository(String region, String name, String policy) {
        service.createRepository(name, null, null, null, null, null, null, region);
        registry.create(internal(region, name));
        if (policy != null) {
            service.putLifecyclePolicy(name, null, policy, region);
        }
        return service.describeRepositories(List.of(name), null, region).get(0);
    }

    private String push(String region, String repo, String tag, String manifest, int pushedDaysAgo) {
        String digest = registry.put(internal(region, repo), tag, manifest);
        service.recordImagePush(ACCOUNT, region, repo, digest, NOW.minus(Duration.ofDays(pushedDaysAgo)));
        return digest;
    }

    private Set<String> recordedDigests(String region, String repo) {
        String prefix = region + "::" + ACCOUNT + "::" + repo + "::";
        Set<String> digests = new HashSet<>();
        pushRecords.scan(key -> key.startsWith(prefix)).forEach(record -> digests.add(record.getDigest()));
        return digests;
    }

    private static String internal(String region, String repo) {
        return ACCOUNT + "/" + region + "/" + repo;
    }

    private static String policy(String... rules) {
        return "{\"rules\":[" + String.join(",", rules) + "]}";
    }

    private static String image(String name) {
        return "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"config\":{},"
                + "\"layers\":[],\"annotations\":{\"name\":\"" + name + "\"}}";
    }

    private static String referrer(String subject, String name) {
        return "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"artifactType\":\"application/vnd.example.signature\",\"config\":{},\"layers\":[],"
                + "\"subject\":{\"digest\":\"" + subject + "\"},\"annotations\":{\"name\":\"" + name + "\"}}";
    }

    private static String digest(String manifest) {
        return "sha256:" + Digests.sha256Hex(manifest.getBytes(StandardCharsets.UTF_8));
    }

    /** The distribution API calls the sweep makes: catalog, tag list, manifest HEAD, GET and DELETE. */
    private static final class FakeRegistry implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, Map<String, String>> manifests = new ConcurrentHashMap<>();
        private final Map<String, Map<String, String>> tags = new ConcurrentHashMap<>();
        private final Set<String> failing = ConcurrentHashMap.newKeySet();
        private final List<String> deleted = new ArrayList<>();
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger tagListings = new AtomicInteger();
        private final AtomicInteger manifestReads = new AtomicInteger();
        private final AtomicInteger catalogReads = new AtomicInteger();
        private final Set<String> refusedDeletes = ConcurrentHashMap.newKeySet();
        private final CountDownLatch tagListingHeld = new CountDownLatch(1);
        private volatile CountDownLatch tagListingGate;

        private FakeRegistry() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v2/", this::handle);
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private void create(String repo) {
            manifests.put(repo, new TreeMap<>());
            tags.put(repo, new TreeMap<>());
        }

        private String put(String repo, String tag, String manifest) {
            String digest = digest(manifest);
            manifests.get(repo).put(digest, manifest);
            if (tag != null) {
                tags.get(repo).put(tag, digest);
            }
            return digest;
        }

        private Set<String> digests(String repo) {
            return Set.copyOf(manifests.getOrDefault(repo, Map.of()).keySet());
        }

        /** Holds every tag listing until the returned gate opens. */
        private CountDownLatch holdTagListings() {
            tagListingGate = new CountDownLatch(1);
            return tagListingGate;
        }

        private void awaitHeldTagListing() throws InterruptedException {
            assertTrue(tagListingHeld.await(10, TimeUnit.SECONDS), "no tag listing reached the registry");
        }

        private synchronized void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath().substring("/v2/".length());
            String method = exchange.getRequestMethod();
            if (path.equals("_catalog")) {
                catalogReads.incrementAndGet();
                send(exchange, 200, "{\"repositories\":[\"" + String.join("\",\"", manifests.keySet()) + "\"]}",
                        "application/json");
                return;
            }
            if (path.endsWith("/tags/list")) {
                tagListings.incrementAndGet();
                CountDownLatch gate = tagListingGate;
                if (gate != null) {
                    tagListingHeld.countDown();
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                String repo = path.substring(0, path.length() - "/tags/list".length());
                if (failing.contains(repo)) {
                    send(exchange, 500, "{}", "application/json");
                    return;
                }
                send(exchange, 200, "{\"name\":\"" + repo + "\",\"tags\":[" + (tags.get(repo).isEmpty() ? ""
                        : "\"" + String.join("\",\"", tags.get(repo).keySet()) + "\"") + "]}", "application/json");
                return;
            }
            int split = path.lastIndexOf("/manifests/");
            String repo = path.substring(0, split);
            String reference = path.substring(split + "/manifests/".length());
            String digest = reference.startsWith("sha256:") ? reference : tags.get(repo).get(reference);
            String manifest = digest == null ? null : manifests.get(repo).get(digest);
            if (manifest == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            switch (method) {
                case "DELETE" -> {
                    if (refusedDeletes.contains(digest)) {
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                        return;
                    }
                    manifests.get(repo).remove(digest);
                    tags.get(repo).values().removeIf(digest::equals);
                    deleted.add(digest);
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                }
                case "HEAD" -> {
                    exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                }
                default -> {
                    manifestReads.incrementAndGet();
                    exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                    send(exchange, 200, manifest, "application/vnd.oci.image.manifest.v1+json");
                }
            }
        }

        private static void send(HttpExchange exchange, int status, String body, String contentType)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
