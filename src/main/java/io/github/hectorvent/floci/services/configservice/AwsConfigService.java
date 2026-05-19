package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleEvaluationStatus;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleSource;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorder;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorderStatus;
import io.github.hectorvent.floci.services.configservice.model.ConformancePack;
import io.github.hectorvent.floci.services.configservice.model.ConformancePackStatusDetail;
import io.github.hectorvent.floci.services.configservice.model.DeliveryChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AwsConfigService {

    private final RegionResolver regionResolver;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConfigRule>> configRules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConformancePack>> conformancePacks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ConfigurationRecorder> configurationRecorders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> recorderRunning = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recorderLastStartTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recorderLastStopTime = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, DeliveryChannel> deliveryChannels = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Map<String, String>> tags = new ConcurrentHashMap<>();

    @Inject
    public AwsConfigService(RegionResolver regionResolver) {
        this.regionResolver = regionResolver;
    }

    // --- Config Rules ---

    public ConfigRule putConfigRule(String region, String ruleName, ConfigRuleSource source) {
        ConcurrentHashMap<String, ConfigRule> store = rulesFor(region);
        ConfigRule existing = store.get(ruleName);
        if (existing != null) {
            ConfigRule updated = new ConfigRule(existing.configRuleName(), existing.configRuleArn(),
                    existing.configRuleId(), existing.configRuleState(), source);
            store.put(ruleName, updated);
            return updated;
        }
        String ruleId = "config-rule-" + shortId();
        String ruleArn = AwsArnUtils.Arn.of("config", region, regionResolver.getAccountId(),
                "config-rule/" + ruleId).toString();
        ConfigRule rule = new ConfigRule(ruleName, ruleArn, ruleId, "ACTIVE", source);
        store.put(ruleName, rule);
        return rule;
    }

    public void deleteConfigRule(String region, String ruleName) {
        ConcurrentHashMap<String, ConfigRule> store = rulesFor(region);
        if (store.remove(ruleName) == null) {
            throw new AwsException("NoSuchConfigRuleException",
                    "The ConfigRule provided in the request is invalid. " +
                            "Please check the configRule name.", 400);
        }
    }

    public List<ConfigRule> describeConfigRules(String region, List<String> ruleNames) {
        ConcurrentHashMap<String, ConfigRule> store = rulesFor(region);
        if (ruleNames == null || ruleNames.isEmpty()) {
            return new ArrayList<>(store.values());
        }
        List<ConfigRule> result = new ArrayList<>();
        for (String name : ruleNames) {
            ConfigRule rule = store.get(name);
            if (rule != null) {
                result.add(rule);
            }
        }
        return result;
    }

    public List<ConfigRuleEvaluationStatus> describeConfigRuleEvaluationStatus(String region, List<String> ruleNames) {
        List<ConfigRule> rules = describeConfigRules(region, ruleNames);
        List<ConfigRuleEvaluationStatus> result = new ArrayList<>();
        for (ConfigRule rule : rules) {
            result.add(new ConfigRuleEvaluationStatus(
                    rule.configRuleName(),
                    rule.configRuleArn(),
                    rule.configRuleId(),
                    true));
        }
        return result;
    }

    public void startConfigRulesEvaluation(String region, List<String> ruleNames) {
        ConcurrentHashMap<String, ConfigRule> store = rulesFor(region);
        for (String name : ruleNames) {
            if (!store.containsKey(name)) {
                throw new AwsException("NoSuchConfigRuleException",
                        "The ConfigRule provided in the request is invalid. " +
                                "Please check the configRule name.", 400);
            }
        }
    }

    // --- Configuration Recorder ---

    public void putConfigurationRecorder(String region, ConfigurationRecorder recorder) {
        String name = (recorder.name() == null || recorder.name().isEmpty()) ? "default" : recorder.name();
        ConfigurationRecorder stored = new ConfigurationRecorder(name, recorder.roleARN(), recorder.recordingGroup());
        configurationRecorders.put(region, stored);
    }

    public List<ConfigurationRecorder> describeConfigurationRecorders(String region, List<String> names) {
        ConfigurationRecorder recorder = configurationRecorders.get(region);
        if (recorder == null) {
            if (names != null && !names.isEmpty()) {
                throw new AwsException("NoSuchConfigurationRecorderException",
                        "Cannot find configuration recorder with the specified name.", 400);
            }
            return Collections.emptyList();
        }
        if (names != null && !names.isEmpty()) {
            for (String name : names) {
                if (!name.equals(recorder.name())) {
                    throw new AwsException("NoSuchConfigurationRecorderException",
                            "Cannot find configuration recorder with the specified name.", 400);
                }
            }
        }
        return List.of(recorder);
    }

    public void startConfigurationRecorder(String region, String name) {
        ConfigurationRecorder recorder = configurationRecorders.get(region);
        if (recorder == null || !recorder.name().equals(name)) {
            throw new AwsException("NoSuchConfigurationRecorderException",
                    "Cannot find configuration recorder with the specified name.", 400);
        }
        recorderRunning.put(region, true);
        recorderLastStartTime.put(region, System.currentTimeMillis() / 1000);
    }

    public void stopConfigurationRecorder(String region, String name) {
        ConfigurationRecorder recorder = configurationRecorders.get(region);
        if (recorder == null || !recorder.name().equals(name)) {
            throw new AwsException("NoSuchConfigurationRecorderException",
                    "Cannot find configuration recorder with the specified name.", 400);
        }
        recorderRunning.put(region, false);
        recorderLastStopTime.put(region, System.currentTimeMillis() / 1000);
    }

    public List<ConfigurationRecorderStatus> describeConfigurationRecorderStatus(String region, List<String> names) {
        ConfigurationRecorder recorder = configurationRecorders.get(region);
        if (recorder == null) {
            if (names != null && !names.isEmpty()) {
                throw new AwsException("NoSuchConfigurationRecorderException",
                        "Cannot find configuration recorder with the specified name.", 400);
            }
            return Collections.emptyList();
        }
        if (names != null && !names.isEmpty()) {
            for (String name : names) {
                if (!name.equals(recorder.name())) {
                    throw new AwsException("NoSuchConfigurationRecorderException",
                            "Cannot find configuration recorder with the specified name.", 400);
                }
            }
        }
        ConfigurationRecorderStatus status = new ConfigurationRecorderStatus(
                recorder.name(),
                recorderRunning.getOrDefault(region, false),
                recorderLastStartTime.containsKey(region) ? "SUCCESS" : "Pending",
                recorderLastStartTime.get(region),
                recorderLastStopTime.get(region));
        return List.of(status);
    }

    // --- Delivery Channel ---

    public void putDeliveryChannel(String region, DeliveryChannel channel) {
        if (!configurationRecorders.containsKey(region)) {
            throw new AwsException("NoAvailableConfigurationRecorderException",
                    "There are no configuration recorders available to provide the resource count.", 400);
        }
        String name = (channel.name() == null || channel.name().isEmpty()) ? "default" : channel.name();
        DeliveryChannel stored = new DeliveryChannel(name, channel.s3BucketName(), channel.s3KeyPrefix(),
                channel.s3KmsKeyArn(), channel.snsTopicARN(), channel.configSnapshotDeliveryProperties());
        deliveryChannels.put(region, stored);
    }

    public List<DeliveryChannel> describeDeliveryChannels(String region, List<String> names) {
        DeliveryChannel channel = deliveryChannels.get(region);
        if (channel == null) {
            if (names != null && !names.isEmpty()) {
                throw new AwsException("NoSuchDeliveryChannelException",
                        "Cannot find delivery channel with the specified name.", 400);
            }
            return Collections.emptyList();
        }
        if (names != null && !names.isEmpty()) {
            for (String name : names) {
                if (!name.equals(channel.name())) {
                    throw new AwsException("NoSuchDeliveryChannelException",
                            "Cannot find delivery channel with the specified name.", 400);
                }
            }
        }
        return List.of(channel);
    }

    // --- Conformance Packs ---

    public ConformancePack putConformancePack(String region, String packName,
                                              String templateS3Uri, String templateBody) {
        ConcurrentHashMap<String, ConformancePack> store = packsFor(region);
        ConformancePack existing = store.get(packName);
        if (existing != null) {
            ConformancePack updated = new ConformancePack(existing.conformancePackName(), existing.conformancePackArn(),
                    existing.conformancePackId(), templateS3Uri, templateBody);
            store.put(packName, updated);
            return updated;
        }
        String packId = "conformance-pack-" + shortId();
        String packArn = AwsArnUtils.Arn.of("config", region, regionResolver.getAccountId(),
                "conformance-pack/" + packName + "/" + packId).toString();
        ConformancePack pack = new ConformancePack(packName, packArn, packId, templateS3Uri, templateBody);
        store.put(packName, pack);
        return pack;
    }

    public void deleteConformancePack(String region, String packName) {
        ConcurrentHashMap<String, ConformancePack> store = packsFor(region);
        if (store.remove(packName) == null) {
            throw new AwsException("NoSuchConformancePackException",
                    "Conformance pack '" + packName + "' does not exist.", 400);
        }
    }

    public List<ConformancePack> describeConformancePacks(String region, List<String> names) {
        ConcurrentHashMap<String, ConformancePack> store = packsFor(region);
        if (names == null || names.isEmpty()) {
            return new ArrayList<>(store.values());
        }
        List<ConformancePack> result = new ArrayList<>();
        for (String name : names) {
            ConformancePack pack = store.get(name);
            if (pack == null) {
                throw new AwsException("NoSuchConformancePackException",
                        "Conformance pack '" + name + "' does not exist.", 400);
            }
            result.add(pack);
        }
        return result;
    }

    public List<ConformancePackStatusDetail> describeConformancePackStatus(String region, List<String> names) {
        List<ConformancePack> packs = describeConformancePacks(region, names);
        List<ConformancePackStatusDetail> result = new ArrayList<>();
        for (ConformancePack pack : packs) {
            result.add(new ConformancePackStatusDetail(
                    pack.conformancePackName(),
                    pack.conformancePackId(),
                    pack.conformancePackArn(),
                    "CREATE_SUCCESSFUL",
                    System.currentTimeMillis() / 1000));
        }
        return result;
    }

    // --- Tagging ---

    public void tagResource(String arn, List<Map<String, String>> tagList) {
        Map<String, String> tagMap = tags.computeIfAbsent(arn, k -> new ConcurrentHashMap<>());
        for (Map<String, String> t : tagList) {
            tagMap.put(t.get("Key"), t.get("Value"));
        }
    }

    public void untagResource(String arn, List<String> tagKeys) {
        Map<String, String> tagMap = tags.get(arn);
        if (tagMap != null) {
            tagKeys.forEach(tagMap::remove);
        }
    }

    public List<Map<String, String>> listTagsForResource(String arn) {
        Map<String, String> tagMap = tags.getOrDefault(arn, Map.of());
        return tagMap.entrySet().stream()
                .map(e -> Map.of("Key", e.getKey(), "Value", e.getValue()))
                .collect(Collectors.toList());
    }

    // --- Helpers ---

    private ConcurrentHashMap<String, ConfigRule> rulesFor(String region) {
        return configRules.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private ConcurrentHashMap<String, ConformancePack> packsFor(String region) {
        return conformancePacks.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
