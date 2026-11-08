package io.floci.aws.services.securityhub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@ApplicationScoped
public class SecurityHubFindingsService implements Resettable {
    private static final int MAX_FINDING_BYTES = 240 * 1024;
    private static final Set<String> FILTER_FIELDS = Set.of("ComplianceAssociatedStandardsId", "RecordState",
            "WorkflowStatus", "ComplianceStatus", "ComplianceSecurityControlId", "ProductArn",
            "AwsAccountId", "Region");
    private static final List<String> INVESTIGATION_FIELDS = List.of(
            "Note", "UserDefinedFields", "VerificationState", "Workflow");
    private static final List<String> PROVIDER_FIELDS = List.of(
            "Confidence", "Criticality", "RelatedFindings", "Severity", "Types");

    private final AccountAwareStorageBackend<Map<String, ObjectNode>> findings;
    private final SecurityHubService securityHubService;
    private final RegionResolver regionResolver;

    @Inject
    public SecurityHubFindingsService(StorageFactory storageFactory, SecurityHubService securityHubService,
                                      RegionResolver regionResolver) {
        this(storageFactory.createPartitioned("securityhub", "securityhub-findings.json",
                        new TypeReference<Map<String, Map<String, ObjectNode>>>() {}, ObjectNode.class, 32),
                securityHubService, regionResolver);
    }

    SecurityHubFindingsService(AccountAwareStorageBackend<Map<String, ObjectNode>> findings,
                               SecurityHubService securityHubService, RegionResolver regionResolver) {
        this.findings = findings;
        this.securityHubService = securityHubService;
        this.regionResolver = regionResolver;
    }

    public synchronized ImportResult batchImportFindings(String region, JsonNode request) {
        JsonNode requestedFindings = request == null ? null : request.get("Findings");
        if (requestedFindings == null || !requestedFindings.isArray()) {
            throw invalid("Findings must contain between 1 and 100 findings.");
        }
        SecurityHubRequestShapes.checkFindingsSize(requestedFindings.size());
        String accountId = regionResolver.getAccountId();
        Map<String, ObjectNode> stored = new LinkedHashMap<>(
                findings.getForAccount(accountId, region).orElseGet(LinkedHashMap::new));
        List<ImportFailure> failures = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int successCount = 0;
        boolean enabled = securityHubService.state(region).isEnabled();
        for (JsonNode candidate : requestedFindings) {
            String id = text(candidate, "Id");
            ImportError error = enabled ? validateFinding(candidate, region, accountId)
                    : new ImportError("InvalidAccess", "Security Hub is not enabled for this account.");
            if (error != null) {
                failures.add(new ImportFailure(id == null ? "" : id,
                        error.code(), error.message()));
                continue;
            }
            String key = findingKey(candidate.path("ProductArn").textValue(), id);
            if (!seen.contains(key)) {
                ObjectNode previous = stored.get(key);
                JsonNode verificationState = candidate.get("VerificationState");
                if (previous == null && verificationState != null) {
                    try {
                        SecurityHubRequestShapes.checkInitialVerificationState(verificationState);
                    } catch (AwsException e) {
                        failures.add(new ImportFailure(id, "InvalidInput",
                                "VerificationState must be a valid verification state."));
                        continue;
                    }
                }
                ObjectNode imported = ((ObjectNode) candidate).deepCopy();
                Instant updatedAt = Instant.parse(imported.path("UpdatedAt").textValue());
                if (previous != null && !updatedAt.isAfter(Instant.parse(previous.path("UpdatedAt").textValue()))) {
                    seen.add(key);
                    successCount++;
                    continue;
                }
                addManagedFields(imported, previous, region);
                stored.put(key, imported);
                seen.add(key);
                if (findings.writesEntries()) {
                    findings.putEntryForAccount(accountId, region, key, imported);
                }
            }
            successCount++;
        }
        if (successCount > 0 && !findings.writesEntries()) {
            findings.putForAccount(accountId, region, stored);
        }
        return new ImportResult(successCount, failures);
    }

    public synchronized PaginatedResult<ObjectNode> getFindings(String region, JsonNode request) {
        requireEnabled(region);
        if (request == null || !request.isObject()) {
            throw invalid("GetFindings request must be a JSON object.");
        }
        if (request.has("SortCriteria")) {
            throw invalid("SortCriteria is not supported.");
        }
        SecurityHubRequestShapes.checkGetFindings(request);
        Map<String, List<StringPredicate>> filters = parseFilters(request.get("Filters"));
        Integer maxResults = Pagination.parseMaxResults(request, "MaxResults", "InvalidInputException");
        String nextToken = Pagination.parseNextToken(request, "NextToken", "InvalidInputException");
        if (nextToken != null && nextToken.isEmpty()) {
            throw invalid("NextToken is invalid.");
        }
        String accountId = regionResolver.getAccountId();
        List<ObjectNode> matches = new ArrayList<>();
        findings.getForAccount(accountId, region).ifPresent(stored -> stored.values().stream()
                .filter(finding -> matches(finding, filters)).map(ObjectNode::deepCopy).forEach(matches::add));
        String namespace = "securityhub-findings:" + scopeDigest(accountId, region, filters);
        return Pagination.paginate(matches,
                finding -> findingKey(finding.path("ProductArn").asText(), finding.path("Id").asText()),
                maxResults == null ? 100 : maxResults, nextToken, namespace,
                token -> invalid("NextToken is invalid."));
    }

    private ImportError validateFinding(JsonNode finding, String region, String accountId) {
        if (finding == null || !finding.isObject()) {
            return invalidFinding("Finding must be a JSON object.");
        }
        if (finding.toString().getBytes(StandardCharsets.UTF_8).length > MAX_FINDING_BYTES) {
            return invalidFinding("Finding exceeds the 240 KB size limit.");
        }
        try {
            SecurityHubRequestShapes.checkFinding(finding);
        } catch (AwsException e) {
            return invalidFinding(e.getMessage());
        }
        if (!"2018-10-08".equals(text(finding, "SchemaVersion"))) {
            return invalidFinding("SchemaVersion must be 2018-10-08.");
        }
        for (Map.Entry<String, Integer> field : Map.of("Id", 512, "ProductArn", 2048,
                "GeneratorId", 512, "Title", 256, "Description", 1024).entrySet()) {
            String value = text(finding, field.getKey());
            if (value.length() > field.getValue()) {
                return invalidFinding(field.getKey() + " must not exceed " + field.getValue() + " characters.");
            }
        }
        for (String field : List.of("ProductName", "CompanyName")) {
            if (finding.has(field)) {
                String value = text(finding, field);
                if (value == null || value.length() > 128) {
                    return invalidFinding(field + " must contain between 1 and 128 characters.");
                }
            }
        }
        String findingAccount = text(finding, "AwsAccountId");
        if (findingAccount == null || !findingAccount.matches("\\d{12}")) {
            return invalidFinding("AwsAccountId must be a 12 digit account ID.");
        }
        if (!accountId.equals(findingAccount)) {
            return new ImportError("InvalidAccess", "AwsAccountId must match the caller account for the default product.");
        }
        String defaultProductArn = regionResolver.buildArn("securityhub", region,
                "product/" + accountId + "/default");
        if (!defaultProductArn.equals(text(finding, "ProductArn"))) {
            return new ImportError("InvalidAccess", "ProductArn must be the caller's default product ARN in this Region.");
        }
        for (String field : List.of("CreatedAt", "UpdatedAt")) {
            String timestamp = text(finding, field);
            if (timestamp == null || timestamp.isBlank()) {
                return invalidFinding(field + " is required.");
            }
            try {
                Instant.parse(timestamp);
            } catch (DateTimeParseException e) {
                return invalidFinding(field + " must be an ISO 8601 timestamp.");
            }
        }
        JsonNode resources = finding.get("Resources");
        if (resources == null || !resources.isArray() || resources.isEmpty() || resources.size() > 32) {
            return invalidFinding("Resources must contain between 1 and 32 resources.");
        }
        JsonNode compliance = finding.get("Compliance");
        if (compliance != null) {
            if (!compliance.isObject()) {
                return invalidFinding("Compliance must be a JSON object.");
            }
            if (compliance.has("Status") && text(compliance, "Status") == null) {
                return invalidFinding("Compliance.Status must be a valid compliance status.");
            }
        }
        if (finding.has("RecordState") && text(finding, "RecordState") == null) {
            return invalidFinding("RecordState must be a valid record state.");
        }
        JsonNode providerFields = finding.path("FindingProviderFields");
        if (!providerFields.isMissingNode() && !providerFields.isObject()) {
            return invalidFinding("FindingProviderFields must be a JSON object.");
        }
        for (JsonNode severity : new JsonNode[] {finding.get("Severity"), providerFields.get("Severity")}) {
            if (severity != null) {
                if (!severity.isObject()) {
                    return invalidFinding("Severity must be a JSON object.");
                }
                if (!severity.has("Label") && !severity.has("Normalized")) {
                    return invalidFinding("Severity requires Label or Normalized.");
                }
                if (severity.has("Label")) {
                    String label = text(severity, "Label");
                    if (label == null) {
                        return invalidFinding("Severity.Label must be a valid severity label.");
                    }
                }
                if (severity.has("Normalized") && (!severity.path("Normalized").isIntegralNumber()
                        || !severity.path("Normalized").canConvertToInt()
                        || severity.path("Normalized").intValue() < 0
                        || severity.path("Normalized").intValue() > 100)) {
                    return invalidFinding("Severity.Normalized must be between 0 and 100.");
                }
            }
        }
        for (JsonNode types : new JsonNode[] {finding.get("Types"), providerFields.get("Types")}) {
            if (types != null) {
                if (!types.isArray() || types.isEmpty() || types.size() > 50) {
                    return invalidFinding("Types must contain between 1 and 50 finding types.");
                }
            }
        }
        return null;
    }

    private void addManagedFields(ObjectNode imported, ObjectNode previous, String region) {
        JsonNode providerFields = imported.path("FindingProviderFields");
        ObjectNode provider = providerFields.isObject() ? (ObjectNode) providerFields : null;
        for (String field : PROVIDER_FIELDS) {
            if (provider != null && provider.has(field) && (previous == null || !imported.has(field))) {
                imported.set(field, provider.get(field).deepCopy());
            } else if (imported.has(field)) {
                if (provider == null) {
                    provider = imported.putObject("FindingProviderFields");
                }
                if (!provider.has(field)) {
                    provider.set(field, imported.get(field).deepCopy());
                }
            }
        }
        if (imported.path("Severity").isObject()) {
            normalizeSeverity((ObjectNode) imported.get("Severity"));
        }
        if (provider != null && provider.path("Severity").isObject()) {
            normalizeSeverity((ObjectNode) provider.get("Severity"));
        }
        if (previous != null) {
            for (String field : INVESTIGATION_FIELDS) {
                JsonNode old = previous.get(field);
                if (old == null) {
                    imported.remove(field);
                } else {
                    imported.set(field, old.deepCopy());
                }
            }
        }
        if (!imported.has("ProductName")) {
            imported.put("ProductName", previous == null ? "Default" : previous.path("ProductName").asText("Default"));
        }
        if (!imported.has("CompanyName")) {
            imported.put("CompanyName", previous == null ? "Personal" : previous.path("CompanyName").asText("Personal"));
        }
        imported.put("Region", region);
        if (!imported.hasNonNull("RecordState")) {
            imported.put("RecordState", "ACTIVE");
        }
        if (previous == null) {
            imported.putObject("Workflow").put("Status", "NEW");
        }
    }

    private static void normalizeSeverity(ObjectNode severity) {
        if (!severity.has("Normalized")) {
            int normalized = switch (severity.path("Label").asText()) {
                case "INFORMATIONAL" -> 0;
                case "LOW" -> 1;
                case "MEDIUM" -> 40;
                case "HIGH" -> 70;
                case "CRITICAL" -> 90;
                default -> -1;
            };
            if (normalized >= 0) {
                severity.put("Normalized", normalized);
            }
        } else if (!severity.has("Label")) {
            int normalized = severity.path("Normalized").asInt();
            String label = normalized == 0 ? "INFORMATIONAL" : normalized < 40 ? "LOW"
                    : normalized < 70 ? "MEDIUM" : normalized < 90 ? "HIGH" : "CRITICAL";
            severity.put("Label", label);
        }
    }

    private static Map<String, List<StringPredicate>> parseFilters(JsonNode input) {
        Map<String, List<StringPredicate>> filters = new TreeMap<>();
        if (input == null || input.isNull()) {
            return filters;
        }
        if (!input.isObject() || input.size() > 10) {
            throw invalid("Filters must contain at most 10 finding attributes.");
        }
        input.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            JsonNode values = entry.getValue();
            if (!FILTER_FIELDS.contains(field)) {
                throw invalid("Filter " + field + " is not supported.");
            }
            if (!values.isArray() || values.isEmpty() || values.size() > 20) {
                throw invalid(field + " must contain between 1 and 20 filter values.");
            }
            List<StringPredicate> predicates = new ArrayList<>();
            boolean equals = false;
            boolean negative = false;
            for (JsonNode value : values) {
                String text = text(value, "Value");
                String comparison = value.has("Comparison") ? text(value, "Comparison") : "EQUALS";
                if (text == null || text.isEmpty() || !List.of("EQUALS", "PREFIX", "NOT_EQUALS")
                        .contains(comparison)) {
                    throw invalid(field + " requires a nonempty Value and EQUALS, PREFIX, or NOT_EQUALS.");
                }
                equals |= "EQUALS".equals(comparison);
                negative |= "NOT_EQUALS".equals(comparison);
                predicates.add(new StringPredicate(text, comparison));
            }
            if (equals && negative) {
                throw invalid(field + " cannot combine EQUALS with NOT_EQUALS.");
            }
            filters.put(field, predicates);
        });
        return filters;
    }

    private static boolean matches(ObjectNode finding, Map<String, List<StringPredicate>> filters) {
        for (Map.Entry<String, List<StringPredicate>> filter : filters.entrySet()) {
            List<String> values = valuesFor(finding, filter.getKey());
            if (values.isEmpty()) {
                return false;
            }
            boolean hasPositive = false;
            boolean positiveMatched = false;
            for (StringPredicate predicate : filter.getValue()) {
                if ("NOT_EQUALS".equals(predicate.comparison())) {
                    if (values.contains(predicate.value())) {
                        return false;
                    }
                } else {
                    hasPositive = true;
                    positiveMatched |= values.stream().anyMatch(value -> "EQUALS".equals(predicate.comparison())
                            ? value.equals(predicate.value()) : value.startsWith(predicate.value()));
                }
            }
            if (hasPositive && !positiveMatched) {
                return false;
            }
        }
        return true;
    }

    private static List<String> valuesFor(ObjectNode finding, String field) {
        if ("ComplianceAssociatedStandardsId".equals(field)) {
            List<String> values = new ArrayList<>();
            JsonNode associated = finding.path("Compliance").path("AssociatedStandards");
            if (associated.isArray()) {
                for (JsonNode standard : associated) {
                    addText(values, standard.path("StandardsId"));
                }
            }
            return values;
        }
        JsonNode value = switch (field) {
            case "WorkflowStatus" -> finding.path("Workflow").path("Status");
            case "ComplianceStatus" -> finding.path("Compliance").path("Status");
            case "ComplianceSecurityControlId" -> finding.path("Compliance").path("SecurityControlId");
            default -> finding.path(field);
        };
        List<String> values = new ArrayList<>();
        addText(values, value);
        return values;
    }

    private static void addText(List<String> values, JsonNode node) {
        if (node.isTextual()) {
            values.add(node.textValue());
        }
    }

    private static String scopeDigest(String accountId, String region,
                                      Map<String, List<StringPredicate>> filters) {
        StringBuilder scope = new StringBuilder(accountId).append(':').append(region);
        filters.forEach((field, predicates) -> {
            scope.append(':').append(field.length()).append(':').append(field);
            for (StringPredicate predicate : predicates) {
                scope.append(':').append(predicate.comparison()).append(':')
                        .append(predicate.value().length()).append(':').append(predicate.value());
            }
        });
        return Base64Url.encode(Digests.sha256(scope.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String findingKey(String productArn, String id) {
        return Base64Url.encode(productArn + "\u0000" + id);
    }

    private void requireEnabled(String region) {
        if (!securityHubService.state(region).isEnabled()) {
            throw new AwsException("InvalidAccessException", "Security Hub is not enabled for this account.", 401);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private static ImportError invalidFinding(String message) {
        return new ImportError("InvalidInput", message);
    }

    @Override
    public void clear() {
        findings.clear();
    }

    public record ImportFailure(String id, String errorCode, String errorMessage) {}

    public record ImportResult(int successCount, List<ImportFailure> failedFindings) {}

    private record ImportError(String code, String message) {}

    private record StringPredicate(String value, String comparison) {}
}
