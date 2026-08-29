package io.github.hectorvent.floci.services.configservice;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.configservice.model.ConfigEvaluation;
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
        ConfigRule rule = first.putConfigRule(REGION,
                rule("s3-public-read", "AWS", "S3_BUCKET_PUBLIC_READ_PROHIBITED"));
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
        ConfigRule rule = first.putConfigRule(REGION, rule("keep", "AWS", "REQUIRED_TAGS"));
        first.putConfigRule(REGION, rule("drop", "AWS", "REQUIRED_TAGS"));
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

    @Test
    void evaluationsSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        AwsConfigService first = serviceWithStorage(storage);
        first.putConfigRule(REGION, rule("bucket-policy", "CUSTOM_LAMBDA",
                "arn:aws:lambda:us-east-1:000000000000:function:check"));
        first.putEvaluations(REGION, "bucket-policy",
                List.of(evaluation("AWS::S3::Bucket", "bucket-1", "NON_COMPLIANT")), false);

        AwsConfigService reloaded = serviceWithStorage(storage);

        assertEquals("NON_COMPLIANT", reloaded.complianceForRule(REGION, "bucket-policy").complianceType());
        assertEquals(1, reloaded.getComplianceDetailsByConfigRule(REGION, "bucket-policy",
                null, null, null).items().size());
    }

    @Test
    void deleteConfigRuleCascadesEvaluationsAcrossRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        AwsConfigService first = serviceWithStorage(storage);
        first.putConfigRule(REGION, rule("kept-rule", "CUSTOM_LAMBDA",
                "arn:aws:lambda:us-east-1:000000000000:function:check"));
        first.putConfigRule(REGION, rule("dropped-rule", "CUSTOM_LAMBDA",
                "arn:aws:lambda:us-east-1:000000000000:function:check"));
        first.putEvaluations(REGION, "kept-rule",
                List.of(evaluation("AWS::S3::Bucket", "bucket-1", "COMPLIANT")), false);
        first.putEvaluations(REGION, "dropped-rule",
                List.of(evaluation("AWS::S3::Bucket", "bucket-1", "NON_COMPLIANT")), false);
        first.deleteConfigRule(REGION, "dropped-rule");

        AwsConfigService reloaded = serviceWithStorage(storage);

        assertEquals("COMPLIANT", reloaded.complianceForRule(REGION, "kept-rule").complianceType());
        assertEquals("INSUFFICIENT_DATA", reloaded.complianceForRule(REGION, "dropped-rule").complianceType());
    }

    @Test
    void retentionConfigurationSurvivesRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        AwsConfigService first = serviceWithStorage(storage);
        first.putRetentionConfiguration(REGION, 90);

        AwsConfigService reloaded = serviceWithStorage(storage);

        assertEquals(90, reloaded.describeRetentionConfigurations(REGION, null)
                .getFirst().retentionPeriodInDays());
    }

    private static ConfigRule rule(String name, String owner, String sourceIdentifier) {
        return new ConfigRule(name, null, null, null, null,
                new ConfigRuleSource(owner, sourceIdentifier, null, null), null, null, null, null, null);
    }

    private static ConfigEvaluation evaluation(String resourceType, String resourceId, String complianceType) {
        return new ConfigEvaluation(resourceType, resourceId, complianceType, null, 1700000000.0, null, null);
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
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
