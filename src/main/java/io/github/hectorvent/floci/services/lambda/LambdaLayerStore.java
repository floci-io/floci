package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wraps the storage backend for Lambda Layer Versions.
 * Keys follow the pattern: layer::{region}::{layerName}::{version}
 */
@ApplicationScoped
public class LambdaLayerStore {

    private final StorageBackend<String, LambdaLayerVersion> backend;

    @Inject
    public LambdaLayerStore(StorageFactory storageFactory) {
        this.backend = storageFactory.create("lambda", "lambda-layers.json",
                new TypeReference<Map<String, LambdaLayerVersion>>() {});
    }

    LambdaLayerStore(StorageBackend<String, LambdaLayerVersion> backend) {
        this.backend = backend;
    }

    public void save(String region, LambdaLayerVersion layerVersion) {
        backend.put(key(region, layerVersion.getLayerName(), layerVersion.getVersion()), layerVersion);
    }

    public Optional<LambdaLayerVersion> get(String region, String layerName, long version) {
        return backend.get(key(region, layerName, version));
    }

    /**
     * Returns all versions of a given layer in a region, sorted by version ascending.
     */
    public List<LambdaLayerVersion> listVersions(String region, String layerName) {
        String prefix = "layer::" + region + "::" + layerName + "::";
        return backend.scan(k -> k.startsWith(prefix)).stream()
                .sorted((a, b) -> Long.compare(a.getVersion(), b.getVersion()))
                .toList();
    }

    /**
     * Returns the latest version number for a layer, or 0 if no versions exist.
     */
    public long getLatestVersion(String region, String layerName) {
        return listVersions(region, layerName).stream()
                .mapToLong(LambdaLayerVersion::getVersion)
                .max()
                .orElse(0);
    }

    /**
     * Returns all distinct layers in a region (latest version of each).
     */
    public List<LambdaLayerVersion> listLayers(String region) {
        String prefix = "layer::" + region + "::";
        List<LambdaLayerVersion> all = backend.scan(k -> k.startsWith(prefix));
        // Group by layer name and return the latest version of each
        return all.stream()
                .collect(java.util.stream.Collectors.groupingBy(LambdaLayerVersion::getLayerName))
                .values().stream()
                .map(versions -> versions.stream()
                        .max((a, b) -> Long.compare(a.getVersion(), b.getVersion()))
                        .orElseThrow())
                .toList();
    }

    public void delete(String region, String layerName, long version) {
        backend.delete(key(region, layerName, version));
    }

    /**
     * Deletes all versions of a layer.
     */
    public void deleteAll(String region, String layerName) {
        listVersions(region, layerName).forEach(lv ->
                backend.delete(key(region, lv.getLayerName(), lv.getVersion())));
    }

    private static String key(String region, String layerName, long version) {
        return "layer::" + region + "::" + layerName + "::" + version;
    }
}
