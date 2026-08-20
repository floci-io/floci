package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.accessanalyzer.model.Analyzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core IAM Access Analyzer logic — analyzers only (findings/archive rules are not modeled;
 * floci runs no real analysis, so an analyzer never produces any). Backs
 * {@code aws_accessanalyzer_analyzer} (lex00/floci#75): before this service existed there was
 * no AccessAnalyzer package anywhere in floci, so CreateAnalyzer 404'd outright.
 *
 * <p>Region-scoped like Backup: an analyzer's ARN carries a region, so storage keys are
 * {@code region/name} rather than relying on {@link StorageFactory}'s automatic (account-only)
 * namespacing to separate them.
 */
@ApplicationScoped
public class AccessAnalyzerService {

    private static final Logger LOG = Logger.getLogger(AccessAnalyzerService.class);
    private static final String DEFAULT_TYPE = "ACCOUNT";

    private final StorageBackend<String, Analyzer> analyzerStore;
    private final RegionResolver regionResolver;

    @Inject
    public AccessAnalyzerService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.analyzerStore = storageFactory.create("accessanalyzer", "accessanalyzer-analyzers.json",
                new TypeReference<>() {});
        this.regionResolver = regionResolver;
    }

    public Analyzer createAnalyzer(String name, String type, Map<String, String> tags, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "analyzerName must not be empty.", 400);
        }
        String key = analyzerKey(region, name);
        if (analyzerStore.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "An analyzer with the name " + name + " already exists.", 409);
        }
        Analyzer analyzer = new Analyzer();
        analyzer.setName(name);
        analyzer.setType(type == null || type.isBlank() ? DEFAULT_TYPE : type);
        analyzer.setArn(regionResolver.buildArn("access-analyzer", region, "analyzer/" + name));
        analyzer.setCreatedAt(Instant.now());
        analyzer.setStatus("ACTIVE");
        analyzer.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        analyzerStore.put(key, analyzer);
        LOG.infov("Created Access Analyzer analyzer: {0} ({1})", name, analyzer.getType());
        return analyzer;
    }

    public Analyzer getAnalyzer(String name, String region) {
        return analyzerStore.get(analyzerKey(region, name))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Analyzer " + name + " could not be found.", 404));
    }

    public List<Analyzer> listAnalyzers(String region, String type) {
        return analyzerStore.scan(k -> true).stream()
                .filter(a -> type == null || type.isBlank() || type.equals(a.getType()))
                .toList();
    }

    public void deleteAnalyzer(String name, String region) {
        String key = analyzerKey(region, name);
        if (analyzerStore.get(key).isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "Analyzer " + name + " could not be found.", 404);
        }
        analyzerStore.delete(key);
        LOG.infov("Deleted Access Analyzer analyzer: {0}", name);
    }

    // ── Tagging (dispatched generically by SharedTagsController via AccessAnalyzerTagHandler) ──

    public Map<String, String> listTags(String arn) {
        return findByArn(arn).getTags();
    }

    public void tagResource(String arn, Map<String, String> tags) {
        Analyzer analyzer = findByArn(arn);
        analyzer.getTags().putAll(tags);
        analyzerStore.put(analyzerKey(analyzer), analyzer);
    }

    public void untagResource(String arn, List<String> tagKeys) {
        Analyzer analyzer = findByArn(arn);
        tagKeys.forEach(analyzer.getTags()::remove);
        analyzerStore.put(analyzerKey(analyzer), analyzer);
    }

    private Analyzer findByArn(String arn) {
        return analyzerStore.scan(k -> true).stream()
                .filter(a -> arn.equals(a.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + arn, 404));
    }

    private static String analyzerKey(String region, String name) {
        return region + "/" + name;
    }

    private static String analyzerKey(Analyzer analyzer) {
        // The analyzer's own ARN always carries the region it was created in
        // (arn:aws:access-analyzer:<region>:<account>:analyzer/<name>), so this reconstructs
        // the same key createAnalyzer used without needing a separate region field on the model.
        String arn = analyzer.getArn();
        String[] parts = arn.split(":", 6);
        String region = parts.length > 3 ? parts[3] : "";
        return analyzerKey(region, analyzer.getName());
    }
}
