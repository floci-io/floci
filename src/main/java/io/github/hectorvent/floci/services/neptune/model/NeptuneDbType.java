package io.github.hectorvent.floci.services.neptune.model;

import java.util.Optional;

/**
 * Backend graph database engine used to emulate a Neptune cluster.
 *
 * <p>Amazon Neptune exposes several query languages over its data-plane endpoint. Floci backs
 * each one with a different container image and proxies the matching wire protocol:
 * <ul>
 *   <li>{@link #GREMLIN} — Apache TinkerPop Gremlin Server. Gremlin traversals over a
 *       WebSocket on port 8182 (Neptune's native default).</li>
 *   <li>{@link #NEO4J} — Neo4j. openCypher queries over the Bolt protocol on port 7687,
 *       matching Neptune's openCypher / Bolt endpoint.</li>
 * </ul>
 *
 * <p>The engine is selected globally via {@code FLOCI_SERVICES_NEPTUNE_DB_TYPE}, mirroring
 * LocalStack's {@code NEPTUNE_DB_TYPE} toggle.
 */
public enum NeptuneDbType {

    GREMLIN(8182),
    NEO4J(7687);

    private final int backendPort;

    NeptuneDbType(int backendPort) {
        this.backendPort = backendPort;
    }

    /** Container port the backend listens on for its native data-plane protocol. */
    public int backendPort() {
        return backendPort;
    }

    /**
     * Resolves a configured db-type string (case-insensitive) to an engine.
     * Empty / null falls back to {@link #GREMLIN} to preserve historical behaviour.
     * An unrecognised value returns {@link Optional#empty()} so the caller can fall back
     * to the default rather than failing the client's request over a server-side typo.
     */
    public static Optional<NeptuneDbType> fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(GREMLIN);
        }
        return switch (value.trim().toLowerCase()) {
            case "gremlin", "tinkerpop" -> Optional.of(GREMLIN);
            case "neo4j", "opencypher", "cypher", "bolt" -> Optional.of(NEO4J);
            default -> Optional.empty();
        };
    }
}
