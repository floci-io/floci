package io.github.hectorvent.floci.services.configservice;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleSource;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorder;
import io.github.hectorvent.floci.services.configservice.model.DeliveryChannel;
import io.github.hectorvent.floci.services.configservice.model.RecordingGroup;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies AWS Config durable resources survive a restart. Two service instances share the same
 * {@link StorageFactory} backends; the second simulates a process restart reloading from disk.
 */
class AwsConfigServicePersistenceTest {

    private static final String REGION = "us-east-1";

    @Test
    void durableResourcesAndTagsSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        AwsConfigService first = serviceWithStorage(storage);
        ConfigRule rule = first.putConfigRule(REGION, "s3-public-read",
                new ConfigRuleSource("AWS", "S3_BUCKET_PUBLIC_READ_PROHIBITED"));
        first.putConformancePack(REGION, "ops-pack", "s3://bucket/template.yaml", null);
        first.putConfigurationRecorder(REGION, new ConfigurationRecorder("default",
                "arn:aws:iam::000000000000:role/config", new RecordingGroup(true, false, null)));
        first.putDeliveryChannel(REGION,
                new DeliveryChannel("default", "config-bucket", null, null, null, null));
        first.tagResource(rule.configRuleArn(), List.of(Map.of("Key", "env", "Value", "prod")));

        AwsConfigService reloaded = serviceWithStorage(storage);

        assertEquals(List.of("s3-public-read"),
                reloaded.describeConfigRules(REGION, null).stream().map(ConfigRule::configRuleName).toList());
        assertEquals(List.of("ops-pack"),
                reloaded.describeConformancePacks(REGION, null).stream()
                        .map(p -> p.conformancePackName()).toList());
        assertEquals("default",
                reloaded.describeConfigurationRecorders(REGION, null).getFirst().name());
        assertEquals("config-bucket",
                reloaded.describeDeliveryChannels(REGION, null).getFirst().s3BucketName());
        assertEquals("prod", reloaded.listTagsForResource(rule.configRuleArn()).getFirst().get("Value"));
    }

    @Test
    void deleteAndUntagArePersistedAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        AwsConfigService first = serviceWithStorage(storage);
        ConfigRule rule = first.putConfigRule(REGION, "keep",
                new ConfigRuleSource("AWS", "REQUIRED_TAGS"));
        first.putConfigRule(REGION, "drop", new ConfigRuleSource("AWS", "REQUIRED_TAGS"));
        first.deleteConfigRule(REGION, "drop");
        first.tagResource(rule.configRuleArn(),
                List.of(Map.of("Key", "env", "Value", "prod"), Map.of("Key", "team", "Value", "sec")));
        first.untagResource(rule.configRuleArn(), List.of("team"));

        AwsConfigService reloaded = serviceWithStorage(storage);

        assertEquals(List.of("keep"),
                reloaded.describeConfigRules(REGION, null).stream().map(ConfigRule::configRuleName).toList());
        Map<String, String> tags = reloaded.listTagsForResource(rule.configRuleArn()).stream()
                .collect(java.util.stream.Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
        assertEquals("prod", tags.get("env"));
        assertTrue(!tags.containsKey("team"), "untagged key must not reappear after restart");
    }

    private static AwsConfigService serviceWithStorage(StorageFactory storage) {
        AwsConfigService service = new AwsConfigService(new RegionResolver(REGION, "000000000000"), storage);
        service.initializeStorage();
        return service;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> StorageBackend<String, V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}
