package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
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
import java.util.regex.Pattern;

@ApplicationScoped
public class CloudWatchLogsCrossAccountService {
    private static final Pattern DESTINATION_NAME = Pattern.compile("[^:*]{1,512}");
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

    CloudWatchLogsCrossAccountService() {
        this(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"), new ObjectMapper());
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
        if (destination.getCreationTime() == 0) destination.setCreationTime(System.currentTimeMillis());
        destinations.put(key, destination);
        return destination;
    }

    public synchronized void putDestinationPolicy(String destinationName, String accessPolicy, String region) {
        validateDestinationName(destinationName);
        requireJson(accessPolicy, "accessPolicy", DESTINATION_POLICY_MAX_BYTES);
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
        requireJson(policyDocument, "policyDocument", ACCOUNT_POLICY_MAX_CHARS);
        if (policyType == null || !POLICY_TYPES.contains(policyType)) throw invalid("policyType is invalid.");
        if (scope != null && !"ALL".equals(scope)) throw invalid("scope must be ALL.");
        validateSelectionCriteria(policyType, selectionCriteria);

        String key = policyKey(region, policyType, policyName);
        boolean updating = accountPolicies.get(key).isPresent();
        if (!updating && exceedsPolicyLimit(region, policyType, selectionCriteria)) {
            throw new AwsException("LimitExceededException",
                    "The account policy quota for this policy type has been exceeded.", 400);
        }

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
        if (policyType == null || !POLICY_TYPES.contains(policyType)) throw invalid("policyType is required and must be valid.");
        List<AccountPolicy> result = accountPolicies.scan(key -> key.startsWith(region + "::" + policyType + "::")).stream()
                .filter(policy -> policyName == null || policyName.equals(policy.getPolicyName()))
                .sorted(Comparator.comparing(AccountPolicy::getPolicyName))
                .toList();
        if (policyName != null && result.isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "The specified account policy does not exist.", 400);
        }
        return result;
    }

    private boolean exceedsPolicyLimit(String region, String type, String selectionCriteria) {
        long count = accountPolicies.scan(key -> key.startsWith(region + "::" + type + "::")).size();
        boolean scoped = selectionCriteria != null && !selectionCriteria.isBlank();
        return switch (type) {
            case "SUBSCRIPTION_FILTER_POLICY", "DATA_PROTECTION_POLICY" -> count >= 1;
            case "TRANSFORMER_POLICY", "FIELD_INDEX_POLICY" -> scoped ? count >= 20 : count >= 1;
            case "METRIC_EXTRACTION_POLICY" -> scoped ? count >= 5 : count >= 1;
            default -> false;
        };
    }

    private static void validateSelectionCriteria(String policyType, String selectionCriteria) {
        if (selectionCriteria == null || selectionCriteria.isBlank()) return;
        if (selectionCriteria.getBytes(StandardCharsets.UTF_8).length > SELECTION_CRITERIA_MAX_BYTES) {
            throw invalid("selectionCriteria exceeds 25 KB.");
        }
        switch (policyType) {
            case "DATA_PROTECTION_POLICY", "METRIC_EXTRACTION_POLICY" -> {
                if ("DATA_PROTECTION_POLICY".equals(policyType)) throw invalid("selectionCriteria is not supported for data protection policies.");
            }
            case "SUBSCRIPTION_FILTER_POLICY" -> {
                if (!selectionCriteria.matches("\\s*LogGroupName\\s+NOT\\s+IN\\s*\\[.*]\\s*")) {
                    throw invalid("Subscription filter selectionCriteria must use LogGroupName NOT IN [...].");
                }
            }
            case "TRANSFORMER_POLICY" -> {
                if (!selectionCriteria.contains("LogGroupNamePrefix")) throw invalid("Transformer selectionCriteria must use LogGroupNamePrefix.");
            }
            case "FIELD_INDEX_POLICY" -> {
                boolean prefix = selectionCriteria.contains("LogGroupNamePrefix");
                boolean source = selectionCriteria.contains("DataSourceName") && selectionCriteria.contains("DataSourceType");
                if (!prefix && !source) throw invalid("Field index selectionCriteria is invalid.");
            }
            default -> throw invalid("policyType is invalid.");
        }
    }

    private void requireJson(String value, String field, int max) {
        if (value == null || value.isBlank()) throw invalid(field + " is required.");
        int size = field.equals("accessPolicy") ? value.getBytes(StandardCharsets.UTF_8).length : value.length();
        if (size > max) throw invalid(field + " exceeds the maximum size.");
        try {
            if (!objectMapper.readTree(value).isObject()) throw invalid(field + " must contain a JSON object.");
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw invalid(field + " must contain valid JSON.");
        }
    }

    private static void validateDestinationName(String value) {
        if (value == null || !DESTINATION_NAME.matcher(value).matches()) throw invalid("destinationName is invalid.");
    }
    private static void validatePolicyName(String value) {
        if (value == null || value.isBlank() || value.startsWith("aws/") || value.length() > 256) throw invalid("policyName is invalid.");
    }
    private static void requireArn(String value, String field) {
        if (value == null || !value.startsWith("arn:") || value.length() < 10) throw invalid(field + " must be a valid ARN.");
    }
    private static String destinationKey(String region, String name) { return region + "::" + name; }
    private static String policyKey(String region, String type, String name) { return region + "::" + type + "::" + name; }
    private static AwsException invalid(String message) { return new AwsException("InvalidParameterException", message, 400); }
}
