package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleSource;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TagResource, UntagResource and ListTagsForResource all model ResourceNotFoundException
 * (config/2014-11-12). For the kinds Floci stores, an ARN that names no live resource in the
 * caller's account and Region must get it, rather than a 200 that re-creates an orphan tag entry.
 */
class AwsConfigServiceTaggingTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final List<Map<String, String>> ENV_PROD = List.of(Map.of("Key", "env", "Value", "prod"));

    private AwsConfigService service() {
        return new AwsConfigService(new RegionResolver(REGION, ACCOUNT), null);
    }

    private static void assertNotFound(Executable call) {
        AwsException ex = assertThrows(AwsException.class, call);
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    private static void assertAllThreeNotFound(AwsConfigService service, String region, String arn) {
        assertNotFound(() -> service.tagResource(region, arn, ENV_PROD));
        assertNotFound(() -> service.untagResource(region, arn, List.of("env")));
        assertNotFound(() -> service.listTagsForResource(region, arn));
    }

    @Test
    void aDeletedConformancePackIsNotFoundAndTaggingItLeavesNoOrphan() {
        AwsConfigService service = service();
        String arn = service.putConformancePack(REGION, "pack", "s3://bucket/t.yaml", null).conformancePackArn();
        service.deleteConformancePack(REGION, "pack");

        assertAllThreeNotFound(service, REGION, arn);
        assertTrue(service.storedTags(arn).isEmpty(), "a rejected TagResource must not store tags");
    }

    @Test
    void aConformancePackThatNeverExistedIsNotFound() {
        assertAllThreeNotFound(service(), REGION,
                "arn:aws:config:us-east-1:000000000000:conformance-pack/ghost/conformance-pack-abc123");
    }

    @Test
    void aRecreatedPackDoesNotAnswerForItsPredecessorsArn() {
        AwsConfigService service = service();
        String oldArn = service.putConformancePack(REGION, "pack", "s3://bucket/t.yaml", null).conformancePackArn();
        service.deleteConformancePack(REGION, "pack");
        service.putConformancePack(REGION, "pack", "s3://bucket/t.yaml", null);

        assertAllThreeNotFound(service, REGION, oldArn);
    }

    @Test
    void aLiveConformancePackIsStillTaggable() {
        AwsConfigService service = service();
        String arn = service.putConformancePack(REGION, "pack", "s3://bucket/t.yaml", null).conformancePackArn();

        service.tagResource(REGION, arn, List.of(Map.of("Key", "env", "Value", "prod"),
                Map.of("Key", "team", "Value", "sec")));
        service.untagResource(REGION, arn, List.of("team"));

        assertEquals(ENV_PROD, service.listTagsForResource(REGION, arn));
    }

    @Test
    void aPackArnFromAnotherRegionAccountOrPartitionIsNotFound() {
        AwsConfigService service = service();
        String arn = service.putConformancePack(REGION, "pack", "s3://bucket/t.yaml", null).conformancePackArn();
        service.putConformancePack("eu-west-1", "pack", "s3://bucket/t.yaml", null);

        assertAllThreeNotFound(service, "eu-west-1", arn);
        assertAllThreeNotFound(service, REGION, arn.replace(":" + ACCOUNT + ":", ":111122223333:"));
        assertAllThreeNotFound(service, REGION, arn.replace("arn:aws:", "arn:aws-cn:"));
    }

    @Test
    void aDeletedConfigRuleIsNotFoundAndALiveOneIsTaggable() {
        AwsConfigService service = service();
        ConfigRule live = service.putConfigRule(REGION, rule("live"));
        String goneArn = service.putConfigRule(REGION, rule("gone")).configRuleArn();
        service.deleteConfigRule(REGION, "gone");

        assertAllThreeNotFound(service, REGION, goneArn);
        service.tagResource(REGION, live.configRuleArn(), ENV_PROD);
        assertEquals(ENV_PROD, service.listTagsForResource(REGION, live.configRuleArn()));
    }

    @Test
    void putConfigRuleAppliesRequestTagsAtCreationOnly() {
        AwsConfigService service = service();
        String arn = service.putConfigRule(REGION, rule("tagged"), ENV_PROD).configRuleArn();
        assertEquals(ENV_PROD, service.listTagsForResource(REGION, arn));

        ConfigRule updated = service.putConfigRule(REGION, rule("tagged"),
                List.of(Map.of("Key", "env", "Value", "dev"), Map.of("Key", "team", "Value", "sec")));

        assertEquals(arn, updated.configRuleArn());
        assertEquals(ENV_PROD, service.listTagsForResource(REGION, arn));
    }

    @Test
    void deletingAConfigRuleDropsItsTagsAndARecreatedRuleStartsFresh() {
        AwsConfigService service = service();
        String oldArn = service.putConfigRule(REGION, rule("tagged"), ENV_PROD).configRuleArn();
        service.tagResource(REGION, oldArn, List.of(Map.of("Key", "team", "Value", "sec")));

        service.deleteConfigRule(REGION, "tagged");

        assertTrue(service.storedTags(oldArn).isEmpty(), "delete must drop the rule's tags");
        List<Map<String, String>> fresh = List.of(Map.of("Key", "env", "Value", "dev"));
        String newArn = service.putConfigRule(REGION, rule("tagged"), fresh).configRuleArn();
        assertEquals(fresh, service.listTagsForResource(REGION, newArn));
    }

    @Test
    void aDeletedAggregationAuthorizationIsNotFound() {
        AwsConfigService service = service();
        String arn = service.putAggregationAuthorization(REGION, "111122223333", "eu-west-1", ENV_PROD)
                .aggregationAuthorizationArn();
        assertEquals(ENV_PROD, service.listTagsForResource(REGION, arn));

        service.deleteAggregationAuthorization(REGION, "111122223333", "eu-west-1");

        assertAllThreeNotFound(service, REGION, arn);
    }

    @Test
    void anArnOfAKindFlociDoesNotModelIsPassedThrough() {
        AwsConfigService service = service();
        String arn = "arn:aws:config:us-east-1:000000000000:config-aggregator/config-aggregator-abc123";

        service.tagResource(REGION, arn, ENV_PROD);

        assertEquals(ENV_PROD, service.listTagsForResource(REGION, arn));
    }

    /**
     * Check-then-tag must not race a delete. Unlocked, a delete landing between TagResource's
     * existence check and its write removes the pack and its tags, then the write re-creates
     * the tag entry for a pack that is gone. The TagResource is parked in its write, inside the
     * section the pack lock guards, and the delete must be seen waiting on it.
     */
    @Test
    void aPackDeleteWaitsForATagWriteAlreadyInProgress() throws Exception {
        ParkingStore<Map<String, String>> tagStore = new ParkingStore<>();
        AwsConfigService service = serviceWithTagStore(tagStore);
        String arn = service.putConformancePack(REGION, "pack", "s3://bucket/t.yaml", null).conformancePackArn();

        tagStore.parkNextWrite();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> tag = pool.submit(() -> service.tagResource(REGION, arn, ENV_PROD));
            assertTrue(tagStore.awaitParked(), "the tag write must reach its persist");
            AtomicReference<Thread> deleteThread = new AtomicReference<>();
            Future<?> delete = pool.submit(() -> {
                deleteThread.set(Thread.currentThread());
                service.deleteConformancePack(REGION, "pack");
            });
            awaitBlockedOrDone(deleteThread, delete);
            assertFalse(delete.isDone(), "the delete ran while a tag write on the same pack was mid-flight");

            tagStore.release();
            tag.get(2, TimeUnit.SECONDS);
            delete.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError(e.getCause());
        } finally {
            tagStore.release();
            pool.shutdownNow();
        }

        assertTrue(service.storedTags(arn).isEmpty(), "a tag entry outlived the pack it was written for");
    }

    /**
     * Creation tags are skipped once the rule exists, so a tag write that fails after the rule
     * was stored would leave a retry nothing to restore them with. The retry must end tagged.
     */
    @Test
    void aRetryAfterAFailedCreationTagWriteEndsWithTheTags() {
        AtomicBoolean failNextWrite = new AtomicBoolean(true);
        AwsConfigService service = serviceWithTagStore(new InMemoryStorage<String, Map<String, String>>() {
            @Override
            public void put(String key, Map<String, String> value) {
                if (failNextWrite.getAndSet(false)) {
                    throw new IllegalStateException("tag store unavailable");
                }
                super.put(key, value);
            }
        });

        assertThrows(IllegalStateException.class, () -> service.putConfigRule(REGION, rule("retried"), ENV_PROD));
        String arn = service.putConfigRule(REGION, rule("retried"), ENV_PROD).configRuleArn();

        assertEquals(ENV_PROD, service.listTagsForResource(REGION, arn));
    }

    private static AwsConfigService serviceWithTagStore(StorageBackend<String, Map<String, String>> tagStore) {
        AwsConfigService service = new AwsConfigService(new RegionResolver(REGION, ACCOUNT),
                new InMemoryStorageFactory(ACCOUNT).withStore("config-tags.json", tagStore));
        service.initializeStorage();
        return service;
    }

    private static ConfigRule rule(String name) {
        return new ConfigRule(name, null, null, null, null,
                new ConfigRuleSource("AWS", "S3_BUCKET_VERSIONING_ENABLED", null, null),
                null, null, null, null, null);
    }
}
