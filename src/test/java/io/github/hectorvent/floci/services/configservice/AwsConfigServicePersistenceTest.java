package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.configservice.model.ConfigEvaluation;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleSource;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorder;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorderStatus;
import io.github.hectorvent.floci.services.configservice.model.DeliveryChannel;
import io.github.hectorvent.floci.services.configservice.model.RecordingGroup;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies AWS Config durable resources survive a restart. Each service instance gets a fresh
 * {@link StorageFactory} in {@code persistent} mode over one temporary directory, so a later
 * instance sees only what the earlier one wrote to disk and it reads back.
 */
class AwsConfigServicePersistenceTest {

    private static final String REGION = "us-east-1";

    @TempDir
    Path storageDir;

    @Test
    void durableResourcesAndTagsSurviveRestart() {
        AwsConfigService first = restartedService();
        ConfigRule rule = first.putConfigRule(REGION,
                rule("s3-public-read", "AWS", "S3_BUCKET_PUBLIC_READ_PROHIBITED"));
        first.putConformancePack(REGION, "ops-pack", "s3://bucket/template.yaml", null);
        first.putConfigurationRecorder(REGION, new ConfigurationRecorder("default",
                "arn:aws:iam::000000000000:role/config", new RecordingGroup(true, false, null)));
        first.putDeliveryChannel(REGION,
                new DeliveryChannel("default", "config-bucket", null, null, null, null));
        first.tagResource(REGION, rule.configRuleArn(), List.of(Map.of("Key", "env", "Value", "prod")));

        AwsConfigService reloaded = restartedService();

        assertEquals(List.of("s3-public-read"),
                reloaded.describeConfigRules(REGION, null).stream().map(ConfigRule::configRuleName).toList());
        assertEquals(List.of("ops-pack"),
                reloaded.describeConformancePacks(REGION, null).stream()
                        .map(p -> p.conformancePackName()).toList());
        assertEquals("default",
                reloaded.describeConfigurationRecorders(REGION, null).getFirst().name());
        assertEquals("config-bucket",
                reloaded.describeDeliveryChannels(REGION, null).getFirst().s3BucketName());
        assertEquals("prod", reloaded.listTagsForResource(REGION, rule.configRuleArn()).getFirst().get("Value"));
    }

    @Test
    void deleteAndUntagArePersistedAfterRestart() {
        AwsConfigService first = restartedService();
        ConfigRule rule = first.putConfigRule(REGION, rule("keep", "AWS", "REQUIRED_TAGS"));
        first.putConfigRule(REGION, rule("drop", "AWS", "REQUIRED_TAGS"));
        first.deleteConfigRule(REGION, "drop");
        first.tagResource(REGION, rule.configRuleArn(),
                List.of(Map.of("Key", "env", "Value", "prod"), Map.of("Key", "team", "Value", "sec")));
        first.untagResource(REGION, rule.configRuleArn(), List.of("team"));

        AwsConfigService reloaded = restartedService();

        assertEquals(List.of("keep"),
                reloaded.describeConfigRules(REGION, null).stream().map(ConfigRule::configRuleName).toList());
        Map<String, String> tags = reloaded.listTagsForResource(REGION, rule.configRuleArn()).stream()
                .collect(Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
        assertEquals("prod", tags.get("env"));
        assertTrue(!tags.containsKey("team"), "untagged key must not reappear after restart");
    }

    @Test
    void evaluationsSurviveRestart() {
        AwsConfigService first = restartedService();
        first.putConfigRule(REGION, rule("bucket-policy", "CUSTOM_LAMBDA",
                "arn:aws:lambda:us-east-1:000000000000:function:check"));
        first.putEvaluations(REGION, "bucket-policy",
                List.of(evaluation("AWS::S3::Bucket", "bucket-1", "NON_COMPLIANT")), false);

        AwsConfigService reloaded = restartedService();

        assertEquals("NON_COMPLIANT", reloaded.complianceForRule(REGION, "bucket-policy").complianceType());
        assertEquals(1, reloaded.getComplianceDetailsByConfigRule(REGION, "bucket-policy",
                null, null, null).items().size());
    }

    @Test
    void deleteConfigRuleCascadesEvaluationsAcrossRestart() {
        AwsConfigService first = restartedService();
        first.putConfigRule(REGION, rule("kept-rule", "CUSTOM_LAMBDA",
                "arn:aws:lambda:us-east-1:000000000000:function:check"));
        first.putConfigRule(REGION, rule("dropped-rule", "CUSTOM_LAMBDA",
                "arn:aws:lambda:us-east-1:000000000000:function:check"));
        first.putEvaluations(REGION, "kept-rule",
                List.of(evaluation("AWS::S3::Bucket", "bucket-1", "COMPLIANT")), false);
        first.putEvaluations(REGION, "dropped-rule",
                List.of(evaluation("AWS::S3::Bucket", "bucket-1", "NON_COMPLIANT")), false);
        first.deleteConfigRule(REGION, "dropped-rule");

        AwsConfigService reloaded = restartedService();

        assertEquals("COMPLIANT", reloaded.complianceForRule(REGION, "kept-rule").complianceType());
        assertEquals("INSUFFICIENT_DATA", reloaded.complianceForRule(REGION, "dropped-rule").complianceType());
    }

    @Test
    void retentionConfigurationSurvivesRestart() {
        AwsConfigService first = restartedService();
        first.putRetentionConfiguration(REGION, 90);

        AwsConfigService reloaded = restartedService();

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

    @Test
    void aStartedRecorderIsStillRecordingAfterRestart() {
        AwsConfigService first = restartedService();
        first.putConfigurationRecorder(REGION, new ConfigurationRecorder("default",
                "arn:aws:iam::000000000000:role/config", new RecordingGroup(true, false, null)));
        first.putDeliveryChannel(REGION,
                new DeliveryChannel("default", "config-bucket", null, null, null, null));
        first.startConfigurationRecorder(REGION, "default");
        Long startedAt = first.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStartTime();
        assertNotNull(startedAt);

        AwsConfigService reloaded = restartedService();

        ConfigurationRecorderStatus afterStart =
                reloaded.describeConfigurationRecorderStatus(REGION, null).getFirst();
        assertTrue(afterStart.recording());
        assertEquals(startedAt, afterStart.lastStartTime());

        reloaded.stopConfigurationRecorder(REGION, "default");
        Long stoppedAt = reloaded.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStopTime();
        assertNotNull(stoppedAt);

        ConfigurationRecorderStatus afterStop =
                restartedService().describeConfigurationRecorderStatus(REGION, null).getFirst();
        assertFalse(afterStop.recording());
        assertEquals(startedAt, afterStop.lastStartTime());
        assertEquals(stoppedAt, afterStop.lastStopTime());
    }

    @Test
    void aDeletedRecorderDoesNotComeBackRecordingAfterRestart() {
        AwsConfigService first = restartedService();
        ConfigurationRecorder recorder = new ConfigurationRecorder("default",
                "arn:aws:iam::000000000000:role/config", new RecordingGroup(true, false, null));
        first.putConfigurationRecorder(REGION, recorder);
        first.putDeliveryChannel(REGION,
                new DeliveryChannel("default", "config-bucket", null, null, null, null));
        first.startConfigurationRecorder(REGION, "default");
        first.deleteConfigurationRecorder(REGION, "default");

        AwsConfigService reloaded = restartedService();
        reloaded.putConfigurationRecorder(REGION, recorder);

        ConfigurationRecorderStatus status =
                reloaded.describeConfigurationRecorderStatus(REGION, null).getFirst();
        assertFalse(status.recording());
        assertNull(status.lastStartTime());
    }

    /** A service over a fresh factory and fresh backends that load whatever is on disk. */
    private AwsConfigService restartedService() {
        AwsConfigService service = new AwsConfigService(new RegionResolver(REGION, "000000000000"),
                StorageFactories.open(storageDir, "persistent", "000000000000"));
        service.initializeStorage();
        return service;
    }
}
