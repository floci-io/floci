package io.github.hectorvent.floci.services.resourceexplorer2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public record Index(
        String arn,
        String region,
        IndexType type,
        IndexState state,
        Map<String, String> tags,
        Instant createdAt,
        Instant lastUpdatedAt,
        List<String> replicatingFrom,
        List<String> replicatingTo) implements Taggable {

    /** New index: lastUpdatedAt tracks createdAt, replication lists start empty. */
    public Index(String arn, String region, IndexType type, IndexState state,
                 Map<String, String> tags, Instant createdAt) {
        this(arn, region, type, state, tags, createdAt, createdAt, List.of(), List.of());
    }

    public Index withState(IndexState state) {
        return new Index(arn, region, type, state, tags, createdAt, lastUpdatedAt, replicatingFrom, replicatingTo);
    }

    public Index withType(IndexType type) {
        return new Index(arn, region, type, state, tags, createdAt, lastUpdatedAt, replicatingFrom, replicatingTo);
    }

    public Index withLastUpdatedAt(Instant lastUpdatedAt) {
        return new Index(arn, region, type, state, tags, createdAt, lastUpdatedAt, replicatingFrom, replicatingTo);
    }

    @Override
    public Index withTags(Map<String, String> tags) {
        return new Index(arn, region, type, state, tags, createdAt, lastUpdatedAt, replicatingFrom, replicatingTo);
    }
}
