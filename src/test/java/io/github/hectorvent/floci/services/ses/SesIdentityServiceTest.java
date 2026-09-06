package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted identity domain: store ownership, key validation, verification
 * (email and domain), listing, MAIL FROM, the CVET pending registration, identity tags, and the
 * find/save escape hatches the facade's notification flows rely on. The DKIM state machine moved
 * here too and keeps its behavioral coverage in the existing facade-level unit and integration
 * tests, which now exercise it through the delegation; only its creation-time surface is pinned
 * here.
 */
class SesIdentityServiceTest {

    private static final String REGION = "us-east-1";
    private SesIdentityService service;

    @BeforeEach
    void setUp() {
        service = new SesIdentityService(new InMemoryStorage<>(), null, Clock.systemUTC());
    }

    @Test
    void verifyEmailIdentity_createsOnce_returnsExistingOnRepeat() {
        Identity first = service.verifyEmailIdentity("alice@example.com", REGION);
        assertEquals("EmailAddress", first.getIdentityType());
        first.setVerificationStatus("Success");
        service.save(first, REGION);
        Identity second = service.verifyEmailIdentity("alice@example.com", REGION);
        assertEquals("Success", second.getVerificationStatus());
    }

    @Test
    void verifyEmailIdentity_rejectsBlankAndWhitespace() {
        AwsException blank = assertThrows(AwsException.class,
                () -> service.verifyEmailIdentity(" ", REGION));
        assertEquals("Email address is required.", blank.getMessage());
        AwsException padded = assertThrows(AwsException.class,
                () -> service.verifyEmailIdentity(" alice@example.com", REGION));
        assertEquals("Email address must not contain leading or trailing whitespace.",
                padded.getMessage());
    }

    @Test
    void verifyDomainIdentity_generatesDkimTokens_reportsNotStarted() {
        Identity domain = service.verifyDomainIdentity("example.com", REGION);
        assertEquals("Domain", domain.getIdentityType());
        assertEquals("Pending", domain.getVerificationStatus());
        assertEquals("NotStarted", domain.getDkimVerificationStatus());
        assertEquals(3, domain.getDkimTokens().size());
        // Tokens are stable across repeated calls.
        assertEquals(domain.getDkimTokens(),
                service.verifyDomainIdentity("example.com", REGION).getDkimTokens());
        assertEquals(domain.getDkimTokens(), service.verifyDomainDkim("example.com", REGION));
    }

    @Test
    void listIdentities_filtersByType_andRegion() {
        service.verifyEmailIdentity("alice@example.com", REGION);
        service.save(new Identity("example.com", "Domain"), REGION);
        service.verifyEmailIdentity("other@example.com", "eu-west-1");
        assertEquals(2, service.listIdentities(null, REGION).size());
        assertEquals(List.of("example.com"),
                service.listIdentities("Domain", REGION).stream().map(Identity::getIdentity).toList());
    }

    @Test
    void getVerifiedEmailAddresses_onlySuccessfulEmails() {
        Identity verified = service.verifyEmailIdentity("alice@example.com", REGION);
        verified.setVerificationStatus("Success");
        service.save(verified, REGION);
        service.markPendingEmailIdentity("pending@example.com", REGION);
        Identity domain = new Identity("example.com", "Domain");
        domain.setVerificationStatus("Success");
        service.save(domain, REGION);
        assertEquals(List.of("alice@example.com"), service.getVerifiedEmailAddresses(REGION));
    }

    @Test
    void delete_removesTheRecord() {
        service.verifyEmailIdentity("alice@example.com", REGION);
        service.delete("alice@example.com", REGION);
        assertTrue(service.find("alice@example.com", REGION).isEmpty());
    }

    @Test
    void setMailFromDomain_setClearAndValidate() {
        service.verifyEmailIdentity("alice@example.com", REGION);
        service.setMailFromDomain("alice@example.com", "mail.example.com", "RejectMessage", REGION);
        Identity attrs = service.getMailFromAttributes("alice@example.com", REGION);
        assertEquals("mail.example.com", attrs.getMailFromDomain());
        assertEquals("Success", attrs.getMailFromDomainStatus());
        assertEquals("RejectMessage", attrs.getBehaviorOnMxFailure());

        service.setMailFromDomain("alice@example.com", "", null, REGION);
        attrs = service.getMailFromAttributes("alice@example.com", REGION);
        assertEquals(null, attrs.getMailFromDomain());
        assertEquals("UseDefaultValue", attrs.getBehaviorOnMxFailure());

        assertThrows(AwsException.class, () -> service.setMailFromDomain(
                "alice@example.com", "mail.example.com", "BadEnum", REGION));
        AwsException missing = assertThrows(AwsException.class, () -> service.setMailFromDomain(
                "ghost@example.com", "mail.example.com", null, REGION));
        assertEquals("Identity <ghost@example.com> does not exist.", missing.getMessage());
    }

    @Test
    void markPendingEmailIdentity_registersOnce_neverDowngrades() {
        Identity verified = service.verifyEmailIdentity("alice@example.com", REGION);
        verified.setVerificationStatus("Success");
        service.save(verified, REGION);
        service.markPendingEmailIdentity("alice@example.com", REGION);
        assertEquals("Success",
                service.find("alice@example.com", REGION).orElseThrow().getVerificationStatus());

        service.markPendingEmailIdentity("new@example.com", REGION);
        assertEquals("Pending",
                service.find("new@example.com", REGION).orElseThrow().getVerificationStatus());
    }

    @Test
    void createEmailIdentity_buildsTheCompleteRecordInOneWrite() {
        Identity created = service.createEmailIdentity("alice@example.com", "my-cs",
                List.of(new Tag("team", "floci")), REGION, () -> { });
        assertEquals("EmailAddress", created.getIdentityType());

        Identity stored = service.find("alice@example.com", REGION).orElseThrow();
        assertEquals("my-cs", stored.getConfigurationSetName());
        assertEquals(List.of("team"), stored.getTags().stream().map(Tag::key).toList());
    }

    @Test
    void createEmailIdentity_domain_getsDkimTokensAndNotStarted() {
        Identity created = service.createEmailIdentity("example.com", null, null, REGION, null);
        assertEquals("Domain", created.getIdentityType());
        assertEquals(3, created.getDkimTokens().size());
        assertEquals("NotStarted", created.getDkimVerificationStatus());
    }

    @Test
    void createEmailIdentity_duplicateThrows_withAwsGrammar() {
        service.createEmailIdentity("alice@example.com", null, null, REGION, null);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createEmailIdentity("alice@example.com", null, null, REGION, null));
        // "already exist." is AWS's own grammar (probe-confirmed).
        assertEquals("Email identity alice@example.com already exist.", e.getMessage());
    }

    @Test
    void createEmailIdentity_failures_leaveNothingBehind() {
        assertThrows(AwsException.class, () -> service.createEmailIdentity(
                "alice@example.com", null, List.of(new Tag("", "v")), REGION, null));
        assertTrue(service.find("alice@example.com", REGION).isEmpty());

        AwsException configSetGone = new AwsException("ConfigurationSetDoesNotExist",
                "Configuration set <ghost> does not exist.", 400);
        AwsException e = assertThrows(AwsException.class, () -> service.createEmailIdentity(
                "alice@example.com", "ghost", null, REGION, () -> { throw configSetGone; }));
        assertEquals("ConfigurationSetDoesNotExist", e.getErrorCode());
        assertTrue(service.find("alice@example.com", REGION).isEmpty());
    }

    @Test
    void createEmailIdentity_alreadyExists_winsOverLaterChecks() {
        service.createEmailIdentity("alice@example.com", null, null, REGION, null);
        // Existing identity beats both invalid tags and a failing configuration-set check,
        // preserving the wire-visible validation order.
        AwsException e = assertThrows(AwsException.class, () -> service.createEmailIdentity(
                "alice@example.com", "ghost", List.of(new Tag("", "v")), REGION,
                () -> { throw new AwsException("ConfigurationSetDoesNotExist", "boom", 400); }));
        assertEquals("AlreadyExistsException", e.getErrorCode());
    }

    /** Any parked state counts as contending, so a switch to e.g. ReentrantLock stays green. */
    private static boolean isParked(Thread.State state) {
        return state == Thread.State.BLOCKED || state == Thread.State.WAITING
                || state == Thread.State.TIMED_WAITING;
    }

    /** Counts put calls so the single-write guarantee is asserted, not inferred. */
    private static final class CountingStorage extends InMemoryStorage<String, Identity> {
        final AtomicInteger puts = new AtomicInteger();

        @Override
        public void put(String key, Identity value) {
            puts.incrementAndGet();
            super.put(key, value);
        }
    }

    @Test
    void createEmailIdentity_persistsWithExactlyOneWrite() {
        CountingStorage store = new CountingStorage();
        SesIdentityService counted = new SesIdentityService(store, null, Clock.systemUTC());
        counted.createEmailIdentity("alice@example.com", "my-cs",
                List.of(new Tag("team", "floci")), REGION, () -> { });
        assertEquals(1, store.puts.get());
    }

    @Test
    void createEmailIdentity_concurrentDuplicate_loserGetsAlreadyExists() throws Exception {
        CountingStorage store = new CountingStorage();
        SesIdentityService counted = new SesIdentityService(store, null, Clock.systemUTC());
        CountDownLatch insideLock = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        // The configuration-set callback runs inside the creation lock, so it gives the test a
        // deterministic point where the first create holds the lock after its AlreadyExists check.
        Thread first = new Thread(() -> {
            try {
                counted.createEmailIdentity("alice@example.com", "my-cs", null, REGION, () -> {
                    insideLock.countDown();
                    try {
                        proceed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        });
        first.start();

        Thread second = new Thread(() -> {
            try {
                counted.createEmailIdentity("alice@example.com", null, null, REGION, null);
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        });
        // Every wait is bounded and proceed is always released, so a locking regression fails the
        // test instead of wedging the suite.
        try {
            assertTrue(insideLock.await(5, TimeUnit.SECONDS), "first create never entered the lock");
            second.start();
            // The second create must park on the creation lock before the first is released; a
            // TERMINATED second thread means it completed without contending, i.e. the lock is gone.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!isParked(second.getState())) {
                assertTrue(second.getState() != Thread.State.TERMINATED,
                        "second create finished without blocking on the creation lock");
                assertTrue(System.nanoTime() < deadline,
                        "second create never blocked on the creation lock");
                Thread.onSpinWait();
            }
        } finally {
            proceed.countDown();
        }
        first.join(TimeUnit.SECONDS.toMillis(5));
        second.join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(!first.isAlive() && !second.isAlive(), "creates did not finish in time");

        assertEquals(null, firstFailure.get());
        AwsException e = (AwsException) secondFailure.get();
        assertEquals("AlreadyExistsException", e.getErrorCode());
        assertEquals(1, store.puts.get());
        assertEquals("my-cs",
                counted.find("alice@example.com", REGION).orElseThrow().getConfigurationSetName());
    }

    @Test
    void verifyEmailIdentity_contendsWithCreate_neverClobbersTheFullRecord() throws Exception {
        CountingStorage store = new CountingStorage();
        SesIdentityService counted = new SesIdentityService(store, null, Clock.systemUTC());
        CountDownLatch insideLock = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<Identity> verifyResult = new AtomicReference<>();

        Thread creator = new Thread(() -> counted.createEmailIdentity(
                "alice@example.com", "my-cs", List.of(new Tag("team", "floci")), REGION, () -> {
                    insideLock.countDown();
                    try {
                        proceed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        creator.start();

        Thread verifier = new Thread(() ->
                verifyResult.set(counted.verifyEmailIdentity("alice@example.com", REGION)));
        try {
            assertTrue(insideLock.await(5, TimeUnit.SECONDS), "create never entered the lock");
            verifier.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            // The v1 verify path takes the same creation lock, so it must park here rather than
            // overwrite the in-flight create with a bare identity (the lost-update it used to allow).
            while (!isParked(verifier.getState())) {
                assertTrue(verifier.getState() != Thread.State.TERMINATED,
                        "verify finished without contending on the creation lock");
                assertTrue(System.nanoTime() < deadline,
                        "verify never blocked on the creation lock");
                Thread.onSpinWait();
            }
        } finally {
            proceed.countDown();
        }
        creator.join(TimeUnit.SECONDS.toMillis(5));
        verifier.join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(!creator.isAlive() && !verifier.isAlive(), "threads did not finish in time");

        // Verify returned the creator's record instead of replacing it; nothing was lost.
        assertEquals("my-cs", verifyResult.get().getConfigurationSetName());
        assertEquals(1, store.puts.get());
        assertEquals("my-cs",
                counted.find("alice@example.com", REGION).orElseThrow().getConfigurationSetName());
    }

    @Test
    void createEmailIdentity_whitespace_keepsFieldSpecificWording() {
        assertEquals("Email address must not contain leading or trailing whitespace.",
                assertThrows(AwsException.class, () -> create(" alice@example.com")).getMessage());
        assertEquals("Domain must not contain leading or trailing whitespace.",
                assertThrows(AwsException.class, () -> create(" example.com")).getMessage());
        assertTrue(service.listIdentities(null, REGION).isEmpty());
    }

    private void create(String identity) {
        service.createEmailIdentity(identity, null, null, REGION, null);
    }

    @Test
    void tags_lifecycle_andNotFoundMessage() {
        service.verifyEmailIdentity("alice@example.com", REGION);
        service.tag("alice@example.com", REGION, List.of(new Tag("team", "floci")));
        assertEquals(1, service.listTags("alice@example.com", REGION).size());

        service.tag("alice@example.com", REGION, List.of(new Tag("env", "dev")));
        service.untag("alice@example.com", REGION, List.of("team"));
        assertEquals(List.of("env"),
                service.listTags("alice@example.com", REGION).stream().map(Tag::key).toList());

        AwsException e = assertThrows(AwsException.class,
                () -> service.listTags("ghost@example.com", REGION));
        assertEquals("No EmailIdentity present with name: ghost@example.com", e.getMessage());
    }
}
