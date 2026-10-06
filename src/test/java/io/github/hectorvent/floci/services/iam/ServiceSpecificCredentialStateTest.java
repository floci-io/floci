package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.iam.model.ServiceSpecificCredential;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The properties of the service-specific credential store the wire tests cannot reach: that it
 * survives a restart, that one account cannot see another's, that the per-service quota holds when
 * creates race, and that a user's credentials block a delete and follow a rename.
 */
class ServiceSpecificCredentialStateTest {

    private static final String ACCOUNT_A = "000000000000";
    private static final String ACCOUNT_B = "111111111111";
    private static final String CODECOMMIT = "codecommit.amazonaws.com";
    private static final String CASSANDRA = "cassandra.amazonaws.com";
    private static final String BEDROCK = "bedrock.amazonaws.com";
    private static final String LOGS = "logs.amazonaws.com";

    @SuppressWarnings("unchecked")
    private static Instance<RequestContext> requestContextFor(AtomicReference<String> accountId) {
        RequestContext rc = mock(RequestContext.class);
        when(rc.getAccountId()).thenAnswer(invocation -> accountId.get());
        Instance<RequestContext> inst = mock(Instance.class);
        when(inst.get()).thenReturn(rc);
        return inst;
    }

    private static IamService newService(
            StorageBackend<String, ServiceSpecificCredential> credentials, String defaultAccount) {
        return new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                credentials,
                new RegionResolver("us-east-1", defaultAccount), false, null);
    }

    /**
     * The secret half is disclosed once and stored, so a restart that lost it would leave a
     * credential nobody can authenticate with and no way to tell. Checked on both shapes.
     */
    @Test
    void aCredentialSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("iam-service-credentials.json");

        PersistentStorage<String, ServiceSpecificCredential> first =
                new PersistentStorage<>(file, new TypeReference<>() {});
        first.load();
        IamService before = newService(first, ACCOUNT_A);
        before.createUser("restart-user", "/");
        ServiceSpecificCredential password =
                before.createServiceSpecificCredential("restart-user", CODECOMMIT, null);
        ServiceSpecificCredential apiKey =
                before.createServiceSpecificCredential("restart-user", BEDROCK, 30);
        before.updateServiceSpecificCredential("restart-user",
                password.getServiceSpecificCredentialId(), "Inactive");

        PersistentStorage<String, ServiceSpecificCredential> second =
                new PersistentStorage<>(file, new TypeReference<>() {});
        second.load();
        IamService after = newService(second, ACCOUNT_A);
        after.createUser("restart-user", "/");
        Map<String, ServiceSpecificCredential> reloaded =
                after.listServiceSpecificCredentials("restart-user", null, false).stream()
                        .collect(Collectors.toMap(
                                ServiceSpecificCredential::getServiceName, c -> c));

        assertEquals(2, reloaded.size());
        ServiceSpecificCredential reloadedPassword = reloaded.get(CODECOMMIT);
        assertEquals(password.getServiceSpecificCredentialId(),
                reloadedPassword.getServiceSpecificCredentialId());
        assertEquals(password.getServiceUserName(), reloadedPassword.getServiceUserName());
        assertEquals(password.getServicePassword(), reloadedPassword.getServicePassword(),
                "the password must survive: it is disclosed once and never recoverable");
        // A default of Active would hide the loss of this field, so it is the one to check.
        assertEquals("Inactive", reloadedPassword.getStatus());
        assertEquals(password.getCreateDate(), reloadedPassword.getCreateDate());

        ServiceSpecificCredential reloadedApiKey = reloaded.get(BEDROCK);
        assertEquals(apiKey.getServiceCredentialAlias(),
                reloadedApiKey.getServiceCredentialAlias());
        assertEquals(apiKey.getServiceCredentialSecret(),
                reloadedApiKey.getServiceCredentialSecret());
        assertEquals(apiKey.getExpirationDate(), reloadedApiKey.getExpirationDate(),
                "an expiry that did not survive would make an expired credential usable");
    }

    /**
     * One scoped store behind two services, each defaulting to its own account, because the
     * service user name is minted from the account and so has to differ between them.
     */
    @Test
    void oneAccountCannotSeeAnothersCredentials() {
        AtomicReference<String> account = new AtomicReference<>(ACCOUNT_A);
        AccountAwareStorageBackend<ServiceSpecificCredential> store =
                new AccountAwareStorageBackend<>(
                        new InMemoryStorage<>(), requestContextFor(account), ACCOUNT_A);
        IamService inAccountA = newService(store, ACCOUNT_A);
        IamService inAccountB = newService(store, ACCOUNT_B);
        inAccountA.createUser("shared-name", "/");
        inAccountB.createUser("shared-name", "/");

        ServiceSpecificCredential inA =
                inAccountA.createServiceSpecificCredential("shared-name", CODECOMMIT, null);
        assertEquals(1,
                inAccountA.listServiceSpecificCredentials("shared-name", null, false).size());

        // Each service has its own users store, so the user is already present under the same name
        // on both sides. Only the credential store is shared, so a leak is the only thing that can
        // fail this.
        account.set(ACCOUNT_B);
        assertEquals(0,
                inAccountB.listServiceSpecificCredentials("shared-name", null, false).size(),
                "account B must not see account A's credential");

        // And B filling its own quota must not be refused for what A already holds.
        ServiceSpecificCredential inB =
                inAccountB.createServiceSpecificCredential("shared-name", CODECOMMIT, null);
        inAccountB.createServiceSpecificCredential("shared-name", CODECOMMIT, null);
        assertEquals(2,
                inAccountB.listServiceSpecificCredentials("shared-name", null, false).size());
        // The service user name carries the account, so B's first is not a copy of A's.
        assertTrue(inB.getServiceUserName().endsWith("-at-" + ACCOUNT_B), inB.getServiceUserName());
        assertNotEquals(inA.getServiceUserName(), inB.getServiceUserName());

        // Nor may B reach A's credential by its id, which it can read from nowhere but is a guess
        // away in a flat store.
        assertThrows(AwsException.class, () -> inAccountB.deleteServiceSpecificCredential(
                "shared-name", inA.getServiceSpecificCredentialId()));

        account.set(ACCOUNT_A);
        assertEquals(1,
                inAccountA.listServiceSpecificCredentials("shared-name", null, false).size(),
                "account A's view must be unchanged by account B's creates");
    }

    /**
     * The quota is counted inside the same lock as the write, so concurrent creates cannot both
     * see room for the last slot. Asserted by counting what actually succeeded, because an
     * invariant phrased as "the store holds at most two" passes with the lock removed too.
     */
    @Test
    void concurrentCreatesCannotExceedTheQuota() throws Exception {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("race-user", "/");

        int attempts = 12;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        service.createServiceSpecificCredential("race-user", CODECOMMIT, null);
                        accepted.incrementAndGet();
                    } catch (AwsException expected) {
                        // LimitExceeded for the losers, which is the point of the race.
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "creates did not finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, accepted.get(), "exactly the quota should have been accepted");
        assertEquals(2, service.listServiceSpecificCredentials("race-user", null, false).size());
    }

    /**
     * The quota is per service, and three services racing at once is where a count taken over the
     * wrong set shows: counting the user's credentials rather than the user's for that service
     * would stop after two in total.
     */
    @Test
    void racingCreatesAcrossServicesEachGetTheirOwnQuota() throws Exception {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("multi-race", "/");

        List<String> services = List.of(CODECOMMIT, CASSANDRA, BEDROCK);
        int perService = 6;
        ExecutorService pool = Executors.newFixedThreadPool(services.size() * perService);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (String serviceName : services) {
                for (int i = 0; i < perService; i++) {
                    pool.submit(() -> {
                        start.await();
                        try {
                            service.createServiceSpecificCredential(
                                    "multi-race", serviceName, null);
                        } catch (AwsException expected) {
                            // LimitExceeded once that service has its two.
                        }
                        return null;
                    });
                }
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "creates did not finish");
        } finally {
            pool.shutdownNow();
        }

        List<ServiceSpecificCredential> held =
                service.listServiceSpecificCredentials("multi-race", null, false);
        assertEquals(6, held.size(), "two for each of the three services");
        for (String serviceName : services) {
            assertEquals(2, held.stream()
                            .filter(c -> serviceName.equals(c.getServiceName())).count(),
                    "two for " + serviceName);
        }
    }

    /**
     * Two credentials for one service must not share a service user name: it is what the caller
     * authenticates with, so a collision makes one of them unusable. The {@code +n} comes from a
     * count taken under the lock, which is the only reason racing creates cannot both read zero.
     */
    @Test
    void racingCreatesNeverShareAServiceUserName() throws Exception {
        for (int trial = 0; trial < 60; trial++) {
            IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
            service.createUser("suffix-race", "/");

            Set<String> names = ConcurrentHashMap.newKeySet();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                for (int i = 0; i < 2; i++) {
                    pool.submit(() -> {
                        start.await();
                        names.add(service.createServiceSpecificCredential(
                                "suffix-race", CODECOMMIT, null).getServiceUserName());
                        return null;
                    });
                }
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "trial did not finish");
            } finally {
                pool.shutdownNow();
            }

            assertEquals(2, names.size(), "trial " + trial
                    + ": both credentials got the same service user name " + names);
        }
    }

    /** AWS lists the service-specific credential among the items to remove before a user. */
    @Test
    void aUserWithACredentialCannotBeDeleted() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("blocked-user", "/");
        String id = service.createServiceSpecificCredential("blocked-user", CODECOMMIT, null)
                .getServiceSpecificCredentialId();

        AwsException refused = assertThrows(AwsException.class,
                () -> service.deleteUser("blocked-user"));
        assertEquals("DeleteConflict", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("service-specific credential"),
                "the message should name what is in the way: " + refused.getMessage());

        service.deleteServiceSpecificCredential("blocked-user", id);
        service.deleteUser("blocked-user");
    }

    /**
     * A rename must carry the credentials with it. Left behind, one is stranded on a user name
     * that no longer exists: invisible to its owner, because listing goes through the user, and so
     * undeletable.
     */
    @Test
    void aRenameCarriesTheCredentials() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("before-rename", "/");
        ServiceSpecificCredential created =
                service.createServiceSpecificCredential("before-rename", CODECOMMIT, null);

        service.updateUser("before-rename", "after-rename", null);

        List<ServiceSpecificCredential> moved =
                service.listServiceSpecificCredentials("after-rename", null, false);
        assertEquals(1, moved.size(), "the credential should have followed the rename");
        assertEquals(created.getServiceSpecificCredentialId(),
                moved.getFirst().getServiceSpecificCredentialId());
        assertEquals("after-rename", moved.getFirst().getUserName());
        // The service user name is minted from the name at creation and is what the caller
        // authenticates with, so the rename leaves it alone rather than silently invalidating it.
        assertEquals(created.getServiceUserName(), moved.getFirst().getServiceUserName());

        service.deleteServiceSpecificCredential("after-rename",
                created.getServiceSpecificCredentialId());
        service.deleteUser("after-rename");
    }

    /**
     * A create must never land on a user DeleteUser has just removed. Both hold the credential
     * lock, so only two orders exist: the delete is refused because a credential is there, or the
     * create fails because the user is gone. A credential owned by a user that does not exist is
     * the state neither order may produce, and nothing in the API would report it, so this asserts
     * against the store.
     */
    @Test
    void aCreateNeverStrandsACredentialOnADeletedUser() throws Exception {
        for (int trial = 0; trial < 60; trial++) {
            InMemoryStorage<String, ServiceSpecificCredential> store = new InMemoryStorage<>();
            IamService service = newService(store, ACCOUNT_A);
            service.createUser("delete-race", "/");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                pool.submit(() -> {
                    start.await();
                    try {
                        service.createServiceSpecificCredential("delete-race", CODECOMMIT, null);
                    } catch (AwsException expected) {
                        // NoSuchEntity when the delete won.
                    }
                    return null;
                });
                pool.submit(() -> {
                    start.await();
                    try {
                        service.deleteUser("delete-race");
                    } catch (AwsException expected) {
                        // DeleteConflict when the create won.
                    }
                    return null;
                });
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "trial did not finish");
            } finally {
                pool.shutdownNow();
            }

            boolean userExists;
            try {
                service.getUser("delete-race");
                userExists = true;
            } catch (AwsException gone) {
                userExists = false;
            }
            if (!userExists) {
                assertTrue(store.scan(key -> true).isEmpty(),
                        "trial " + trial + ": credential left behind on a deleted user");
            }
        }
    }

    /**
     * The same hazard on the rename path. Ran many times because the window between capturing the
     * credentials to move and republishing the user is a few statements wide: a handful of trials
     * passes even with the rename's lock removed.
     */
    @Test
    void aCreateNeverStrandsACredentialOnTheOldNameDuringARename() throws Exception {
        for (int trial = 0; trial < 300; trial++) {
            InMemoryStorage<String, ServiceSpecificCredential> store = new InMemoryStorage<>();
            IamService service = newService(store, ACCOUNT_A);
            service.createUser("rename-race", "/");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                pool.submit(() -> {
                    start.await();
                    try {
                        service.createServiceSpecificCredential("rename-race", CODECOMMIT, null);
                    } catch (AwsException expected) {
                        // NoSuchEntity when the rename won.
                    }
                    return null;
                });
                pool.submit(() -> {
                    start.await();
                    service.updateUser("rename-race", "renamed", null);
                    return null;
                });
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "trial did not finish");
            } finally {
                pool.shutdownNow();
            }

            List<ServiceSpecificCredential> all = store.scan(key -> true);
            for (ServiceSpecificCredential credential : all) {
                assertEquals("renamed", credential.getUserName(), "trial " + trial
                        + ": credential stranded on the pre-rename name");
            }
            // And it is reachable, which is what being stranded costs.
            assertEquals(all.size(),
                    service.listServiceSpecificCredentials("renamed", null, false).size());
        }
    }

    /**
     * The version behind the {@code +n} comes from what is free, not from how many exist. Holding
     * only the suffixed credential, a count would mint the same suffix again and two live
     * credentials would share the name the caller authenticates with.
     */
    @Test
    void aFreedServiceUserNameIsReusedAndALiveOneIsNot() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("suffix-user", "/");

        ServiceSpecificCredential first =
                service.createServiceSpecificCredential("suffix-user", CODECOMMIT, null);
        ServiceSpecificCredential second =
                service.createServiceSpecificCredential("suffix-user", CODECOMMIT, null);
        assertEquals("suffix-user-at-" + ACCOUNT_A, first.getServiceUserName());
        assertEquals("suffix-user+1-at-" + ACCOUNT_A, second.getServiceUserName());

        service.deleteServiceSpecificCredential(
                "suffix-user", first.getServiceSpecificCredentialId());
        ServiceSpecificCredential replacement =
                service.createServiceSpecificCredential("suffix-user", CODECOMMIT, null);
        assertEquals(first.getServiceUserName(), replacement.getServiceUserName(),
                "the freed name is the lowest free one");
        assertNotEquals(second.getServiceUserName(), replacement.getServiceUserName(),
                "the replacement took the name of the credential still in use");

        // The other direction too: free the suffixed one and the suffix comes back, not the base.
        service.deleteServiceSpecificCredential(
                "suffix-user", second.getServiceSpecificCredentialId());
        ServiceSpecificCredential third =
                service.createServiceSpecificCredential("suffix-user", CODECOMMIT, null);
        assertEquals(second.getServiceUserName(), third.getServiceUserName());
    }

    /**
     * The same reasoning for a long-term key's alias, which carries the same version. Documented
     * as including "the IAM user name and a suffix containing version and creation information".
     */
    @Test
    void aLongTermKeyAliasCarriesTheUserNameAndAFreeVersion() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("alias-user", "/");

        ServiceSpecificCredential first =
                service.createServiceSpecificCredential("alias-user", BEDROCK, null);
        ServiceSpecificCredential second =
                service.createServiceSpecificCredential("alias-user", BEDROCK, null);
        String today = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
                .format(first.getCreateDate());

        assertEquals("alias-user+v1-" + today, first.getServiceCredentialAlias());
        assertEquals("alias-user+v2-" + today, second.getServiceCredentialAlias());
        // The alias is the public half, so it must satisfy serviceCredentialAlias's own pattern.
        assertTrue(first.getServiceCredentialAlias().matches("[\\w+=,.@-]+"),
                first.getServiceCredentialAlias());
        assertTrue(first.getServiceCredentialAlias().length() <= 200);

        service.deleteServiceSpecificCredential(
                "alias-user", first.getServiceSpecificCredentialId());
        ServiceSpecificCredential replacement =
                service.createServiceSpecificCredential("alias-user", BEDROCK, null);
        assertNotEquals(second.getServiceCredentialAlias(),
                replacement.getServiceCredentialAlias(),
                "the replacement took the alias of the credential still in use");
    }

    /**
     * A credential keeps the service user name it was minted with across a rename, so the freed
     * IAM name must not mint that same credential name a second time. Scoping the uniqueness
     * check to one user's credentials misses this: the new user holds none.
     */
    @Test
    void aRenamedUsersNameCannotBeMintedAgainByItsSuccessor() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("anika", "/");
        ServiceSpecificCredential original =
                service.createServiceSpecificCredential("anika", CODECOMMIT, null);
        assertEquals("anika-at-" + ACCOUNT_A, original.getServiceUserName());

        service.updateUser("anika", "bob", null);
        assertEquals("anika-at-" + ACCOUNT_A, service
                        .listServiceSpecificCredentials("bob", null, false)
                        .getFirst().getServiceUserName(),
                "the rename leaves the minted name alone, which is what creates the hazard");

        // A brand new user takes the freed IAM name and asks for the same service.
        service.createUser("anika", "/");
        ServiceSpecificCredential successor =
                service.createServiceSpecificCredential("anika", CODECOMMIT, null);
        assertNotEquals(original.getServiceUserName(), successor.getServiceUserName(),
                "two live credentials would share the name the caller authenticates with");
    }

    /**
     * An alias carries no service, so two services' keys for one user on the same day would
     * collide on {@code <user>+v1-<date>} unless the version is checked across the whole store.
     * This needs no rename and no delete: it is two creates.
     */
    @Test
    void twoServicesKeysForOneUserCannotShareAnAliasOnTheSameDay() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("alias-clash", "/");

        ServiceSpecificCredential bedrock =
                service.createServiceSpecificCredential("alias-clash", BEDROCK, null);
        ServiceSpecificCredential logs =
                service.createServiceSpecificCredential("alias-clash", LOGS, null);

        assertNotEquals(bedrock.getServiceCredentialAlias(), logs.getServiceCredentialAlias(),
                "both were created the same day and the alias has no service in it");
        // Both still carry the user name and a version, which is what AWS documents.
        assertTrue(bedrock.getServiceCredentialAlias().startsWith("alias-clash+v"),
                bedrock.getServiceCredentialAlias());
        assertTrue(logs.getServiceCredentialAlias().startsWith("alias-clash+v"),
                logs.getServiceCredentialAlias());
    }

    /** The same hazard for the password shape: two services, one user, no suffix to tell apart. */
    @Test
    void twoServicesCredentialsForOneUserCannotShareAServiceUserName() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("name-clash", "/");

        ServiceSpecificCredential codecommit =
                service.createServiceSpecificCredential("name-clash", CODECOMMIT, null);
        ServiceSpecificCredential cassandra =
                service.createServiceSpecificCredential("name-clash", CASSANDRA, null);

        assertNotEquals(codecommit.getServiceUserName(), cassandra.getServiceUserName());
    }

    /**
     * A key past its {@code ExpirationDate} reports {@code Expired}, and an update to
     * {@code Active} cannot undo that: the status is derived on read, so the stored value is left
     * as the caller set it while the reported one tells the truth.
     *
     * <p>The expiry has to be planted directly in the store, because the smallest
     * {@code CredentialAgeDays} AWS allows is one day, so no sequence of calls reaches a past
     * expiry.
     */
    @Test
    void aKeyPastItsExpiryReportsExpiredAndCannotBeReactivated() {
        InMemoryStorage<String, ServiceSpecificCredential> store = new InMemoryStorage<>();
        IamService service = newService(store, ACCOUNT_A);
        service.createUser("expiry-user", "/");
        ServiceSpecificCredential credential =
                service.createServiceSpecificCredential("expiry-user", BEDROCK, 1);
        assertEquals("Active", service.reportedStatus(credential));

        credential.setExpirationDate(Instant.now().minusSeconds(60));
        store.put(credential.getServiceSpecificCredentialId(), credential);

        assertEquals("Expired", service.reportedStatus(credential));
        assertEquals("Expired", service.reportedStatus(service
                .listServiceSpecificCredentials("expiry-user", null, false).getFirst()));

        service.updateServiceSpecificCredential(
                "expiry-user", credential.getServiceSpecificCredentialId(), "Active");
        ServiceSpecificCredential reactivated = service
                .listServiceSpecificCredentials("expiry-user", null, false).getFirst();
        assertEquals("Active", reactivated.getStatus(),
                "the stored status is still what the caller set");
        assertEquals("Expired", service.reportedStatus(reactivated),
                "but an expired key cannot be made to report Active");
    }

    /** Expiry is terminal, so it outranks a status the caller set to Inactive. */
    @Test
    void anExpiredKeyReportsExpiredRatherThanInactive() {
        InMemoryStorage<String, ServiceSpecificCredential> store = new InMemoryStorage<>();
        IamService service = newService(store, ACCOUNT_A);
        service.createUser("inactive-user", "/");
        ServiceSpecificCredential credential =
                service.createServiceSpecificCredential("inactive-user", BEDROCK, 1);
        service.updateServiceSpecificCredential(
                "inactive-user", credential.getServiceSpecificCredentialId(), "Inactive");

        credential.setExpirationDate(Instant.now().minusSeconds(60));
        store.put(credential.getServiceSpecificCredentialId(), credential);

        assertEquals("Expired", service.reportedStatus(credential));
    }

    /** A credential with no expiry, or one still in date, reports exactly what is stored. */
    @Test
    void aCredentialWithinOrWithoutAnExpiryReportsItsStoredStatus() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("unexpired-user", "/");

        ServiceSpecificCredential noExpiry =
                service.createServiceSpecificCredential("unexpired-user", CODECOMMIT, null);
        assertNull(noExpiry.getExpirationDate());
        assertEquals("Active", service.reportedStatus(noExpiry));
        service.updateServiceSpecificCredential(
                "unexpired-user", noExpiry.getServiceSpecificCredentialId(), "Inactive");
        assertEquals("Inactive", service.reportedStatus(service
                .listServiceSpecificCredentials("unexpired-user", CODECOMMIT, false).getFirst()),
                "no expiry means the stored status is reported untouched");

        ServiceSpecificCredential inDate =
                service.createServiceSpecificCredential("unexpired-user", BEDROCK, 36600);
        assertEquals("Active", service.reportedStatus(inDate));
    }

    /** The reset replaces only the secret of the shape the service actually uses. */
    @Test
    void theResetTouchesOnlyTheSecretOfTheRelevantShape() {
        IamService service = newService(new InMemoryStorage<>(), ACCOUNT_A);
        service.createUser("reset-user", "/");

        ServiceSpecificCredential password =
                service.createServiceSpecificCredential("reset-user", CODECOMMIT, null);
        String oldPassword = password.getServicePassword();
        ServiceSpecificCredential resetPassword = service.resetServiceSpecificCredential(
                "reset-user", password.getServiceSpecificCredentialId());
        assertNotEquals(oldPassword, resetPassword.getServicePassword());
        assertNull(resetPassword.getServiceCredentialSecret(),
                "a password credential must not grow an API-key secret on reset");

        ServiceSpecificCredential apiKey =
                service.createServiceSpecificCredential("reset-user", BEDROCK, null);
        String oldSecret = apiKey.getServiceCredentialSecret();
        String alias = apiKey.getServiceCredentialAlias();
        ServiceSpecificCredential resetApiKey = service.resetServiceSpecificCredential(
                "reset-user", apiKey.getServiceSpecificCredentialId());
        assertNotEquals(oldSecret, resetApiKey.getServiceCredentialSecret());
        assertEquals(alias, resetApiKey.getServiceCredentialAlias(),
                "the alias identifies the credential, so a reset keeps it");
        assertNull(resetApiKey.getServicePassword(),
                "an API-key credential must not grow a password on reset");
    }
}
