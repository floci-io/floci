package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.configservice.model.AggregationAuthorization;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AggregationAuthorization is keyed by (AuthorizedAccountId, AuthorizedAwsRegion). Put is an
 * upsert, and the Config model declares no "not found" error for DeleteAggregationAuthorization,
 * so deleting one that is absent succeeds.
 */
class AwsConfigServiceAggregationAuthorizationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String AUTHORIZED_ACCOUNT = "111122223333";

    private AwsConfigService service() {
        return new AwsConfigService(new RegionResolver(REGION, ACCOUNT), null);
    }

    private List<AggregationAuthorization> authorizations(AwsConfigService service, String region) {
        return service.describeAggregationAuthorizations(region, null, null).items();
    }

    @Test
    void putReturnsAnArnAndCreationTime() {
        AwsConfigService service = service();

        AggregationAuthorization created =
                service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);

        AwsArnUtils.Arn arn = AwsArnUtils.parse(created.aggregationAuthorizationArn());
        assertEquals("aws", arn.partition());
        assertEquals("config", arn.service());
        assertEquals(REGION, arn.region());
        assertEquals(ACCOUNT, arn.accountId());
        assertEquals("aggregation-authorization/" + AUTHORIZED_ACCOUNT + "/eu-west-1", arn.resource());
        assertEquals(AUTHORIZED_ACCOUNT, created.authorizedAccountId());
        assertEquals("eu-west-1", created.authorizedAwsRegion());
        assertNotNull(created.creationTime(), "CreationTime must be populated");
    }

    @Test
    void putIsIdempotentForTheSameAccountAndRegion() {
        AwsConfigService service = service();

        AggregationAuthorization first =
                service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);
        AggregationAuthorization second =
                service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);

        assertEquals(first.aggregationAuthorizationArn(), second.aggregationAuthorizationArn());
        assertEquals(first.creationTime(), second.creationTime(),
                "re-authorizing must not restart the creation time");
        assertEquals(1, authorizations(service, REGION).size());
    }

    @Test
    void describeListsEveryAuthorizationSortedByAccountThenRegion() {
        AwsConfigService service = service();
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "ap-south-1", null);
        service.putAggregationAuthorization(REGION, "444455556666", "eu-west-1", null);

        List<AggregationAuthorization> listed = authorizations(service, REGION);

        assertEquals(List.of(
                        AUTHORIZED_ACCOUNT + "|ap-south-1",
                        AUTHORIZED_ACCOUNT + "|eu-west-1",
                        "444455556666|eu-west-1"),
                listed.stream()
                        .map(a -> a.authorizedAccountId() + "|" + a.authorizedAwsRegion())
                        .toList());
    }

    @Test
    void describeIsScopedToTheRequestRegion() {
        AwsConfigService service = service();
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);

        assertEquals(1, authorizations(service, REGION).size());
        assertTrue(authorizations(service, "eu-central-1").isEmpty(),
                "an authorization must not leak into another region's aggregator");
    }

    @Test
    void deleteRemovesOnlyTheNamedAuthorization() {
        AwsConfigService service = service();
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "ap-south-1", null);

        service.deleteAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1");

        assertEquals(List.of("ap-south-1"),
                authorizations(service, REGION).stream()
                        .map(AggregationAuthorization::authorizedAwsRegion).toList());
    }

    @Test
    void deletingAnAbsentAuthorizationSucceeds() {
        AwsConfigService service = service();

        assertDoesNotThrow(() -> service.deleteAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1"));
    }

    @Test
    void deleteClearsTagsEvenWhenTheAuthorizationIsAlreadyGone() {
        AwsConfigService service = service();
        String arn = service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null)
                .aggregationAuthorizationArn();

        // An interrupted delete leaves the entry removed but its tags behind. Reproduce that
        // state, then retry the delete: the retry must still reach the tags.
        service.deleteAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1");
        service.tagResource(arn, List.of(Map.of("Key", "env", "Value", "prod")));
        assertTrue(authorizations(service, REGION).isEmpty(), "precondition: the entry is gone");

        service.deleteAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1");

        assertTrue(service.listTagsForResource(arn).isEmpty(),
                "a retried delete must clear tags an interrupted attempt left on the ARN");
        assertEquals(arn,
                service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null)
                        .aggregationAuthorizationArn(),
                "put reuses the same deterministic ARN, which is why the stale tags mattered");
    }

    @Test
    void tagsSuppliedOnPutAreReadableThroughListTagsForResource() {
        AwsConfigService service = service();

        AggregationAuthorization created = service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT,
                "eu-west-1", List.of(Map.of("Key", "env", "Value", "prod")));

        List<Map<String, String>> tags = service.listTagsForResource(created.aggregationAuthorizationArn());
        assertEquals(List.of(Map.of("Key", "env", "Value", "prod")), tags);
    }

    @Test
    void rePutIgnoresItsTagsAndLeavesTheCreationTagsInPlace() {
        AwsConfigService service = service();
        String arn = service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1",
                        List.of(Map.of("Key", "env", "Value", "prod"),
                                Map.of("Key", "owner", "Value", "platform")))
                .aggregationAuthorizationArn();

        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1",
                List.of(Map.of("Key", "env", "Value", "dev"),
                        Map.of("Key", "tier", "Value", "gold")));

        assertEquals(Map.of("env", "prod", "owner", "platform"), tagMap(service, arn),
                "a put that finds the authorization already there must ignore its Tags: "
                        + "no overwrite of env, and no tier added");

        // Tags do change through the tagging API, which is the only way AWS offers.
        service.tagResource(arn, List.of(Map.of("Key", "env", "Value", "dev")));
        assertEquals(Map.of("env", "dev", "owner", "platform"), tagMap(service, arn),
                "TagResource must still update an existing tag");

        service.untagResource(arn, List.of("owner"));
        assertEquals(Map.of("env", "dev"), tagMap(service, arn),
                "UntagResource must still remove a tag");
    }

    private Map<String, String> tagMap(AwsConfigService service, String arn) {
        return service.listTagsForResource(arn).stream()
                .collect(Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
    }

    @Test
    void deleteDropsTheAuthorizationTagsSoARecreateStartsClean() {
        AwsConfigService service = service();
        AggregationAuthorization created = service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT,
                "eu-west-1", List.of(Map.of("Key", "env", "Value", "prod")));

        service.deleteAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1");
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);

        assertTrue(service.listTagsForResource(created.aggregationAuthorizationArn()).isEmpty(),
                "tags of a deleted authorization must not resurface on the reused ARN");
    }

    @Test
    void rejectsAnAccountIdThatIsNotTwelveDigits() {
        AwsConfigService service = service();

        AwsException ex = assertThrows(AwsException.class,
                () -> service.putAggregationAuthorization(REGION, "12345", "eu-west-1", null));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
    }

    @Test
    void rejectsAMissingAuthorizedRegion() {
        AwsConfigService service = service();

        AwsException ex = assertThrows(AwsException.class,
                () -> service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "  ", null));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
    }

    @Test
    void rejectsALimitAboveTheModeledMaximum() {
        AwsConfigService service = service();

        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeAggregationAuthorizations(REGION, 101, null));
        assertEquals("InvalidLimitException", ex.getErrorCode());
    }

    @Test
    void describePaginatesWithNextToken() {
        AwsConfigService service = service();
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "ap-south-1", null);

        AwsConfigService.Paged<AggregationAuthorization> first =
                service.describeAggregationAuthorizations(REGION, 1, null);
        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());

        AwsConfigService.Paged<AggregationAuthorization> second =
                service.describeAggregationAuthorizations(REGION, 1, first.nextToken());
        assertEquals(1, second.items().size());
        assertNull(second.nextToken());
        assertEquals("eu-west-1", second.items().getFirst().authorizedAwsRegion());
    }

    /**
     * Idempotency that only usually holds is not idempotency. Put is a get-then-put, so without a
     * lock two concurrent Puts for one (account, Region) both find nothing and both create, and
     * the loser's caller is handed an object the store no longer contains.
     *
     * <p>Reference identity is the assertion that catches this, and the obvious alternatives do
     * not: the store is keyed on the pair so its size is 1 either way, the ARN is derived from the
     * key so it is equal either way, and CreationTime is epoch seconds so a racing pair created
     * inside one second is indistinguishable by timestamp too.
     *
     * <p>Many short rounds rather than one wide one: the unlocked window is a few instructions, so
     * what makes the race land is repeated fresh starts, not more threads on one start.
     */
    @Test
    void concurrentPutsForOneKeyAllReturnTheStoredAuthorization() throws Exception {
        int rounds = 300;
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            int mismatches = 0;
            for (int round = 0; round < rounds; round++) {
                AwsConfigService service = service();
                CyclicBarrier gate = new CyclicBarrier(threads);
                List<Future<AggregationAuthorization>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        gate.await(10, TimeUnit.SECONDS);
                        return service.putAggregationAuthorization(
                                REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);
                    }));
                }
                List<AggregationAuthorization> returned = new ArrayList<>();
                for (Future<AggregationAuthorization> future : futures) {
                    returned.add(future.get(10, TimeUnit.SECONDS));
                }
                List<AggregationAuthorization> stored = authorizations(service, REGION);
                assertEquals(1, stored.size());
                for (AggregationAuthorization each : returned) {
                    if (each != stored.getFirst()) {
                        mismatches++;
                    }
                }
            }
            assertEquals(0, mismatches,
                    "a concurrent Put returned an authorization the store does not hold");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** Delete takes the same lock, so it cannot interleave with the create half of a Put. */
    @Test
    void concurrentPutAndDeleteLeaveNoHalfState() throws Exception {
        AwsConfigService service = service();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                CountDownLatch start = new CountDownLatch(1);
                Future<?> put = pool.submit(() -> {
                    start.await();
                    return service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);
                });
                Future<?> delete = pool.submit(() -> {
                    start.await();
                    service.deleteAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1");
                    return null;
                });
                start.countDown();
                put.get(10, TimeUnit.SECONDS);
                delete.get(10, TimeUnit.SECONDS);

                // Either order is legal; what is not legal is an entry whose tags outlived a delete.
                List<AggregationAuthorization> after = authorizations(service, REGION);
                assertTrue(after.size() <= 1);
                if (after.isEmpty()) {
                    assertTrue(service.listTagsForResource(
                            AwsArnUtils.Arn.of("config", REGION, ACCOUNT,
                                    "aggregation-authorization/" + AUTHORIZED_ACCOUNT + "/eu-west-1").toString())
                            .isEmpty(), "delete left tags behind for a removed authorization");
                }
                service.deleteAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1");
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
