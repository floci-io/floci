package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.AccountPolicy;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogDestination;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class CloudWatchLogsCrossAccountService {
    private static final Pattern DESTINATION_NAME = Pattern.compile("[^:*]{1,512}");
    private static final Pattern METRIC_SELECTION_CRITERIA = Pattern.compile(
            "\\s*(LogGroupName|LogGroupNamePrefix)\\s+(IN|NOT\\s+IN)\\s*(\\[.*])\\s*",
            Pattern.DOTALL);
    private static final Pattern QUOTED_SELECTION_VALUE = Pattern.compile(
            "'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Set<String> POLICY_TYPES = Set.of(
            "DATA_PROTECTION_POLICY", "SUBSCRIPTION_FILTER_POLICY", "FIELD_INDEX_POLICY",
            "TRANSFORMER_POLICY", "METRIC_EXTRACTION_POLICY");
    private static final int DESTINATION_POLICY_MAX_BYTES = 5120;
    private static final int ACCOUNT_POLICY_MAX_CHARS = 30_720;
    private static final int SELECTION_CRITERIA_MAX_BYTES = 25 * 1024;

    private final StorageBackend<String, LogDestination> destinations;
    private final StorageBackend<String, AccountPolicy> accountPolicies;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudWatchLogsCrossAccountService(StorageFactory storageFactory, RegionResolver regionResolver,
                                             ObjectMapper objectMapper) {
        this(
                storageFactory.create("cloudwatchlogs", "cwlogs-destinations.json",
                        new TypeReference<Map<String, LogDestination>>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-account-policies.json",
                        new TypeReference<Map<String, AccountPolicy>>() {}),
                regionResolver, objectMapper);
    }

    CloudWatchLogsCrossAccountService(StorageBackend<String, LogDestination> destinations,
                                      StorageBackend<String, AccountPolicy> accountPolicies,
                                      RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.destinations = destinations;
        this.accountPolicies = accountPolicies;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized LogDestination putDestination(String destinationName, String targetArn,
                                                       String roleArn, String region) {
        validateDestinationName(destinationName);
        requireArn(targetArn, "targetArn");
        requireArn(roleArn, "roleArn");
        String key = destinationKey(region, destinationName);
        LogDestination destination = destinations.get(key).orElseGet(LogDestination::new);
        destination.setDestinationName(destinationName);
        destination.setTargetArn(targetArn);
        destination.setRoleArn(roleArn);
        destination.setArn("arn:aws:logs:" + region + ":" + regionResolver.getAccountId() + ":destination:" + destinationName);
        if (destination.getCreationTime() == 0) {
            destination.setCreationTime(System.currentTimeMillis());
        }
        destinations.put(key, destination);
        return destination;
    }

    public synchronized void putDestinationPolicy(String destinationName, String accessPolicy, String region) {
        validateDestinationName(destinationName);
        requireJsonObject(accessPolicy, "accessPolicy", DESTINATION_POLICY_MAX_BYTES);
        String key = destinationKey(region, destinationName);
        LogDestination destination = destinations.get(key)
                .orElseThrow(() -> invalid("The specified destination does not exist."));
        destination.setAccessPolicy(accessPolicy);
        destinations.put(key, destination);
    }

    public synchronized AccountPolicy putAccountPolicy(String policyName, String policyDocument,
                                                        String policyType, String selectionCriteria, String scope,
                                                        String region) {
        validatePolicyName(policyName);
        if (policyType == null || !POLICY_TYPES.contains(policyType)) {
            throw invalid("policyType is invalid.");
        }
        requirePolicyDocument(policyDocument, policyType);
        if (scope != null && !"ALL".equals(scope)) {
            throw invalid("scope must be ALL.");
        }
        validateSelectionCriteria(policyType, selectionCriteria);

        String key = policyKey(region, policyType, policyName);
        validatePolicyQuota(region, policyType, policyName, selectionCriteria);

        AccountPolicy policy = accountPolicies.get(key).orElseGet(AccountPolicy::new);
        policy.setAccountId(regionResolver.getAccountId());
        policy.setPolicyName(policyName);
        policy.setPolicyDocument(policyDocument);
        policy.setPolicyType(policyType);
        policy.setSelectionCriteria(selectionCriteria);
        policy.setScope(scope == null || scope.isBlank() ? "ALL" : scope);
        policy.setLastUpdatedTime(System.currentTimeMillis());
        accountPolicies.put(key, policy);
        return policy;
    }

    public List<AccountPolicy> describeAccountPolicies(String policyType, String policyName, String region) {
        if (policyType == null || !POLICY_TYPES.contains(policyType)) {
            throw invalid("policyType is required and must be valid.");
        }
        List<AccountPolicy> result = accountPolicies.scan(key -> key.startsWith(region + "::" + policyType + "::")).stream()
                .filter(policy -> policyName == null || policyName.equals(policy.getPolicyName()))
                .sorted(Comparator.comparing(AccountPolicy::getPolicyName))
                .toList();
        if (policyName != null && result.isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "The specified account policy does not exist.", 400);
        }
        return result;
    }

    private void validatePolicyQuota(String region, String type, String policyName, String selectionCriteria) {
        List<AccountPolicy> others = accountPolicies.scan(key -> key.startsWith(region + "::" + type + "::")).stream()
                .filter(policy -> !policyName.equals(policy.getPolicyName()))
                .toList();
        boolean scoped = hasSelectionCriteria(selectionCriteria);

        switch (type) {
            case "DATA_PROTECTION_POLICY", "SUBSCRIPTION_FILTER_POLICY" -> {
                if (!others.isEmpty()) {
                    throw quotaExceeded(type);
                }
            }
            case "TRANSFORMER_POLICY" -> validateExclusiveScopedQuota(type, scoped, 20, others);
            case "METRIC_EXTRACTION_POLICY" -> validateExclusiveScopedQuota(type, scoped, 5, others);
            case "FIELD_INDEX_POLICY" -> validateFieldIndexQuota(selectionCriteria, others);
            default -> {
                // policyType is validated before this method is called.
            }
        }
    }

    private static void validateExclusiveScopedQuota(String type, boolean scoped, int scopedLimit,
                                                     List<AccountPolicy> others) {
        long scopedCount = others.stream()
                .filter(policy -> hasSelectionCriteria(policy.getSelectionCriteria()))
                .count();
        boolean hasUnscoped = others.stream()
                .anyMatch(policy -> !hasSelectionCriteria(policy.getSelectionCriteria()));
        if (scoped ? hasUnscoped || scopedCount >= scopedLimit : !others.isEmpty()) {
            throw quotaExceeded(type);
        }
    }

    private static void validateFieldIndexQuota(String selectionCriteria, List<AccountPolicy> others) {
        FieldIndexScope requestedScope = fieldIndexScope(selectionCriteria);
        long prefixCount = others.stream()
                .filter(policy -> fieldIndexScope(policy.getSelectionCriteria()) == FieldIndexScope.PREFIX)
                .count();
        long dataSourceCount = others.stream()
                .filter(policy -> fieldIndexScope(policy.getSelectionCriteria()) == FieldIndexScope.DATA_SOURCE)
                .count();
        boolean hasGlobal = others.stream()
                .anyMatch(policy -> fieldIndexScope(policy.getSelectionCriteria()) == FieldIndexScope.GLOBAL);

        boolean exceeded = switch (requestedScope) {
            case GLOBAL -> hasGlobal || prefixCount > 0;
            case PREFIX -> hasGlobal || prefixCount >= 20;
            case DATA_SOURCE -> dataSourceCount >= 20;
        };
        if (exceeded) {
            throw quotaExceeded("FIELD_INDEX_POLICY");
        }
    }

    private static boolean hasSelectionCriteria(String selectionCriteria) {
        return selectionCriteria != null && !selectionCriteria.isBlank();
    }

    private static FieldIndexScope fieldIndexScope(String selectionCriteria) {
        if (!hasSelectionCriteria(selectionCriteria)) {
            return FieldIndexScope.GLOBAL;
        }
        String operators = fieldIndexOperators(selectionCriteria);
        if (operators.contains("DataSourceName") && operators.contains("DataSourceType")) {
            return FieldIndexScope.DATA_SOURCE;
        }
        return FieldIndexScope.PREFIX;
    }

    private static String fieldIndexOperators(String selectionCriteria) {
        return QUOTED_SELECTION_VALUE.matcher(selectionCriteria).replaceAll("");
    }

    private static AwsException quotaExceeded(String policyType) {
        return new AwsException("LimitExceededException",
                "The account policy quota for " + policyType + " has been exceeded.", 400);
    }

    private void validateSelectionCriteria(String policyType, String selectionCriteria) {
        if (selectionCriteria == null || selectionCriteria.isBlank()) {
            return;
        }
        if (selectionCriteria.getBytes(StandardCharsets.UTF_8).length > SELECTION_CRITERIA_MAX_BYTES) {
            throw invalid("selectionCriteria exceeds 25 KB.");
        }
        switch (policyType) {
            case "DATA_PROTECTION_POLICY" ->
                    throw invalid("selectionCriteria is not supported for data protection policies.");
            case "METRIC_EXTRACTION_POLICY" -> validateMetricSelectionCriteria(selectionCriteria);
            case "SUBSCRIPTION_FILTER_POLICY" -> {
                if (!selectionCriteria.matches("\\s*LogGroupName\\s+NOT\\s+IN\\s*\\[.*]\\s*")) {
                    throw invalid("Subscription filter selectionCriteria must use LogGroupName NOT IN [...].");
                }
            }
            case "TRANSFORMER_POLICY" -> {
                if (!selectionCriteria.contains("LogGroupNamePrefix")) {
                    throw invalid("Transformer selectionCriteria must use LogGroupNamePrefix.");
                }
            }
            case "FIELD_INDEX_POLICY" -> {
                String operators = fieldIndexOperators(selectionCriteria);
                boolean prefix = operators.contains("LogGroupNamePrefix");
                boolean dataSourceName = operators.contains("DataSourceName");
                boolean dataSourceType = operators.contains("DataSourceType");
                boolean prefixOnly = prefix && !dataSourceName && !dataSourceType;
                boolean dataSourcePairOnly = !prefix && dataSourceName && dataSourceType;
                if (!prefixOnly && !dataSourcePairOnly) {
                    throw invalid("Field index selectionCriteria must use either LogGroupNamePrefix or DataSourceName and DataSourceType.");
                }
            }
            default -> throw invalid("policyType is invalid.");
        }
    }

    private void validateMetricSelectionCriteria(String selectionCriteria) {
        Matcher matcher = METRIC_SELECTION_CRITERIA.matcher(selectionCriteria);
        if (!matcher.matches()) {
            throw invalid("Metric extraction selectionCriteria must use LogGroupName or LogGroupNamePrefix with IN or NOT IN.");
        }
        try {
            JsonNode values = objectMapper.readTree(matcher.group(3));
            if (!values.isArray() || values.isEmpty() || values.size() > 50) {
                throw invalid("Metric extraction selectionCriteria must contain between 1 and 50 values.");
            }
            for (JsonNode value : values) {
                if (!value.isTextual() || value.textValue().isBlank()) {
                    throw invalid("Metric extraction selectionCriteria values must be non-empty strings.");
                }
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("Metric extraction selectionCriteria must contain a valid JSON string array.");
        }
    }

    private enum FieldIndexScope {
        GLOBAL,
        PREFIX,
        DATA_SOURCE
    }

    private void requireJsonObject(String value, String field, int maxBytes) {
        JsonNode document = parseJson(value, field, maxBytes, true);
        if (!document.isObject()) {
            throw invalid(field + " must contain a JSON object.");
        }
    }

    private void requirePolicyDocument(String value, String policyType) {
        JsonNode document = parseJson(value, "policyDocument", ACCOUNT_POLICY_MAX_CHARS, false);
        if ("TRANSFORMER_POLICY".equals(policyType)) {
            if (!document.isArray() && !document.isObject()) {
                throw invalid("policyDocument must contain a JSON object or processor array.");
            }
            return;
        }
        if (!document.isObject()) {
            throw invalid("policyDocument must contain a JSON object.");
        }
    }

    private JsonNode parseJson(String value, String field, int max, boolean countUtf8Bytes) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
        int size = countUtf8Bytes ? value.getBytes(StandardCharsets.UTF_8).length : value.length();
        if (size > max) {
            throw invalid(field + " exceeds the maximum size.");
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw invalid(field + " must contain valid JSON.");
        }
    }

    private static void validateDestinationName(String value) {
        if (value == null || !DESTINATION_NAME.matcher(value).matches()) {
            throw invalid("destinationName is invalid.");
        }
    }
    private static void validatePolicyName(String value) {
        if (value == null || value.isBlank() || value.startsWith("aws/") || value.length() > 256) {
            throw invalid("policyName is invalid.");
        }
    }
    private static void requireArn(String value, String field) {
        if (value == null || !value.startsWith("arn:") || value.length() < 10) {
            throw invalid(field + " must be a valid ARN.");
        }
    }
    private static String destinationKey(String region, String name) { return region + "::" + name; }
    private static String policyKey(String region, String type, String name) { return region + "::" + type + "::" + name; }
    private static AwsException invalid(String message) { return new AwsException("InvalidParameterException", message, 400); }
}
