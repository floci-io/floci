package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.Field;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.GeoLocation;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.ThingIndexing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AWS IoT fleet indexing configuration: UpdateIndexingConfiguration, GetIndexingConfiguration and
 * DescribeIndex. One configuration per account and region; a region never updated is OFF.
 */
@ApplicationScoped
public class IotFleetIndexingService {

    static final String THINGS_INDEX = "AWS_Things";
    static final String THING_GROUPS_INDEX = "AWS_ThingGroups";

    // Enum value sets in the order AWS prints them in its validation messages.
    private static final List<String> THING_INDEXING_MODES = List.of("REGISTRY_AND_SHADOW", "OFF", "REGISTRY");
    private static final List<String> CONNECTIVITY_MODES = List.of("OFF", "STATUS");
    private static final List<String> DEVICE_DEFENDER_MODES = List.of("VIOLATIONS", "OFF");
    private static final List<String> ON_OFF = List.of("OFF", "ON");
    private static final List<String> FIELD_TYPES = List.of("Boolean", "Number", "String");
    private static final List<String> SOCKET_INFORMATION_APIS = List.of("GET_THING_CONNECTIVITY_DATA");
    private static final List<String> GEO_LOCATION_ORDERS = List.of("LatLon", "LonLat");

    private static final List<Field> REGISTRY_FIELDS = List.of(
            new Field("thingName", "String"),
            new Field("thingId", "String"),
            new Field("registry.version", "Number"),
            new Field("registry.thingTypeName", "String"),
            new Field("registry.thingGroupNames", "String"));
    private static final List<Field> SHADOW_FIELDS = List.of(
            new Field("shadow.version", "Number"),
            new Field("shadow.hasDelta", "Boolean"));
    private static final List<Field> NAMED_SHADOW_FIELDS = List.of(
            new Field("shadow.name.*.hasDelta", "Boolean"),
            new Field("shadow.name.*.version", "Number"));
    private static final List<Field> CONNECTIVITY_FIELDS = List.of(
            new Field("connectivity.connected", "Boolean"),
            new Field("connectivity.timestamp", "Number"),
            new Field("connectivity.disconnectReason", "String"),
            new Field("connectivity.clientId", "String"),
            new Field("connectivity.cleanSession", "Boolean"),
            new Field("connectivity.keepAliveDuration", "Number"),
            new Field("connectivity.sessionExpiry", "Number"),
            new Field("connectivity.version", "Number"));
    private static final List<Field> DEVICE_DEFENDER_FIELDS = List.of(
            new Field("deviceDefender.version", "Number"),
            new Field("deviceDefender.violationCount", "Number"),
            new Field("deviceDefender.*.*.inViolation", "Boolean"),
            new Field("deviceDefender.*.*.lastViolationTime", "Number"),
            new Field("deviceDefender.*.*.metricName", "String"));
    private static final Set<Field> KNOWN_THING_FIELDS = Stream.of(REGISTRY_FIELDS, SHADOW_FIELDS,
                    NAMED_SHADOW_FIELDS, CONNECTIVITY_FIELDS, DEVICE_DEFENDER_FIELDS)
            .flatMap(List::stream)
            .collect(Collectors.toUnmodifiableSet());
    static final List<Field> THING_GROUP_MANAGED_FIELDS = List.of(
            new Field("parentGroupNames", "String"),
            new Field("description", "String"),
            new Field("version", "Number"),
            new Field("thingGroupName", "String"),
            new Field("thingGroupId", "String"));

    private final StorageBackend<String, IotIndexingConfiguration> store;
    /** Guards the read-modify-write of an update that changes only one of the two configurations. */
    private final Object lock = new Object();

    @Inject
    public IotFleetIndexingService(StorageFactory storageFactory) {
        this(storageFactory.create("iot", "iot-indexing-configuration.json",
                new TypeReference<Map<String, IotIndexingConfiguration>>() {}));
    }

    IotFleetIndexingService(StorageBackend<String, IotIndexingConfiguration> store) {
        this.store = store;
    }

    public void updateIndexingConfiguration(JsonNode request, String region) {
        if (!request.isObject()) {
            throw serializationError("request body", JsonNodeType.OBJECT);
        }
        String groupPrefix = "thingGroupIndexingConfiguration";
        JsonNode thingNode = object(request.path("thingIndexingConfiguration"), "thingIndexingConfiguration");
        JsonNode groupNode = object(request.path(groupPrefix), groupPrefix);
        if (!present(thingNode) && !present(groupNode)) {
            throw invalid("At least one configuration to update "
                    + "(thingIndexingConfiguration / thingGroupIndexingConfiguration) is required");
        }
        List<String> errors = new ArrayList<>();
        ThingIndexing thing = present(thingNode) ? parseThing(thingNode, errors) : null;
        List<Field> managedFields = fields(thingNode.path("managedFields"),
                "thingIndexingConfiguration.managedFields", errors);
        String groupMode = present(groupNode) ? enumValue(groupNode, groupPrefix,
                "thingGroupIndexingMode", ON_OFF, true, errors) : null;
        List<Field> groupManagedFields = fields(groupNode.path("managedFields"), groupPrefix + ".managedFields",
                errors);
        fields(groupNode.path("customFields"), groupPrefix + ".customFields", errors);
        if (!errors.isEmpty()) {
            throw invalid(errors.size() + (errors.size() == 1 ? " validation error" : " validation errors")
                    + " detected: " + String.join("; ", errors));
        }
        if (thing != null) {
            thing = validateThing(thing, managedFields);
        }
        // Checked in either mode and never stored: AWS derives the group's managed fields from its mode.
        requireKnownManagedFields(groupManagedFields, THING_GROUP_MANAGED_FIELDS, groupPrefix);
        synchronized (lock) {
            IotIndexingConfiguration current = getIndexingConfiguration(region);
            store.put(key(region), new IotIndexingConfiguration(thing == null ? current.thing() : thing,
                    groupMode == null ? current.thingGroupIndexingMode() : groupMode));
        }
    }

    /** The stored configuration, or the OFF one AWS reports before the first update. */
    public IotIndexingConfiguration getIndexingConfiguration(String region) {
        return store.get(key(region)).orElse(IotIndexingConfiguration.OFF);
    }

    /** The schema of an enabled index. */
    public String describeIndex(String indexName, String region) {
        IotIndexingConfiguration configuration = getIndexingConfiguration(region);
        String schema = switch (indexName) {
            case THINGS_INDEX -> thingsSchema(configuration.thing());
            case THING_GROUPS_INDEX -> "OFF".equals(configuration.thingGroupIndexingMode()) ? null : "REGISTRY";
            default -> throw invalid("Unrecognized indexName " + indexName);
        };
        if (schema == null) {
            throw new AwsException("ResourceNotFoundException", "Index " + indexName + " does not exist", 404);
        }
        return schema;
    }

    /** The managed fields AWS derives from the thing indexing modes; none while indexing is OFF. */
    public static List<Field> thingManagedFields(ThingIndexing thing) {
        if ("OFF".equals(thing.thingIndexingMode())) {
            return List.of();
        }
        List<Field> fields = new ArrayList<>(REGISTRY_FIELDS);
        if ("REGISTRY_AND_SHADOW".equals(thing.thingIndexingMode())) {
            fields.addAll(SHADOW_FIELDS);
        }
        if ("ON".equals(thing.namedShadowIndexingMode())) {
            fields.addAll(NAMED_SHADOW_FIELDS);
        }
        if ("STATUS".equals(thing.thingConnectivityIndexingMode())) {
            fields.addAll(CONNECTIVITY_FIELDS);
        }
        if ("VIOLATIONS".equals(thing.deviceDefenderIndexingMode())) {
            fields.addAll(DEVICE_DEFENDER_FIELDS);
        }
        return fields;
    }

    private static String thingsSchema(ThingIndexing thing) {
        if ("OFF".equals(thing.thingIndexingMode())) {
            return null;
        }
        if ("VIOLATIONS".equals(thing.deviceDefenderIndexingMode()) || "ON".equals(thing.namedShadowIndexingMode())) {
            return "MULTI_INDEXING_MODE";
        }
        return "STATUS".equals(thing.thingConnectivityIndexingMode())
                ? thing.thingIndexingMode() + "_AND_CONNECTIVITY_STATUS"
                : thing.thingIndexingMode();
    }

    private static ThingIndexing parseThing(JsonNode node, List<String> errors) {
        String prefix = "thingIndexingConfiguration";
        String mode = enumValue(node, prefix, "thingIndexingMode", THING_INDEXING_MODES, true, errors);
        String connectivity = enumValue(node, prefix, "thingConnectivityIndexingMode", CONNECTIVITY_MODES, false, errors);
        String deviceDefender = enumValue(node, prefix, "deviceDefenderIndexingMode", DEVICE_DEFENDER_MODES, false, errors);
        String namedShadow = enumValue(node, prefix, "namedShadowIndexingMode", ON_OFF, false, errors);
        List<Field> customFields = fields(node.path("customFields"), prefix + ".customFields", errors);
        JsonNode filter = object(node.path("filter"), prefix + ".filter");
        JsonNode connectivityFilter = object(filter.path("connectivity"), prefix + ".filter.connectivity");
        List<String> socketInformation = texts(connectivityFilter.path("includeSocketInformation"),
                prefix + ".filter.connectivity.includeSocketInformation");
        // AWS checks this list as a whole and prints the whole list as the offending value.
        if (!SOCKET_INFORMATION_APIS.containsAll(socketInformation)) {
            errors.add("Value '" + socketInformation + "' at '" + prefix
                    + ".filter.connectivity.includeSocketInformation' failed to satisfy constraint: "
                    + "Member must satisfy constraint: [Member must satisfy enum value set: "
                    + SOCKET_INFORMATION_APIS + "]");
        }
        List<GeoLocation> geoLocations = new ArrayList<>();
        String geoLocationsPath = prefix + ".filter.geoLocations";
        for (JsonNode location : list(filter.path("geoLocations"), geoLocationsPath, JsonNodeType.OBJECT)) {
            String member = geoLocationsPath + "." + (geoLocations.size() + 1) + ".member";
            String name = text(location.path("name"), member + ".name");
            String order = text(location.path("order"), member + ".order");
            if (order != null && !GEO_LOCATION_ORDERS.contains(order)) {
                errors.add(enumError(order, member + ".order", GEO_LOCATION_ORDERS));
            }
            geoLocations.add(new GeoLocation(name, order == null ? "LatLon" : order));
        }
        return new ThingIndexing(mode, connectivity, deviceDefender, namedShadow,
                texts(filter.path("namedShadowNames"), prefix + ".filter.namedShadowNames"),
                geoLocations, socketInformation, customFields);
    }

    private static ThingIndexing validateThing(ThingIndexing thing, List<Field> managedFields) {
        if ("OFF".equals(thing.thingIndexingMode())) {
            requireOff(thing.thingConnectivityIndexingMode(), "ThingConnectivityIndexingMode");
            requireOff(thing.namedShadowIndexingMode(), "NamedShadowIndexingMode");
            requireOff(thing.deviceDefenderIndexingMode(), "DeviceDefenderIndexingMode");
        }
        if ("ON".equals(thing.namedShadowIndexingMode()) && thing.namedShadowNames().isEmpty()) {
            throw invalid("NamedShadowNames Filter must not be empty for enabling NamedShadowIndexingMode");
        }
        for (Field field : thing.customFields()) {
            if (field.name() == null) {
                // AWS's message verbatim, the missing space included.
                throw invalid("Custom field name cannot be nullFieldName: null, FieldType: " + field.type());
            }
            if (field.type() == null) {
                throw invalid("Invalid field type: null. Expecting one of [String, Number, Boolean, Geopoint]");
            }
            if (KNOWN_THING_FIELDS.stream().anyMatch(known -> known.name().equals(field.name()))) {
                throw invalid("Defined field is a reserved field for MULTI_INDEXING_MODE. Field: " + field.name());
            }
        }
        requireKnownManagedFields(managedFields, KNOWN_THING_FIELDS, "thingIndexingConfiguration");
        // AWS keeps nothing of a thing configuration that turns indexing OFF.
        return "OFF".equals(thing.thingIndexingMode()) ? ThingIndexing.OFF : thing;
    }

    private static void requireKnownManagedFields(List<Field> fields, Collection<Field> known, String prefix) {
        List<Field> unknown = fields.stream().filter(field -> !known.contains(field)).toList();
        if (!unknown.isEmpty()) {
            throw invalid("Only managed fields with expected types are allowed in " + prefix
                    + ".managedFields. Invalid field(s): " + unknown.stream()
                    .map(field -> "name:" + field.name() + ", type:" + field.type())
                    .collect(Collectors.joining(", ", "[", "]")));
        }
    }

    private static void requireOff(String mode, String member) {
        if (!"OFF".equals(mode)) {
            throw invalid("ThingIndexingMode must be turned ON for enabling " + member);
        }
    }

    private static List<Field> fields(JsonNode node, String path, List<String> errors) {
        List<Field> fields = new ArrayList<>();
        for (JsonNode field : list(node, path, JsonNodeType.OBJECT)) {
            String member = path + "." + (fields.size() + 1) + ".member";
            String name = text(field.path("name"), member + ".name");
            String type = text(field.path("type"), member + ".type");
            if (type != null && !FIELD_TYPES.contains(type)) {
                errors.add(enumError(type, member + ".type", FIELD_TYPES));
            }
            fields.add(new Field(name, type));
        }
        return fields;
    }

    /** A mode, OFF when an optional one is absent; a violation is collected rather than thrown. */
    private static String enumValue(JsonNode node, String prefix, String member, List<String> allowed,
                                    boolean required, List<String> errors) {
        String value = text(node.path(member), prefix + "." + member);
        if (value == null) {
            if (required) {
                errors.add("Value null at '" + prefix + "." + member
                        + "' failed to satisfy constraint: Member must not be null");
            }
            return "OFF";
        }
        if (!allowed.contains(value)) {
            errors.add(enumError(value, prefix + "." + member, allowed));
        }
        return value;
    }

    private static String enumError(String value, String path, List<String> allowed) {
        return "Value '" + value + "' at '" + path
                + "' failed to satisfy constraint: Member must satisfy enum value set: " + allowed;
    }

    /** An object member, read as missing when it is absent or null. */
    private static JsonNode object(JsonNode node, String path) {
        if (!present(node)) {
            return MissingNode.getInstance();
        }
        if (!node.isObject()) {
            throw serializationError(path, JsonNodeType.OBJECT);
        }
        return node;
    }

    /** The elements of a list member, none when it is absent or null; every element must be of the given type. */
    private static List<JsonNode> list(JsonNode node, String path, JsonNodeType elementType) {
        if (!present(node)) {
            return List.of();
        }
        if (!node.isArray()) {
            throw serializationError(path, JsonNodeType.ARRAY);
        }
        List<JsonNode> elements = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.getNodeType() != elementType) {
                throw serializationError(path + "." + (elements.size() + 1), elementType);
            }
            elements.add(element);
        }
        return elements;
    }

    private static List<String> texts(JsonNode node, String path) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : list(node, path, JsonNodeType.STRING)) {
            values.add(value.asText());
        }
        return values;
    }

    /** A string member, null when it is absent or null. */
    private static String text(JsonNode node, String path) {
        if (!present(node)) {
            return null;
        }
        if (!node.isTextual()) {
            throw serializationError(path, JsonNodeType.STRING);
        }
        return node.asText();
    }

    private static boolean present(JsonNode node) {
        return !node.isMissingNode() && !node.isNull();
    }

    /** A member of the wrong JSON type fails before any validation, as AWS fails to deserialize it. */
    private static AwsException serializationError(String path, JsonNodeType expected) {
        return new AwsException("SerializationException",
                path + " must be a JSON " + expected.name().toLowerCase(Locale.ROOT), 400);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static String key(String region) {
        return "indexing:" + region;
    }
}
