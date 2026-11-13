package io.floci.aws.services.configservice;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.configservice.model.ConfigRule;
import io.floci.aws.services.configservice.model.ConfigRuleSource;
import io.floci.aws.services.configservice.model.ConfigurationRecorder;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final List<Map<String, String>> TEAM_RED = List.of(Map.of("Key", "team", "Value", "red"));
    private static final String GHOST_RECORDER_ARN =
            "arn:aws:config:us-east-1:000000000000:configuration-recorder/default/ghost";

    private AwsConfigService service() {
        return new AwsConfigService(new RegionResolver(REGION, ACCOUNT), null);
    }

    private static void assertNotFound(Executable call) {
        AwsException ex = assertThrows(AwsException.class, call);
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    private static void assertNoSuchRecorder(Executable call) {
        AwsException ex = assertThrows(AwsException.class, call);
        assertEquals("NoSuchConfigurationRecorderException", ex.getErrorCode());
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
    void putConfigurationRecorderMintsAnArnAndAppliesRequestTagsAtCreationOnly() {
        AwsConfigService service = service();
        service.putConfigurationRecorder(REGION, recorder("default"), ENV_PROD);
        String arn = recorderArn(service);
        assertTrue(arn.startsWith("arn:aws:config:us-east-1:000000000000:configuration-recorder/default/"), arn);
        assertEquals(ENV_PROD, service.listTagsForResource(REGION, arn));

        service.putConfigurationRecorder(REGION, recorder("default"), List.of(Map.of("Key", "env", "Value", "dev")));

        assertEquals(arn, recorderArn(service));
        assertEquals(ENV_PROD, service.listTagsForResource(REGION, arn));
        service.tagResource(REGION, arn, TEAM_RED);
        service.untagResource(REGION, arn, List.of("env"));
        assertEquals(TEAM_RED, service.listTagsForResource(REGION, arn));
    }

    @Test
    void deletingARecorderDropsItsTagsAndARecreatedRecorderGetsANewArn() {
        AwsConfigService service = service();
        service.putConfigurationRecorder(REGION, recorder("default"), ENV_PROD);
        String oldArn = recorderArn(service);

        service.deleteConfigurationRecorder(REGION, "default");

        assertTrue(service.storedTags(oldArn).isEmpty(), "delete must drop the recorder's tags");
        assertAllThreeNotFound(service, REGION, oldArn);
        service.putConfigurationRecorder(REGION, recorder("default"), TEAM_RED);
        assertNotEquals(oldArn, recorderArn(service));
        assertEquals(TEAM_RED, service.listTagsForResource(REGION, recorderArn(service)));
    }

    @Test
    void aRecorderPutUnderAnotherNameReplacesTheOldOneAndItsTags() {
        AwsConfigService service = service();
        service.putConfigurationRecorder(REGION, recorder("default"), ENV_PROD);
        String oldArn = recorderArn(service);

        service.putConfigurationRecorder(REGION, recorder("renamed"), TEAM_RED);

        String newArn = recorderArn(service);
        assertTrue(newArn.contains(":configuration-recorder/renamed/"), newArn);
        assertEquals(TEAM_RED, service.listTagsForResource(REGION, newArn));
        assertAllThreeNotFound(service, REGION, oldArn);
        assertTrue(service.storedTags(oldArn).isEmpty(), "the replaced recorder's tags must go");
    }

    @Test
    void aRecorderSavedWithoutAnArnGetsOneOnItsNextPut() {
        AwsConfigService service = serviceWithARecorderSavedWithoutAnArn();
        assertNull(recorderArn(service));

        service.putConfigurationRecorder(REGION, recorder("default"), ENV_PROD);

        String arn = recorderArn(service);
        assertTrue(arn.startsWith("arn:aws:config:us-east-1:000000000000:configuration-recorder/default/"), arn);
        assertTrue(service.listTagsForResource(REGION, arn).isEmpty(), "the recorder already existed");
    }

    @Test
    void aRecorderSavedWithoutAnArnCanBeDeleted() {
        AwsConfigService service = serviceWithARecorderSavedWithoutAnArn();

        service.deleteConfigurationRecorder(REGION, "default");

        assertTrue(service.describeConfigurationRecorders(REGION, null, null).isEmpty());
    }

    @Test
    void bothDescribeCallsReturnAndFilterByTheRecorderArn() {
        AwsConfigService service = service();
        service.putConfigurationRecorder(REGION, recorder("default"), null);
        String arn = recorderArn(service);

        assertEquals(arn, service.describeConfigurationRecorderStatus(REGION, null, null).getFirst().arn());
        assertEquals(arn, service.describeConfigurationRecorders(REGION, null, arn).getFirst().arn());
        assertEquals(arn, service.describeConfigurationRecorderStatus(REGION, null, arn).getFirst().arn());
        assertEquals(arn, service.describeConfigurationRecorders(REGION, List.of("default"), arn).getFirst().arn());
        assertEquals(arn,
                service.describeConfigurationRecorderStatus(REGION, List.of("default"), arn).getFirst().arn());
    }

    @Test
    void anArnOrNameThatMissesTheRecorderIsNoSuchConfigurationRecorder() {
        AwsConfigService service = service();
        assertNoSuchRecorder(() -> service.describeConfigurationRecorders(REGION, null, GHOST_RECORDER_ARN));
        assertNoSuchRecorder(() -> service.describeConfigurationRecorderStatus(REGION, null, GHOST_RECORDER_ARN));

        service.putConfigurationRecorder(REGION, recorder("default"), null);
        String arn = recorderArn(service);

        assertNoSuchRecorder(() -> service.describeConfigurationRecorders(REGION, null, GHOST_RECORDER_ARN));
        assertNoSuchRecorder(() -> service.describeConfigurationRecorderStatus(REGION, null, GHOST_RECORDER_ARN));
        assertNoSuchRecorder(() -> service.describeConfigurationRecorders(REGION, List.of("other"), arn));
        assertNoSuchRecorder(() -> service.describeConfigurationRecorderStatus(REGION, List.of("other"), arn));
    }

    @Test
    void aRecorderSavedWithoutAnArnHasNoArnInItsStatusAndNoArnFindsIt() {
        AwsConfigService service = serviceWithARecorderSavedWithoutAnArn();

        assertNull(service.describeConfigurationRecorderStatus(REGION, List.of("default"), null).getFirst().arn());
        assertNoSuchRecorder(() -> service.describeConfigurationRecorders(REGION, null, GHOST_RECORDER_ARN));
        assertNoSuchRecorder(() -> service.describeConfigurationRecorderStatus(REGION, null, GHOST_RECORDER_ARN));
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
        assertOperationWaitsForParkedStore(tagStore,
                () -> service.tagResource(REGION, arn, ENV_PROD),
                () -> service.deleteConformancePack(REGION, "pack"));

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

    @Test
    void aRecorderPutRetriedAfterAFailedCreationTagWriteEndsWithTheTags() {
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

        assertThrows(IllegalStateException.class,
                () -> service.putConfigurationRecorder(REGION, recorder("default"), ENV_PROD));
        service.putConfigurationRecorder(REGION, recorder("default"), ENV_PROD);

        assertEquals(ENV_PROD, service.listTagsForResource(REGION, recorderArn(service)));
    }

    private static AwsConfigService serviceWithTagStore(StorageBackend<String, Map<String, String>> tagStore) {
        AwsConfigService service = new AwsConfigService(new RegionResolver(REGION, ACCOUNT),
                new InMemoryStorageFactory(ACCOUNT).withStore("config-tags.json", tagStore));
        service.initializeStorage();
        return service;
    }

    private static AwsConfigService serviceWithARecorderSavedWithoutAnArn() {
        AccountAwareStorageBackend<ConfigurationRecorder> recorders = AccountAwareStorageBackend.inMemory(ACCOUNT);
        recorders.put(REGION, recorder("default"));
        AwsConfigService service = new AwsConfigService(new RegionResolver(REGION, ACCOUNT),
                new InMemoryStorageFactory(ACCOUNT).withStore("config-recorders.json", recorders));
        service.initializeStorage();
        return service;
    }

    private static ConfigRule rule(String name) {
        return new ConfigRule(name, null, null, null, null,
                new ConfigRuleSource("AWS", "S3_BUCKET_VERSIONING_ENABLED", null, null),
                null, null, null, null, null);
    }

    private static ConfigurationRecorder recorder(String name) {
        return new ConfigurationRecorder(null, name, "arn:aws:iam::000000000000:role/config", null, null);
    }

    private static String recorderArn(AwsConfigService service) {
        return service.describeConfigurationRecorders(REGION, null, null).getFirst().arn();
    }
}
