package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.accessanalyzer.model.Analyzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class AccessAnalyzerService implements Resettable {
    private static final Pattern ANALYZER_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Set<String> ANALYZER_TYPES = Set.of(
            "ACCOUNT",
            "ORGANIZATION",
            "ACCOUNT_UNUSED_ACCESS",
            "ORGANIZATION_UNUSED_ACCESS",
            "ACCOUNT_INTERNAL_ACCESS",
            "ORGANIZATION_INTERNAL_ACCESS");

    private final AccountAwareStorageBackend<Analyzer> analyzers;
    private final RegionResolver regionResolver;

    @Inject
    public AccessAnalyzerService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.analyzers = storageFactory.create("accessanalyzer", "accessanalyzer-analyzers.json",
                new TypeReference<Map<String, Analyzer>>() {});
        this.regionResolver = regionResolver;
    }

    public synchronized Analyzer createAnalyzer(JsonNode request, String region) {
        String name = requireAnalyzerName(request);
        String type = requireAnalyzerType(text(request, "type"));
        String key = storageKey(region, name);
        if (analyzers.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "An analyzer with the specified name already exists.", 409);
        }

        List<Analyzer> inRegion = analyzers.scan(candidate -> candidate.startsWith(region + "::"));
        long sameType = inRegion.stream().filter(analyzer -> type.equals(analyzer.getType())).count();
        if (sameType >= analyzerLimit(type)) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The analyzer quota for this account or organization in the Region has been exceeded.", 402);
        }

        Analyzer analyzer = new Analyzer();
        analyzer.setName(name);
        analyzer.setType(type);
        analyzer.setStatus("ACTIVE");
        analyzer.setCreatedAt(Instant.now().toString());
        analyzer.setArn(regionResolver.buildArn("access-analyzer", region, "analyzer/" + name));
        analyzer.setTags(readTags(request.get("tags")));
        if (request.hasNonNull("configuration")) {
            if (!request.get("configuration").isObject()) {
                throw validation("configuration must be an object.");
            }
            analyzer.setConfiguration(request.get("configuration").deepCopy());
        }
        analyzers.put(key, analyzer);
        return analyzer;
    }

    public PaginatedResult<Analyzer> listAnalyzers(String region, String type, Integer maxResults, String nextToken) {
        String requestedType = type == null || type.isBlank() ? null : requireAnalyzerType(type);
        List<Analyzer> matching = analyzers.scan(key -> key.startsWith(region + "::")).stream()
                .filter(analyzer -> requestedType == null || requestedType.equals(analyzer.getType()))
                .toList();
        return Pagination.paginate(matching, Analyzer::getName, maxResults, nextToken,
                100, 1000, "ValidationException");
    }

    public synchronized void deleteAnalyzer(String region, String analyzerName) {
        validateAnalyzerName(analyzerName);
        String key = storageKey(region, analyzerName);
        if (analyzers.get(key).isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified analyzer could not be found.", 404);
        }
        analyzers.delete(key);
    }

    private static int analyzerLimit(String type) {
        return switch (type) {
            case "ORGANIZATION", "ORGANIZATION_UNUSED_ACCESS" -> 5;
            case "ACCOUNT", "ACCOUNT_UNUSED_ACCESS", "ACCOUNT_INTERNAL_ACCESS",
                    "ORGANIZATION_INTERNAL_ACCESS" -> 1;
            default -> throw new IllegalArgumentException("Unsupported analyzer type: " + type);
        };
    }

    @Override
    public void clear() {
        analyzers.clear();
    }

    private static String requireAnalyzerName(JsonNode request) {
        String value = text(request, "analyzerName");
        validateAnalyzerName(value);
        return value;
    }

    private static void validateAnalyzerName(String value) {
        if (value == null || value.length() < 1 || value.length() > 255 || !ANALYZER_NAME.matcher(value).matches()) {
            throw validation("analyzerName must be 1-255 characters and match [A-Za-z][A-Za-z0-9_.-]*.");
        }
    }

    private static String requireAnalyzerType(String value) {
        if (value == null || !ANALYZER_TYPES.contains(value)) {
            throw validation("type must be a valid analyzer type.");
        }
        return value;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isObject()) {
            throw validation("tags must be an object.");
        }
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (key.length() < 1 || key.length() > 128 || key.startsWith("aws:") || !value.isTextual()
                    || value.textValue().length() > 256) {
                throw validation("tags contain an invalid key or value.");
            }
            tags.put(key, value.textValue());
        });
        return tags;
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static String storageKey(String region, String analyzerName) {
        return region + "::" + analyzerName;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
