package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An Iceberg table's metadata as the REST catalog keeps it: the JSON of the table metadata file, changed only by the
 * requirements and updates a {@code CommitTableRequest} carries, as the Apache Iceberg REST catalog specification
 * defines them. The writer (DuckDB, Spark) writes the data files, manifests and manifest lists itself; the catalog
 * checks what the commit assumes and records what it adds, so no Iceberg library is needed.
 */
final class IcebergTableMetadata {

    static final int DEFAULT_FORMAT_VERSION = 2;
    /** The last partition field id of a table with no partition fields yet, as Iceberg starts it. */
    private static final int NO_PARTITION_FIELDS = 999;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private IcebergTableMetadata() {
    }

    /**
     * The metadata a table starts with before a staged create's updates apply: nothing but the format version and
     * the empty lists and maps every later update fills.
     */
    static ObjectNode empty(long nowMillis) {
        ObjectNode metadata = JSON.objectNode();
        metadata.put("format-version", DEFAULT_FORMAT_VERSION);
        metadata.put("table-uuid", UUID.randomUUID().toString());
        metadata.put("last-sequence-number", 0);
        metadata.put("last-updated-ms", nowMillis);
        metadata.put("last-column-id", 0);
        metadata.put("current-schema-id", -1);
        metadata.set("schemas", JSON.arrayNode());
        metadata.put("default-spec-id", -1);
        metadata.set("partition-specs", JSON.arrayNode());
        metadata.put("last-partition-id", NO_PARTITION_FIELDS);
        metadata.put("default-sort-order-id", -1);
        metadata.set("sort-orders", JSON.arrayNode());
        metadata.set("properties", JSON.objectNode());
        metadata.set("refs", JSON.objectNode());
        metadata.set("snapshots", JSON.arrayNode());
        metadata.set("snapshot-log", JSON.arrayNode());
        metadata.set("metadata-log", JSON.arrayNode());
        return metadata;
    }

    /**
     * The metadata a {@code CreateTableRequest} describes, as the updates a client would send for it: its schema,
     * partition spec, sort order, location and properties, ids assigned as given.
     */
    static ObjectNode forCreate(JsonNode request, String location, long nowMillis) {
        ObjectNode metadata = empty(nowMillis);
        JsonNode properties = request.path("properties");
        int formatVersion = properties.path("format-version").asInt(DEFAULT_FORMAT_VERSION);
        ArrayNode updates = JSON.arrayNode();
        updates.addObject().put("action", "upgrade-format-version").put("format-version", formatVersion);
        ObjectNode addSchema = updates.addObject().put("action", "add-schema");
        addSchema.set("schema", request.path("schema"));
        updates.addObject().put("action", "set-current-schema").put("schema-id", -1);
        ObjectNode spec = request.hasNonNull("partition-spec") ? ((ObjectNode) request.get("partition-spec")).deepCopy()
                : JSON.objectNode().put("spec-id", 0).set("fields", JSON.arrayNode());
        spec.remove("type");
        updates.addObject().put("action", "add-spec").set("spec", spec);
        updates.addObject().put("action", "set-default-spec").put("spec-id", -1);
        JsonNode order = request.hasNonNull("write-order") ? request.get("write-order")
                : JSON.objectNode().put("order-id", 0).set("fields", JSON.arrayNode());
        updates.addObject().put("action", "add-sort-order").set("sort-order", order);
        updates.addObject().put("action", "set-default-sort-order").put("sort-order-id", -1);
        updates.addObject().put("action", "set-location").put("location", location);
        if (properties.isObject() && !properties.isEmpty()) {
            updates.addObject().put("action", "set-properties").set("updates", properties);
        }
        apply(metadata, updates, nowMillis);
        return metadata;
    }

    /**
     * Checks a commit's requirements against the table's current metadata, {@code null} for a table that does not
     * exist yet.
     *
     * @throws IcebergRestException a {@code CommitFailedException} naming the first requirement that does not hold
     */
    static void check(JsonNode metadata, JsonNode requirements) {
        for (JsonNode requirement : requirements) {
            String type = requirement.path("type").asText();
            if ("assert-create".equals(type)) {
                if (metadata != null) {
                    throw commitFailed("Requirement failed: table already exists");
                }
                continue;
            }
            if (metadata == null) {
                throw commitFailed("Requirement failed: table does not exist (" + type + ")");
            }
            switch (type) {
                case "assert-table-uuid" -> requireEqual(type, metadata.path("table-uuid").asText(),
                        requirement.path("uuid").asText());
                case "assert-ref-snapshot-id" -> {
                    JsonNode ref = metadata.path("refs").path(requirement.path("ref").asText());
                    JsonNode expected = requirement.path("snapshot-id");
                    if (expected.isNull() || expected.isMissingNode()) {
                        if (!ref.isMissingNode()) {
                            throw commitFailed("Requirement failed: ref " + requirement.path("ref").asText()
                                    + " was created concurrently");
                        }
                    } else if (ref.isMissingNode() || ref.path("snapshot-id").asLong() != expected.asLong()) {
                        throw commitFailed("Requirement failed: ref " + requirement.path("ref").asText()
                                + " has changed: expected id " + expected.asLong() + " != "
                                + (ref.isMissingNode() ? "null" : ref.path("snapshot-id").asLong()));
                    }
                }
                case "assert-last-assigned-field-id" -> requireEqual(type, metadata.path("last-column-id").asLong(),
                        requirement.path("last-assigned-field-id").asLong());
                case "assert-current-schema-id" -> requireEqual(type, metadata.path("current-schema-id").asLong(),
                        requirement.path("current-schema-id").asLong());
                case "assert-last-assigned-partition-id" -> requireEqual(type,
                        metadata.path("last-partition-id").asLong(),
                        requirement.path("last-assigned-partition-id").asLong());
                case "assert-default-spec-id" -> requireEqual(type, metadata.path("default-spec-id").asLong(),
                        requirement.path("default-spec-id").asLong());
                case "assert-default-sort-order-id" -> requireEqual(type,
                        metadata.path("default-sort-order-id").asLong(),
                        requirement.path("default-sort-order-id").asLong());
                default -> throw IcebergRestException.badRequest("Unsupported requirement: " + type);
            }
        }
    }

    /**
     * Applies a commit's updates to {@code metadata} in order, as the specification defines each action, and stamps
     * {@code last-updated-ms}.
     *
     * @throws IcebergRestException a {@code BadRequestException} for an action this catalog does not apply
     */
    static void apply(ObjectNode metadata, JsonNode updates, long nowMillis) {
        // An id of -1 in set-current-schema, set-default-spec and set-default-sort-order names the last one added.
        int lastAddedSchema = -1;
        int lastAddedSpec = -1;
        int lastAddedOrder = -1;
        for (JsonNode update : updates) {
            String action = update.path("action").asText();
            switch (action) {
                case "assign-uuid" -> metadata.put("table-uuid", update.path("uuid").asText());
                case "upgrade-format-version" -> {
                    int version = update.path("format-version").asInt();
                    if (version < metadata.path("format-version").asInt(DEFAULT_FORMAT_VERSION)
                            && !metadata.path("schemas").isEmpty()) {
                        throw IcebergRestException.badRequest("Cannot downgrade format version to " + version);
                    }
                    metadata.put("format-version", version);
                    if (version >= 3 && !metadata.has("next-row-id")) {
                        metadata.put("next-row-id", 0);
                    }
                }
                case "add-schema" -> {
                    ObjectNode schema = ((ObjectNode) update.path("schema")).deepCopy();
                    lastAddedSchema = reuseOrAdd(metadata.withArray("schemas"), "schema-id", schema);
                    long lastColumnId = update.has("last-column-id") ? update.path("last-column-id").asLong()
                            : Math.max(metadata.path("last-column-id").asLong(), maxFieldId(schema));
                    metadata.put("last-column-id", Math.max(metadata.path("last-column-id").asLong(), lastColumnId));
                }
                case "set-current-schema" -> metadata.put("current-schema-id",
                        existing(metadata.path("schemas"), "schema-id", update.path("schema-id").asInt(),
                                lastAddedSchema, "schema"));
                case "add-spec" -> {
                    ObjectNode spec = ((ObjectNode) update.path("spec")).deepCopy();
                    spec.remove("type");
                    lastAddedSpec = reuseOrAdd(metadata.withArray("partition-specs"), "spec-id", spec);
                    long maxField = NO_PARTITION_FIELDS;
                    for (JsonNode field : spec.path("fields")) {
                        maxField = Math.max(maxField, field.path("field-id").asLong());
                    }
                    metadata.put("last-partition-id", Math.max(metadata.path("last-partition-id").asLong(), maxField));
                }
                case "set-default-spec" -> metadata.put("default-spec-id",
                        existing(metadata.path("partition-specs"), "spec-id", update.path("spec-id").asInt(),
                                lastAddedSpec, "partition spec"));
                case "add-sort-order" -> {
                    ObjectNode order = ((ObjectNode) update.path("sort-order")).deepCopy();
                    lastAddedOrder = reuseOrAdd(metadata.withArray("sort-orders"), "order-id", order);
                }
                case "set-default-sort-order" -> metadata.put("default-sort-order-id",
                        existing(metadata.path("sort-orders"), "order-id", update.path("sort-order-id").asInt(),
                                lastAddedOrder, "sort order"));
                case "add-snapshot" -> addSnapshot(metadata, (ObjectNode) update.path("snapshot"));
                case "set-snapshot-ref" -> setRef(metadata, update, nowMillis);
                case "remove-snapshot-ref" -> removeRef(metadata, update.path("ref-name").asText());
                case "remove-snapshots" -> removeSnapshots(metadata, update.path("snapshot-ids"));
                case "set-location" -> metadata.put("location", update.path("location").asText());
                case "set-properties" -> {
                    ObjectNode properties = metadata.withObject("properties");
                    Iterator<Map.Entry<String, JsonNode>> fields = update.path("updates").fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        properties.put(entry.getKey(), entry.getValue().asText());
                    }
                }
                case "remove-properties" -> {
                    ObjectNode properties = metadata.withObject("properties");
                    for (JsonNode key : update.path("removals")) {
                        properties.remove(key.asText());
                    }
                }
                case "set-statistics" -> replaceById(metadata.withArray("statistics"), "snapshot-id",
                        update.path("statistics").deepCopy());
                case "remove-statistics" -> removeById(metadata.withArray("statistics"), "snapshot-id",
                        update.path("snapshot-id").asLong());
                case "set-partition-statistics" -> replaceById(metadata.withArray("partition-statistics"),
                        "snapshot-id", update.path("partition-statistics").deepCopy());
                case "remove-partition-statistics" -> removeById(metadata.withArray("partition-statistics"),
                        "snapshot-id", update.path("snapshot-id").asLong());
                default -> throw IcebergRestException.badRequest("Unsupported update action: " + action);
            }
        }
        metadata.put("last-updated-ms", nowMillis);
    }

    /** The schema {@code current-schema-id} names, or an empty one for a table with none. */
    static JsonNode currentSchema(JsonNode metadata) {
        int current = metadata.path("current-schema-id").asInt();
        for (JsonNode schema : metadata.path("schemas")) {
            if (schema.path("schema-id").asInt() == current) {
                return schema;
            }
        }
        return JSON.objectNode().put("type", "struct").set("fields", JSON.arrayNode());
    }

    private static void addSnapshot(ObjectNode metadata, ObjectNode snapshot) {
        long id = snapshot.path("snapshot-id").asLong();
        for (JsonNode existing : metadata.withArray("snapshots")) {
            if (existing.path("snapshot-id").asLong() == id) {
                throw IcebergRestException.badRequest("Snapshot already exists: " + id);
            }
        }
        ObjectNode added = snapshot.deepCopy();
        int formatVersion = metadata.path("format-version").asInt(DEFAULT_FORMAT_VERSION);
        if (formatVersion >= 2) {
            long sequence = added.path("sequence-number").asLong();
            // As Iceberg checks it: a snapshot with a parent must come after every sequence number the table used,
            // whether or not the snapshots that used them were expired since.
            if (sequence <= metadata.path("last-sequence-number").asLong() && added.hasNonNull("parent-snapshot-id")) {
                throw IcebergRestException.badRequest("Cannot add snapshot with sequence number " + sequence
                        + " older than last sequence number " + metadata.path("last-sequence-number").asLong());
            }
            metadata.put("last-sequence-number", Math.max(sequence, metadata.path("last-sequence-number").asLong()));
        }
        if (formatVersion >= 3 && added.has("added-rows")) {
            long next = metadata.path("next-row-id").asLong();
            if (!added.has("first-row-id")) {
                added.put("first-row-id", next);
            }
            metadata.put("next-row-id", added.path("first-row-id").asLong() + added.path("added-rows").asLong());
        }
        metadata.withArray("snapshots").add(added);
    }

    private static void setRef(ObjectNode metadata, JsonNode update, long nowMillis) {
        String name = update.path("ref-name").asText();
        long snapshotId = update.path("snapshot-id").asLong();
        JsonNode snapshot = null;
        for (JsonNode existing : metadata.path("snapshots")) {
            if (existing.path("snapshot-id").asLong() == snapshotId) {
                snapshot = existing;
            }
        }
        if (snapshot == null) {
            throw IcebergRestException.badRequest("Cannot set " + name + " to unknown snapshot: " + snapshotId);
        }
        ObjectNode ref = JSON.objectNode();
        ref.put("snapshot-id", snapshotId);
        ref.put("type", update.path("type").asText("branch"));
        for (String retention : new String[] {"max-ref-age-ms", "max-snapshot-age-ms", "min-snapshots-to-keep"}) {
            if (update.hasNonNull(retention)) {
                ref.set(retention, update.get(retention));
            }
        }
        metadata.withObject("refs").set(name, ref);
        if ("main".equals(name)) {
            metadata.put("current-snapshot-id", snapshotId);
            metadata.withArray("snapshot-log").addObject()
                    .put("timestamp-ms", snapshot.path("timestamp-ms").asLong(nowMillis))
                    .put("snapshot-id", snapshotId);
        }
    }

    private static void removeSnapshots(ObjectNode metadata, JsonNode ids) {
        Set<Long> removed = new HashSet<>();
        for (JsonNode id : ids) {
            removed.add(id.asLong());
        }
        ArrayNode kept = JSON.arrayNode();
        for (JsonNode snapshot : metadata.withArray("snapshots")) {
            if (!removed.contains(snapshot.path("snapshot-id").asLong())) {
                kept.add(snapshot);
            }
        }
        metadata.set("snapshots", kept);
        // A ref to a removed snapshot goes with it, as Iceberg removes it, and main's takes the current snapshot.
        Iterator<Map.Entry<String, JsonNode>> refs = metadata.withObject("refs").fields();
        List<String> dangling = new ArrayList<>();
        while (refs.hasNext()) {
            Map.Entry<String, JsonNode> ref = refs.next();
            if (removed.contains(ref.getValue().path("snapshot-id").asLong())) {
                dangling.add(ref.getKey());
            }
        }
        dangling.forEach(name -> removeRef(metadata, name));
        ArrayNode log = JSON.arrayNode();
        for (JsonNode entry : metadata.withArray("snapshot-log")) {
            if (!removed.contains(entry.path("snapshot-id").asLong())) {
                log.add(entry);
            }
        }
        metadata.set("snapshot-log", log);
        // So do the removed snapshots' statistics files, as Iceberg's metadata builder drops them.
        for (String statistics : List.of("statistics", "partition-statistics")) {
            if (metadata.has(statistics)) {
                ArrayNode keptStatistics = JSON.arrayNode();
                for (JsonNode file : metadata.withArray(statistics)) {
                    if (!removed.contains(file.path("snapshot-id").asLong())) {
                        keptStatistics.add(file);
                    }
                }
                metadata.set(statistics, keptStatistics);
            }
        }
    }

    /**
     * Adds a schema, partition spec or sort order as Iceberg's catalog does: an identical one already there is
     * reused, anything else takes the next free id, so an id already in the table never comes to mean something
     * else. Answers the id it has.
     */
    private static int reuseOrAdd(ArrayNode array, String idField, ObjectNode element) {
        for (JsonNode existing : array) {
            ObjectNode withoutId = ((ObjectNode) existing).deepCopy();
            withoutId.remove(idField);
            ObjectNode candidate = element.deepCopy();
            candidate.remove(idField);
            if (withoutId.equals(candidate)) {
                return existing.path(idField).asInt();
            }
        }
        int id = nextId(array, idField);
        element.put(idField, id);
        array.add(element);
        return id;
    }

    /** The id an update names, -1 being the one this commit last added, refused when the table has none such. */
    private static int existing(JsonNode array, String idField, int id, int lastAdded, String what) {
        int resolved = id == -1 ? lastAdded : id;
        if (resolved == -1) {
            throw IcebergRestException.badRequest("Cannot set the last added " + what + ": none was added");
        }
        for (JsonNode element : array) {
            if (element.path(idField).asInt() == resolved) {
                return resolved;
            }
        }
        throw IcebergRestException.badRequest("Cannot set an unknown " + what + ": " + resolved);
    }

    /** Removes a ref; removing main leaves the table with no current snapshot. */
    private static void removeRef(ObjectNode metadata, String name) {
        metadata.withObject("refs").remove(name);
        if ("main".equals(name)) {
            metadata.put("current-snapshot-id", -1);
        }
    }

    private static void replaceById(ArrayNode array, String idField, JsonNode element) {
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).path(idField).asLong() == element.path(idField).asLong()) {
                array.set(i, element);
                return;
            }
        }
        array.add(element);
    }

    private static void removeById(ArrayNode array, String idField, long id) {
        for (int i = array.size() - 1; i >= 0; i--) {
            if (array.get(i).path(idField).asLong() == id) {
                array.remove(i);
            }
        }
    }

    private static int nextId(ArrayNode array, String idField) {
        int next = 0;
        for (JsonNode element : array) {
            next = Math.max(next, element.path(idField).asInt() + 1);
        }
        return next;
    }

    private static long maxFieldId(JsonNode type) {
        long max = 0;
        if (type.isObject()) {
            if (type.has("id")) {
                max = Math.max(max, type.path("id").asLong());
            }
            for (String key : new String[] {"element-id", "key-id", "value-id"}) {
                if (type.has(key)) {
                    max = Math.max(max, type.path(key).asLong());
                }
            }
            for (JsonNode child : type) {
                max = Math.max(max, maxFieldId(child));
            }
        } else if (type.isArray()) {
            for (JsonNode child : type) {
                max = Math.max(max, maxFieldId(child));
            }
        }
        return max;
    }

    private static void requireEqual(String type, Object actual, Object expected) {
        if (!actual.equals(expected)) {
            throw commitFailed("Requirement failed: " + type + " has changed: expected " + expected + " != " + actual);
        }
    }

    private static IcebergRestException commitFailed(String message) {
        return new IcebergRestException(409, "CommitFailedException", message);
    }
}
