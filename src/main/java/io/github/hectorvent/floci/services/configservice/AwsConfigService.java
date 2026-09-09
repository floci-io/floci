package io.github.hectorvent.floci.services.configservice;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.configservice.model.Compliance;
import io.github.hectorvent.floci.services.configservice.model.ComplianceByConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ComplianceByResource;
import io.github.hectorvent.floci.services.configservice.model.ComplianceContributorCount;
import io.github.hectorvent.floci.services.configservice.model.ComplianceSummary;
import io.github.hectorvent.floci.services.configservice.model.ComplianceSummaryByResourceType;
import io.github.hectorvent.floci.services.configservice.model.ConfigEvaluation;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleEvaluationStatus;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorder;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorderStatus;
import io.github.hectorvent.floci.services.configservice.model.ConformancePack;
import io.github.hectorvent.floci.services.configservice.model.ConformancePackStatusDetail;
import io.github.hectorvent.floci.services.configservice.model.DeliveryChannel;
import io.github.hectorvent.floci.services.configservice.model.EvaluationModeConfiguration;
import io.github.hectorvent.floci.services.configservice.model.EvaluationResult;
import io.github.hectorvent.floci.services.configservice.model.EvaluationResultIdentifier;
import io.github.hectorvent.floci.services.configservice.model.EvaluationResultQualifier;
import io.github.hectorvent.floci.services.configservice.model.RetentionConfiguration;
import io.github.hectorvent.floci.services.configservice.model.SourceDetail;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AwsConfigService {

    private static final Set<String> VALID_COMPLIANCE_TYPES =
            Set.of("COMPLIANT", "NON_COMPLIANT", "NOT_APPLICABLE", "INSUFFICIENT_DATA");
    private static final String NO_SUCH_CONFIG_RULE_MESSAGE =
            "The ConfigRule provided in the request is invalid. Please check the configRule name.";
    private static final String INVALID_NEXT_TOKEN_MESSAGE =
            "The specified next token is not valid. Specify the nextToken string that was returned "
                    + "in the previous response to get the next page of results.";
    /** Enum shapes from the Config model (config/2014-11-12/service-2.json). */
    private static final Set<String> VALID_RULE_OWNERS =
            Set.of("CUSTOM_LAMBDA", "AWS", "CUSTOM_POLICY");
    private static final Set<String> VALID_EXECUTION_FREQUENCIES =
            Set.of("One_Hour", "Three_Hours", "Six_Hours", "Twelve_Hours", "TwentyFour_Hours");
    private static final Set<String> VALID_RULE_STATES =
            Set.of("ACTIVE", "DELETING", "DELETING_RESULTS", "EVALUATING");
    private static final Set<String> VALID_EVALUATION_MODES = Set.of("DETECTIVE", "PROACTIVE");
    private static final Set<String> VALID_EVENT_SOURCES = Set.of("aws.config");
    private static final Set<String> VALID_MESSAGE_TYPES = Set.of(
            "ConfigurationItemChangeNotification", "ConfigurationSnapshotDeliveryCompleted",
            "ScheduledNotification", "OversizedConfigurationItemChangeNotification");
    /** {@code ComplianceTypes} is a list of ComplianceType with {@code max: 3}. */
    private static final int MAX_COMPLIANCE_TYPE_FILTERS = 3;
    /** {@code Scope.ComplianceResourceTypes} is {@code max: 100}. */
    private static final int MAX_COMPLIANCE_RESOURCE_TYPES = 100;
    /** {@code DescribeConfigRulesRequest.ConfigRuleNames} is {@code max: 25}. */
    private static final int MAX_DESCRIBE_CONFIG_RULE_NAMES = 25;
    private static final int RULE_CONTRIBUTOR_CAP = 25;
    private static final int RESOURCE_CONTRIBUTOR_CAP = 100;

    private final RegionResolver regionResolver;
    private final StorageFactory storageFactory;

    // region -> ruleName -> rule (nested)
    private Map<String, Map<String, ConfigRule>> configRules = new ConcurrentHashMap<>();
    // region -> packName -> pack (nested)
    private Map<String, Map<String, ConformancePack>> conformancePacks = new ConcurrentHashMap<>();
    // region -> ruleName -> resourceKey("Type|Id") -> evaluation (doubly nested)
    private Map<String, Map<String, Map<String, ConfigEvaluation>>> evaluations = new ConcurrentHashMap<>();

    // region -> recorder / channel / retention (flat)
    private Map<String, ConfigurationRecorder> configurationRecorders = new ConcurrentHashMap<>();
    private Map<String, DeliveryChannel> deliveryChannels = new ConcurrentHashMap<>();
    private Map<String, RetentionConfiguration> retentionConfigurations = new ConcurrentHashMap<>();

    // recorder run-state is transient runtime state (not persisted)
    private final ConcurrentHashMap<String, Boolean> recorderRunning = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recorderLastStartTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recorderLastStopTime = new ConcurrentHashMap<>();

    // per-rule evaluation timing is transient runtime state, keyed region + "|" + ruleName
    private final ConcurrentHashMap<String, Long> ruleFirstActivatedTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ruleLastInvokedTime = new ConcurrentHashMap<>();

    // guards the check-then-act between a rule's existence and its evaluations, keyed
    // region + "|" + ruleName, so DeleteConfigRule can't race PutEvaluations/PutExternalEvaluation
    // into recreating a deleted rule's evaluation bucket for a later same-named rule to inherit
    private final ConcurrentHashMap<String, Object> ruleLocks = new ConcurrentHashMap<>();

    // resourceArn -> {tagKey -> tagValue} (flat outer, mutable inner)
    private Map<String, Map<String, String>> tags = new ConcurrentHashMap<>();

    @Inject
    public AwsConfigService(RegionResolver regionResolver, StorageFactory storageFactory) {
        this.regionResolver = regionResolver;
        this.storageFactory = storageFactory;
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.configRules = storageBacked("config-rules.json",
                new TypeReference<Map<String, Map<String, ConfigRule>>>() {});
        this.conformancePacks = storageBacked("config-conformance-packs.json",
                new TypeReference<Map<String, Map<String, ConformancePack>>>() {});
        this.evaluations = storageBacked("config-evaluations.json",
                new TypeReference<Map<String, Map<String, Map<String, ConfigEvaluation>>>>() {});
        this.configurationRecorders = storageBacked("config-recorders.json",
                new TypeReference<Map<String, ConfigurationRecorder>>() {});
        this.deliveryChannels = storageBacked("config-delivery-channels.json",
                new TypeReference<Map<String, DeliveryChannel>>() {});
        this.retentionConfigurations = storageBacked("config-retention.json",
                new TypeReference<Map<String, RetentionConfiguration>>() {});
        this.tags = storageBacked("config-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        normalizeRegionMaps(configRules);
        normalizeRegionMaps(conformancePacks);
        normalizeRegionMaps(tags);
        normalizeEvaluationMaps();
    }

    private <V> Map<String, V> storageBacked(String fileName, TypeReference<Map<String, V>> typeReference) {
        return new StorageBackedMap<>(storageFactory.create("config", fileName, typeReference));
    }

    /** After load, re-wrap persisted inner maps as {@link ConcurrentHashMap} (Jackson deserializes
     *  them as plain maps) so per-key mutation stays thread-safe. */
    private <V> void normalizeRegionMaps(Map<String, Map<String, V>> resources) {
        for (Map.Entry<String, Map<String, V>> entry : new ArrayList<>(resources.entrySet())) {
            if (!(entry.getValue() instanceof ConcurrentHashMap)) {
                resources.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
        }
    }

    /** Same as {@link #normalizeRegionMaps} but for the doubly nested evaluations map. */
    private void normalizeEvaluationMaps() {
        for (Map.Entry<String, Map<String, Map<String, ConfigEvaluation>>> regionEntry
                : new ArrayList<>(evaluations.entrySet())) {
            Map<String, Map<String, ConfigEvaluation>> ruleMaps = new ConcurrentHashMap<>();
            for (Map.Entry<String, Map<String, ConfigEvaluation>> ruleEntry : regionEntry.getValue().entrySet()) {
                ruleMaps.put(ruleEntry.getKey(), new ConcurrentHashMap<>(ruleEntry.getValue()));
            }
            evaluations.put(regionEntry.getKey(), ruleMaps);
        }
    }

    /** {@link StorageBackedMap} only flushes on a top-level put, so an in-place mutation of an
     *  inner map must be written back by re-putting the outer entry. */
    private <V> void persistRegion(Map<String, Map<String, V>> resources, String region) {
        Map<String, V> regionResources = resources.get(region);
        if (regionResources != null) {
            resources.put(region, regionResources);
        }
    }

    // --- Config Rules ---

    public ConfigRule putConfigRule(String region, ConfigRule requested) {
        if (requested == null || isBlank(requested.configRuleName())) {
            throw new AwsException("InvalidParameterValueException",
                    "ConfigRuleName must be specified.", 400);
        }
        if (requested.source() == null || isBlank(requested.source().owner())) {
            throw new AwsException("InvalidParameterValueException",
                    "Source with Owner must be specified.", 400);
        }
        // The whole modeled ConfigRule is stored and echoed back, so every enum-typed member
        // it keeps is checked here rather than accepted verbatim.
        requireEnum(requested.source().owner(), VALID_RULE_OWNERS, "Source.Owner");
        requireEnum(requested.maximumExecutionFrequency(), VALID_EXECUTION_FREQUENCIES,
                "MaximumExecutionFrequency");
        requireEnum(requested.configRuleState(), VALID_RULE_STATES, "ConfigRuleState");
        if (requested.source().sourceDetails() != null) {
            for (SourceDetail detail : requested.source().sourceDetails()) {
                if (detail == null) {
                    continue;
                }
                requireEnum(detail.eventSource(), VALID_EVENT_SOURCES, "SourceDetails.EventSource");
                requireEnum(detail.messageType(), VALID_MESSAGE_TYPES, "SourceDetails.MessageType");
                requireEnum(detail.maximumExecutionFrequency(), VALID_EXECUTION_FREQUENCIES,
                        "SourceDetails.MaximumExecutionFrequency");
            }
        }
        if (requested.scope() != null && requested.scope().complianceResourceTypes() != null
                && requested.scope().complianceResourceTypes().size() > MAX_COMPLIANCE_RESOURCE_TYPES) {
            throw new AwsException("InvalidParameterValueException",
                    "Scope.ComplianceResourceTypes accepts at most "
                            + MAX_COMPLIANCE_RESOURCE_TYPES + " values.", 400);
        }
        if (requested.evaluationModes() != null) {
            for (EvaluationModeConfiguration mode : requested.evaluationModes()) {
                requireEnum(mode == null ? null : mode.mode(), VALID_EVALUATION_MODES,
                        "EvaluationModes.Mode");
            }
        }
        String ruleName = requested.configRuleName();
        Map<String, ConfigRule> store = rulesFor(region);
        ConfigRule existing = store.get(ruleName);
        String ruleId;
        String ruleArn;
        String state;
        if (existing != null) {
            ruleId = existing.configRuleId();
            ruleArn = existing.configRuleArn();
            state = existing.configRuleState();
        } else {
            ruleId = "config-rule-" + shortId();
            ruleArn = AwsArnUtils.Arn.of("config", region, regionResolver.getAccountId(),
                    "config-rule/" + ruleId).toString();
            state = isBlank(requested.configRuleState()) ? "ACTIVE" : requested.configRuleState();
            ruleFirstActivatedTime.putIfAbsent(ruleKey(region, ruleName), now());
        }
        List<EvaluationModeConfiguration> evaluationModes =
                requested.evaluationModes() == null || requested.evaluationModes().isEmpty()
                        ? List.of(new EvaluationModeConfiguration("DETECTIVE"))
                        : requested.evaluationModes();
        ConfigRule rule = new ConfigRule(ruleName, ruleArn, ruleId, requested.description(),
                requested.scope(), requested.source(), requested.inputParameters(),
                requested.maximumExecutionFrequency(), state, requested.createdBy(), evaluationModes);
        store.put(ruleName, rule);
        persistRegion(configRules, region);
        return rule;
    }

    public void deleteConfigRule(String region, String ruleName) {
        if (isBlank(ruleName)) {
            throw new AwsException("NoSuchConfigRuleException", NO_SUCH_CONFIG_RULE_MESSAGE, 400);
        }
        synchronized (lockFor(ruleKey(region, ruleName))) {
            Map<String, ConfigRule> store = rulesFor(region);
            if (store.remove(ruleName) == null) {
                throw new AwsException("NoSuchConfigRuleException", NO_SUCH_CONFIG_RULE_MESSAGE, 400);
            }
            persistRegion(configRules, region);
            Map<String, Map<String, ConfigEvaluation>> regionEvaluations = evaluations.get(region);
            if (regionEvaluations != null && regionEvaluations.remove(ruleName) != null) {
                persistRegion(evaluations, region);
            }
            ruleFirstActivatedTime.remove(ruleKey(region, ruleName));
            ruleLastInvokedTime.remove(ruleKey(region, ruleName));
        }
    }

    public List<ConfigRule> describeConfigRules(String region, List<String> ruleNames) {
        if (ruleNames != null && ruleNames.size() > MAX_DESCRIBE_CONFIG_RULE_NAMES) {
            throw new AwsException("InvalidParameterValueException",
                    "ConfigRuleNames accepts at most " + MAX_DESCRIBE_CONFIG_RULE_NAMES + " values.", 400);
        }
        Map<String, ConfigRule> store = rulesFor(region);
        List<ConfigRule> result = new ArrayList<>();
        if (ruleNames == null || ruleNames.isEmpty()) {
            result.addAll(store.values());
        } else {
            for (String name : ruleNames) {
                ConfigRule rule = store.get(name);
                if (rule == null) {
                    throw new AwsException("NoSuchConfigRuleException", NO_SUCH_CONFIG_RULE_MESSAGE, 400);
                }
                result.add(rule);
            }
        }
        result.sort(Comparator.comparing(ConfigRule::configRuleName));
        return result;
    }

    public Paged<ConfigRule> describeConfigRulesPaged(String region, List<String> ruleNames, String nextToken) {
        return paginate(describeConfigRules(region, ruleNames), null, nextToken,
                25, 25, "InvalidParameterValueException");
    }

    public Paged<ConfigRuleEvaluationStatus> describeConfigRuleEvaluationStatus(String region,
            List<String> ruleNames, Integer limit, String nextToken) {
        List<ConfigRuleEvaluationStatus> statuses = new ArrayList<>();
        for (ConfigRule rule : describeConfigRules(region, ruleNames)) {
            String key = ruleKey(region, rule.configRuleName());
            Map<String, ConfigEvaluation> ruleEvaluations =
                    evaluationsFor(region).getOrDefault(rule.configRuleName(), Map.of());
            Long lastEvaluationTime = ruleEvaluations.values().stream()
                    .map(ConfigEvaluation::resultRecordedTime)
                    .filter(Objects::nonNull)
                    .max(Long::compareTo)
                    .orElse(ruleLastInvokedTime.get(key));
            boolean evaluationStarted = !ruleEvaluations.isEmpty() || ruleLastInvokedTime.containsKey(key);
            statuses.add(new ConfigRuleEvaluationStatus(
                    rule.configRuleName(), rule.configRuleArn(), rule.configRuleId(),
                    evaluationStarted, ruleFirstActivatedTime.get(key),
                    lastEvaluationTime, lastEvaluationTime));
        }
        return paginate(statuses, limit, nextToken, 150, 150, "InvalidParameterValueException");
    }

    public void startConfigRulesEvaluation(String region, List<String> ruleNames) {
        Map<String, ConfigRule> store = rulesFor(region);
        for (String name : ruleNames) {
            if (!store.containsKey(name)) {
                throw new AwsException("NoSuchConfigRuleException", NO_SUCH_CONFIG_RULE_MESSAGE, 400);
            }
        }
        for (String name : ruleNames) {
            ruleLastInvokedTime.put(ruleKey(region, name), now());
        }
    }

    // --- Evaluations ---

    public void putEvaluations(String region, String resultToken, List<ConfigEvaluation> newEvaluations,
            boolean testMode) {
        if (isBlank(resultToken)) {
            throw new AwsException("InvalidResultTokenException",
                    "The specified ResultToken is not valid.", 400);
        }
        if (newEvaluations.size() > 100) {
            throw new AwsException("InvalidParameterValueException",
                    "The Evaluations list cannot contain more than 100 items.", 400);
        }
        newEvaluations.forEach(this::validateEvaluation);
        if (testMode) {
            return;
        }
        int separator = resultToken.indexOf(':');
        String ruleName = separator > 0 ? resultToken.substring(0, separator) : resultToken;
        synchronized (lockFor(ruleKey(region, ruleName))) {
            requireRule(region, ruleName);
            storeEvaluations(region, ruleName, newEvaluations);
        }
    }

    public void putExternalEvaluation(String region, String ruleName, ConfigEvaluation evaluation) {
        validateEvaluation(evaluation);
        synchronized (lockFor(ruleKey(region, ruleName))) {
            requireRule(region, ruleName);
            storeEvaluations(region, ruleName, List.of(evaluation));
        }
    }

    public void deleteEvaluationResults(String region, String ruleName) {
        synchronized (lockFor(ruleKey(region, ruleName))) {
            requireRule(region, ruleName);
            Map<String, Map<String, ConfigEvaluation>> regionEvaluations = evaluations.get(region);
            if (regionEvaluations != null && regionEvaluations.remove(ruleName) != null) {
                persistRegion(evaluations, region);
            }
        }
    }

    private void validateEvaluation(ConfigEvaluation evaluation) {
        if (evaluation == null || isBlank(evaluation.complianceResourceType())
                || isBlank(evaluation.complianceResourceId()) || isBlank(evaluation.complianceType())
                || evaluation.orderingTimestamp() == null) {
            throw new AwsException("InvalidParameterValueException",
                    "Each evaluation must specify ComplianceResourceType, ComplianceResourceId, "
                            + "ComplianceType and OrderingTimestamp.", 400);
        }
        if (!VALID_COMPLIANCE_TYPES.contains(evaluation.complianceType())) {
            throw new AwsException("InvalidParameterValueException",
                    "ComplianceType must be one of COMPLIANT, NON_COMPLIANT, NOT_APPLICABLE, "
                            + "INSUFFICIENT_DATA.", 400);
        }
    }

    private void storeEvaluations(String region, String ruleName, List<ConfigEvaluation> newEvaluations) {
        long recordedTime = now();
        Map<String, ConfigEvaluation> ruleEvaluations = evaluationsFor(region)
                .computeIfAbsent(ruleName, r -> new ConcurrentHashMap<>());
        for (ConfigEvaluation evaluation : newEvaluations) {
            String key = resourceKey(evaluation.complianceResourceType(), evaluation.complianceResourceId());
            ruleEvaluations.compute(key, (ignoredKey, current) -> {
                if (current != null && current.orderingTimestamp() != null
                        && evaluation.orderingTimestamp() < current.orderingTimestamp()) {
                    return current;
                }
                return new ConfigEvaluation(
                        evaluation.complianceResourceType(),
                        evaluation.complianceResourceId(),
                        evaluation.complianceType(),
                        evaluation.annotation(),
                        evaluation.orderingTimestamp(),
                        recordedTime,
                        recordedTime);
            });
        }
        ruleLastInvokedTime.put(ruleKey(region, ruleName), recordedTime);
        persistRegion(evaluations, region);
    }

    // --- Compliance ---

    public Compliance complianceForRule(String region, String ruleName) {
        List<ConfigEvaluation> applicable = evaluationsFor(region)
                .getOrDefault(ruleName, Map.of()).values().stream()
                .filter(e -> !"NOT_APPLICABLE".equals(e.complianceType()))
                .toList();
        if (applicable.isEmpty()) {
            return new Compliance("INSUFFICIENT_DATA", null);
        }
        long nonCompliant = applicable.stream()
                .filter(e -> "NON_COMPLIANT".equals(e.complianceType()))
                .count();
        if (nonCompliant > 0) {
            return new Compliance("NON_COMPLIANT", contributorCount(nonCompliant, RULE_CONTRIBUTOR_CAP));
        }
        if (applicable.stream().anyMatch(e -> "INSUFFICIENT_DATA".equals(e.complianceType()))) {
            return new Compliance("INSUFFICIENT_DATA", null);
        }
        return new Compliance("COMPLIANT", null);
    }

    public Paged<ComplianceByConfigRule> describeComplianceByConfigRule(String region, List<String> ruleNames,
            List<String> complianceTypes, String nextToken) {
        validateComplianceTypeFilter(complianceTypes);
        List<ComplianceByConfigRule> entries = new ArrayList<>();
        for (ConfigRule rule : describeConfigRules(region, ruleNames)) {
            Compliance compliance = complianceForRule(region, rule.configRuleName());
            if (matchesComplianceFilter(complianceTypes, compliance.complianceType())) {
                entries.add(new ComplianceByConfigRule(rule.configRuleName(), compliance));
            }
        }
        return paginate(entries, null, nextToken, 25, 25, "InvalidParameterValueException");
    }

    public Paged<ComplianceByResource> describeComplianceByResource(String region, String resourceType,
            String resourceId, List<String> complianceTypes, Integer limit, String nextToken) {
        validateComplianceTypeFilter(complianceTypes);
        if (!isBlank(resourceId) && isBlank(resourceType)) {
            throw new AwsException("InvalidParameterValueException",
                    "ResourceType must be specified when ResourceId is provided.", 400);
        }
        List<ComplianceByResource> entries = new ArrayList<>();
        for (List<ConfigEvaluation> resourceEvaluations
                : groupEvaluationsByResource(region, resourceType, resourceId).values()) {
            ConfigEvaluation first = resourceEvaluations.get(0);
            Compliance compliance = complianceForResource(resourceEvaluations);
            if (matchesComplianceFilter(complianceTypes, compliance.complianceType())) {
                entries.add(new ComplianceByResource(first.complianceResourceType(),
                        first.complianceResourceId(), compliance));
            }
        }
        entries.sort(Comparator.comparing(ComplianceByResource::resourceType)
                .thenComparing(ComplianceByResource::resourceId));
        return paginate(entries, limit, nextToken, 10, 100, "InvalidParameterValueException");
    }

    public Paged<EvaluationResult> getComplianceDetailsByConfigRule(String region, String ruleName,
            List<String> complianceTypes, Integer limit, String nextToken) {
        validateComplianceTypeFilter(complianceTypes);
        requireRule(region, ruleName);
        List<EvaluationResult> results = evaluationsFor(region)
                .getOrDefault(ruleName, Map.of()).values().stream()
                .filter(e -> matchesComplianceFilter(complianceTypes, e.complianceType()))
                .sorted(Comparator.comparing(ConfigEvaluation::complianceResourceType)
                        .thenComparing(ConfigEvaluation::complianceResourceId))
                .map(e -> toEvaluationResult(ruleName, e))
                .collect(Collectors.toList());
        return paginate(results, limit, nextToken, 10, 100, "InvalidParameterValueException");
    }

    public Paged<EvaluationResult> getComplianceDetailsByResource(String region, String resourceType,
            String resourceId, List<String> complianceTypes, String nextToken) {
        validateComplianceTypeFilter(complianceTypes);
        if (isBlank(resourceType) || isBlank(resourceId)) {
            throw new AwsException("InvalidParameterValueException",
                    "ResourceType and ResourceId must be specified.", 400);
        }
        String key = resourceKey(resourceType, resourceId);
        List<EvaluationResult> results = new ArrayList<>();
        evaluationsFor(region).forEach((ruleName, ruleEvaluations) -> {
            ConfigEvaluation evaluation = ruleEvaluations.get(key);
            if (evaluation != null && matchesComplianceFilter(complianceTypes, evaluation.complianceType())) {
                results.add(toEvaluationResult(ruleName, evaluation));
            }
        });
        results.sort(Comparator.comparing(r ->
                r.evaluationResultIdentifier().evaluationResultQualifier().configRuleName()));
        return paginate(results, null, nextToken, 10, 10, "InvalidParameterValueException");
    }

    public ComplianceSummary getComplianceSummaryByConfigRule(String region) {
        long compliant = 0;
        long nonCompliant = 0;
        for (String ruleName : rulesFor(region).keySet()) {
            String complianceType = complianceForRule(region, ruleName).complianceType();
            if ("COMPLIANT".equals(complianceType)) {
                compliant++;
            } else if ("NON_COMPLIANT".equals(complianceType)) {
                nonCompliant++;
            }
        }
        return new ComplianceSummary(contributorCount(compliant, RULE_CONTRIBUTOR_CAP),
                contributorCount(nonCompliant, RULE_CONTRIBUTOR_CAP), now());
    }

    public List<ComplianceSummaryByResourceType> getComplianceSummaryByResourceType(String region,
            List<String> resourceTypes) {
        if (resourceTypes != null && resourceTypes.size() > 20) {
            throw new AwsException("InvalidParameterValueException",
                    "ResourceTypes cannot contain more than 20 items.", 400);
        }
        Map<String, long[]> countsByType = new HashMap<>();
        for (List<ConfigEvaluation> resourceEvaluations
                : groupEvaluationsByResource(region, null, null).values()) {
            String type = resourceEvaluations.get(0).complianceResourceType();
            String complianceType = complianceForResource(resourceEvaluations).complianceType();
            long[] counts = countsByType.computeIfAbsent(type, t -> new long[2]);
            if ("COMPLIANT".equals(complianceType)) {
                counts[0]++;
            } else if ("NON_COMPLIANT".equals(complianceType)) {
                counts[1]++;
            }
        }
        long timestamp = now();
        if (resourceTypes == null || resourceTypes.isEmpty()) {
            long compliant = countsByType.values().stream().mapToLong(c -> c[0]).sum();
            long nonCompliant = countsByType.values().stream().mapToLong(c -> c[1]).sum();
            return List.of(new ComplianceSummaryByResourceType(null, new ComplianceSummary(
                    contributorCount(compliant, RESOURCE_CONTRIBUTOR_CAP),
                    contributorCount(nonCompliant, RESOURCE_CONTRIBUTOR_CAP), timestamp)));
        }
        List<ComplianceSummaryByResourceType> result = new ArrayList<>();
        for (String type : resourceTypes) {
            long[] counts = countsByType.getOrDefault(type, new long[2]);
            result.add(new ComplianceSummaryByResourceType(type, new ComplianceSummary(
                    contributorCount(counts[0], RESOURCE_CONTRIBUTOR_CAP),
                    contributorCount(counts[1], RESOURCE_CONTRIBUTOR_CAP), timestamp)));
        }
        return result;
    }

    private Map<String, List<ConfigEvaluation>> groupEvaluationsByResource(String region,
            String resourceType, String resourceId) {
        Map<String, List<ConfigEvaluation>> byResource = new HashMap<>();
        for (Map<String, ConfigEvaluation> ruleEvaluations : evaluationsFor(region).values()) {
            for (ConfigEvaluation evaluation : ruleEvaluations.values()) {
                if (!isBlank(resourceType) && !resourceType.equals(evaluation.complianceResourceType())) {
                    continue;
                }
                if (!isBlank(resourceId) && !resourceId.equals(evaluation.complianceResourceId())) {
                    continue;
                }
                String key = resourceKey(evaluation.complianceResourceType(),
                        evaluation.complianceResourceId());
                byResource.computeIfAbsent(key, k -> new ArrayList<>()).add(evaluation);
            }
        }
        return byResource;
    }

    private Compliance complianceForResource(List<ConfigEvaluation> resourceEvaluations) {
        long nonCompliant = resourceEvaluations.stream()
                .filter(e -> "NON_COMPLIANT".equals(e.complianceType()))
                .count();
        if (nonCompliant > 0) {
            return new Compliance("NON_COMPLIANT", contributorCount(nonCompliant, RULE_CONTRIBUTOR_CAP));
        }
        if (resourceEvaluations.stream().anyMatch(e -> "INSUFFICIENT_DATA".equals(e.complianceType()))) {
            return new Compliance("INSUFFICIENT_DATA", null);
        }
        if (resourceEvaluations.stream().anyMatch(e -> "COMPLIANT".equals(e.complianceType()))) {
            return new Compliance("COMPLIANT", null);
        }
        return new Compliance("NOT_APPLICABLE", null);
    }

    private EvaluationResult toEvaluationResult(String ruleName, ConfigEvaluation evaluation) {
        return new EvaluationResult(
                new EvaluationResultIdentifier(
                        new EvaluationResultQualifier(ruleName, evaluation.complianceResourceType(),
                                evaluation.complianceResourceId(), "DETECTIVE"),
                        evaluation.orderingTimestamp()),
                evaluation.complianceType(),
                evaluation.resultRecordedTime(),
                evaluation.configRuleInvokedTime(),
                evaluation.annotation(),
                null);
    }

    private static boolean matchesComplianceFilter(List<String> complianceTypes, String complianceType) {
        return complianceTypes == null || complianceTypes.isEmpty() || complianceTypes.contains(complianceType);
    }

    /**
     * A {@code ComplianceTypes} filter outside the modeled enum (or longer than the list's
     * {@code max: 3}) would otherwise match nothing and read to the caller as "no results"
     * instead of the rejection AWS answers.
     */
    private static void validateComplianceTypeFilter(List<String> complianceTypes) {
        if (complianceTypes == null || complianceTypes.isEmpty()) {
            return;
        }
        if (complianceTypes.size() > MAX_COMPLIANCE_TYPE_FILTERS) {
            throw new AwsException("InvalidParameterValueException",
                    "ComplianceTypes accepts at most " + MAX_COMPLIANCE_TYPE_FILTERS + " values.", 400);
        }
        for (String complianceType : complianceTypes) {
            requireEnum(complianceType, VALID_COMPLIANCE_TYPES, "ComplianceTypes");
        }
    }

    /** Rejects a non-blank value that is not a member of the shape's enum; a null/blank value is left alone. */
    private static void requireEnum(String value, Set<String> allowed, String memberName) {
        if (isBlank(value) || allowed.contains(value)) {
            return;
        }
        throw new AwsException("InvalidParameterValueException",
                "Value '" + value + "' at '" + memberName + "' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: ["
                        + allowed.stream().sorted().collect(Collectors.joining(", ")) + "]",
                400);
    }

    private ComplianceContributorCount contributorCount(long count, int cap) {
        return new ComplianceContributorCount((int) Math.min(count, cap), count > cap);
    }

    private ConfigRule requireRule(String region, String ruleName) {
        ConfigRule rule = isBlank(ruleName) ? null : rulesFor(region).get(ruleName);
        if (rule == null) {
            throw new AwsException("NoSuchConfigRuleException", NO_SUCH_CONFIG_RULE_MESSAGE, 400);
        }
        return rule;
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

    public void deleteConfigurationRecorder(String region, String name) {
        ConfigurationRecorder recorder = configurationRecorders.get(region);
        if (recorder == null || !recorder.name().equals(name)) {
            throw new AwsException("NoSuchConfigurationRecorderException",
                    "Cannot find configuration recorder with the specified name.", 400);
        }
        configurationRecorders.remove(region);
        recorderRunning.remove(region);
        recorderLastStartTime.remove(region);
        recorderLastStopTime.remove(region);
    }

    public void startConfigurationRecorder(String region, String name) {
        ConfigurationRecorder recorder = configurationRecorders.get(region);
        if (recorder == null || !recorder.name().equals(name)) {
            throw new AwsException("NoSuchConfigurationRecorderException",
                    "Cannot find configuration recorder with the specified name.", 400);
        }
        recorderRunning.put(region, true);
        recorderLastStartTime.put(region, now());
    }

    public void stopConfigurationRecorder(String region, String name) {
        ConfigurationRecorder recorder = configurationRecorders.get(region);
        if (recorder == null || !recorder.name().equals(name)) {
            throw new AwsException("NoSuchConfigurationRecorderException",
                    "Cannot find configuration recorder with the specified name.", 400);
        }
        recorderRunning.put(region, false);
        recorderLastStopTime.put(region, now());
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

    public void deleteDeliveryChannel(String region, String name) {
        DeliveryChannel channel = deliveryChannels.get(region);
        if (channel == null || !channel.name().equals(name)) {
            throw new AwsException("NoSuchDeliveryChannelException",
                    "Cannot find delivery channel with the specified name.", 400);
        }
        if (recorderRunning.getOrDefault(region, false)) {
            throw new AwsException("LastDeliveryChannelDeleteFailedException",
                    "You cannot delete the delivery channel you specified because the customer managed "
                            + "configuration recorder is running.", 400);
        }
        deliveryChannels.remove(region);
    }

    // --- Retention Configuration ---

    public RetentionConfiguration putRetentionConfiguration(String region, Integer retentionPeriodInDays) {
        if (retentionPeriodInDays == null || retentionPeriodInDays < 30 || retentionPeriodInDays > 2557) {
            throw new AwsException("InvalidParameterValueException",
                    "RetentionPeriodInDays must be between 30 and 2557.", 400);
        }
        RetentionConfiguration configuration = new RetentionConfiguration("default", retentionPeriodInDays);
        retentionConfigurations.put(region, configuration);
        return configuration;
    }

    public List<RetentionConfiguration> describeRetentionConfigurations(String region, List<String> names) {
        RetentionConfiguration configuration = retentionConfigurations.get(region);
        if (names != null && !names.isEmpty()) {
            for (String name : names) {
                if (configuration == null || !configuration.name().equals(name)) {
                    throw new AwsException("NoSuchRetentionConfigurationException",
                            "Cannot find retention configuration with the specified name.", 400);
                }
            }
        }
        return configuration == null ? Collections.emptyList() : List.of(configuration);
    }

    public void deleteRetentionConfiguration(String region, String name) {
        if (isBlank(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "RetentionConfigurationName must be specified.", 400);
        }
        RetentionConfiguration configuration = retentionConfigurations.get(region);
        if (configuration == null || !configuration.name().equals(name)) {
            throw new AwsException("NoSuchRetentionConfigurationException",
                    "Cannot find retention configuration with the specified name.", 400);
        }
        retentionConfigurations.remove(region);
    }

    // --- Conformance Packs ---

    public ConformancePack putConformancePack(String region, String packName,
                                              String templateS3Uri, String templateBody) {
        Map<String, ConformancePack> store = packsFor(region);
        ConformancePack existing = store.get(packName);
        if (existing != null) {
            ConformancePack updated = new ConformancePack(existing.conformancePackName(), existing.conformancePackArn(),
                    existing.conformancePackId(), templateS3Uri, templateBody);
            store.put(packName, updated);
            persistRegion(conformancePacks, region);
            return updated;
        }
        String packId = "conformance-pack-" + shortId();
        String packArn = AwsArnUtils.Arn.of("config", region, regionResolver.getAccountId(),
                "conformance-pack/" + packName + "/" + packId).toString();
        ConformancePack pack = new ConformancePack(packName, packArn, packId, templateS3Uri, templateBody);
        store.put(packName, pack);
        persistRegion(conformancePacks, region);
        return pack;
    }

    public void deleteConformancePack(String region, String packName) {
        Map<String, ConformancePack> store = packsFor(region);
        if (store.remove(packName) == null) {
            throw new AwsException("NoSuchConformancePackException",
                    "Conformance pack '" + packName + "' does not exist.", 400);
        }
        persistRegion(conformancePacks, region);
    }

    public List<ConformancePack> describeConformancePacks(String region, List<String> names) {
        Map<String, ConformancePack> store = packsFor(region);
        List<ConformancePack> result = new ArrayList<>();
        if (names == null || names.isEmpty()) {
            result.addAll(store.values());
        } else {
            for (String name : names) {
                ConformancePack pack = store.get(name);
                if (pack == null) {
                    throw new AwsException("NoSuchConformancePackException",
                            "Conformance pack '" + name + "' does not exist.", 400);
                }
                result.add(pack);
            }
        }
        result.sort(Comparator.comparing(ConformancePack::conformancePackName));
        return result;
    }

    public Paged<ConformancePack> describeConformancePacksPaged(String region, List<String> names,
            Integer limit, String nextToken) {
        return paginate(describeConformancePacks(region, names), limit, nextToken,
                20, 20, "InvalidLimitException");
    }

    public Paged<ConformancePackStatusDetail> describeConformancePackStatus(String region, List<String> names,
            Integer limit, String nextToken) {
        List<ConformancePackStatusDetail> result = new ArrayList<>();
        for (ConformancePack pack : describeConformancePacks(region, names)) {
            result.add(new ConformancePackStatusDetail(
                    pack.conformancePackName(),
                    pack.conformancePackId(),
                    pack.conformancePackArn(),
                    "CREATE_SUCCESSFUL",
                    now()));
        }
        return paginate(result, limit, nextToken, 20, 20, "InvalidLimitException");
    }

    // --- Tagging ---

    public void tagResource(String arn, List<Map<String, String>> tagList) {
        Map<String, String> tagMap = tags.computeIfAbsent(arn, k -> new ConcurrentHashMap<>());
        for (Map<String, String> t : tagList) {
            tagMap.put(t.get("Key"), t.get("Value"));
        }
        tags.put(arn, tagMap); // write back the in-place inner-map mutation
    }

    public void untagResource(String arn, List<String> tagKeys) {
        Map<String, String> tagMap = tags.get(arn);
        if (tagMap != null) {
            tagKeys.forEach(tagMap::remove);
            tags.put(arn, tagMap); // write back the in-place inner-map mutation
        }
    }

    public List<Map<String, String>> listTagsForResource(String arn) {
        Map<String, String> tagMap = tags.getOrDefault(arn, Map.of());
        return tagMap.entrySet().stream()
                .map(e -> Map.of("Key", e.getKey(), "Value", e.getValue()))
                .collect(Collectors.toList());
    }

    // --- Pagination ---

    public record Paged<T>(List<T> items, String nextToken) {}

    /** Offset-as-NextToken pagination over a pre-sorted list. A null or zero limit falls back to
     *  the default page size; limits outside [1, maxLimit] fail with the op-specific error code. */
    private <T> Paged<T> paginate(List<T> items, Integer limit, String nextToken,
            int defaultPageSize, int maxLimit, String limitErrorCode) {
        int pageSize = defaultPageSize;
        if (limit != null && limit != 0) {
            if (limit < 0 || limit > maxLimit) {
                throw new AwsException(limitErrorCode,
                        "Limit must be between 0 and " + maxLimit + ".", 400);
            }
            pageSize = limit;
        }
        int offset = 0;
        if (nextToken != null && !nextToken.isEmpty()) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidNextTokenException", INVALID_NEXT_TOKEN_MESSAGE, 400);
            }
            if (offset < 0 || offset > items.size()) {
                throw new AwsException("InvalidNextTokenException", INVALID_NEXT_TOKEN_MESSAGE, 400);
            }
        }
        int end = Math.min(offset + pageSize, items.size());
        List<T> page = new ArrayList<>(items.subList(offset, end));
        return new Paged<>(page, end < items.size() ? String.valueOf(end) : null);
    }

    // --- Helpers ---

    private Map<String, ConfigRule> rulesFor(String region) {
        return configRules.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, ConformancePack> packsFor(String region) {
        return conformancePacks.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, Map<String, ConfigEvaluation>> evaluationsFor(String region) {
        return evaluations.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private static String resourceKey(String resourceType, String resourceId) {
        return resourceType + "|" + resourceId;
    }

    private static String ruleKey(String region, String ruleName) {
        return region + "|" + ruleName;
    }

    private Object lockFor(String key) {
        return ruleLocks.computeIfAbsent(key, k -> new Object());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
