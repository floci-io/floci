package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.Field;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.GeoLocation;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.ThingIndexing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IotFleetIndexingServiceTest {

    private static final String REGION = "us-east-1";

    private static final List<Field> REGISTRY = List.of(
            new Field("thingName", "String"),
            new Field("thingId", "String"),
            new Field("registry.version", "Number"),
            new Field("registry.thingTypeName", "String"),
            new Field("registry.thingGroupNames", "String"));
    private static final List<Field> SHADOW = List.of(
            new Field("shadow.version", "Number"),
            new Field("shadow.hasDelta", "Boolean"));
    private static final List<Field> NAMED_SHADOW = List.of(
            new Field("shadow.name.*.hasDelta", "Boolean"),
            new Field("shadow.name.*.version", "Number"));
    private static final List<Field> CONNECTIVITY = List.of(
            new Field("connectivity.connected", "Boolean"),
            new Field("connectivity.timestamp", "Number"),
            new Field("connectivity.disconnectReason", "String"),
            new Field("connectivity.clientId", "String"),
            new Field("connectivity.cleanSession", "Boolean"),
            new Field("connectivity.keepAliveDuration", "Number"),
            new Field("connectivity.sessionExpiry", "Number"),
            new Field("connectivity.version", "Number"));
    private static final List<Field> DEVICE_DEFENDER = List.of(
            new Field("deviceDefender.version", "Number"),
            new Field("deviceDefender.violationCount", "Number"),
            new Field("deviceDefender.*.*.inViolation", "Boolean"),
            new Field("deviceDefender.*.*.lastViolationTime", "Number"),
            new Field("deviceDefender.*.*.metricName", "String"));
    private static final List<Field> THING_GROUP = List.of(
            new Field("parentGroupNames", "String"),
            new Field("description", "String"),
            new Field("version", "Number"),
            new Field("thingGroupName", "String"),
            new Field("thingGroupId", "String"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final IotFleetIndexingService service = new IotFleetIndexingService(new InMemoryStorage<>());

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private void update(String body) {
        service.updateIndexingConfiguration(json(body), REGION);
    }

    private ThingIndexing thing() {
        return service.getIndexingConfiguration(REGION).thing();
    }

    private Set<Field> managedFieldsAfter(String body) {
        update(body);
        return new HashSet<>(IotFleetIndexingService.thingManagedFields(thing()));
    }

    @SafeVarargs
    private static Set<Field> union(List<Field>... groups) {
        Set<Field> fields = new HashSet<>();
        Stream.of(groups).forEach(fields::addAll);
        return fields;
    }

    private void assertRejected(String body, String message) {
        AwsException failure = assertThrows(AwsException.class, () -> update(body));
        assertEquals("InvalidRequestException", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        assertEquals(message, failure.getMessage());
    }

    private void assertSerializationError(String body) {
        AwsException failure = assertThrows(AwsException.class, () -> update(body), body);
        assertEquals("SerializationException", failure.getErrorCode(), body);
        assertEquals(400, failure.getHttpStatus(), body);
    }

    private static String enumError(String value, String path, String allowed) {
        return "Value '" + value + "' at '" + path
                + "' failed to satisfy constraint: Member must satisfy enum value set: " + allowed;
    }

    private void assertDescribeFails(String indexName, String code, int status, String message) {
        AwsException failure = assertThrows(AwsException.class, () -> service.describeIndex(indexName, REGION));
        assertEquals(code, failure.getErrorCode());
        assertEquals(status, failure.getHttpStatus());
        assertEquals(message, failure.getMessage());
    }

    @Test
    void unsetConfigurationIsOff() {
        IotIndexingConfiguration configuration = service.getIndexingConfiguration(REGION);

        assertEquals(IotIndexingConfiguration.OFF, configuration);
        assertEquals(List.of(), IotFleetIndexingService.thingManagedFields(configuration.thing()));
    }

    @Test
    void registryModeManagesRegistryFieldsAndDefaultsTheRest() {
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"}}
            """);

        assertEquals(new ThingIndexing("REGISTRY", "OFF", "OFF", "OFF", List.of(), List.of(), List.of(), List.of()),
                thing());
        assertEquals(new HashSet<>(REGISTRY), new HashSet<>(IotFleetIndexingService.thingManagedFields(thing())));
        assertEquals("OFF", service.getIndexingConfiguration(REGION).thingGroupIndexingMode());
    }

    @Test
    void eachModeAddsItsManagedFields() {
        assertEquals(union(REGISTRY, SHADOW), managedFieldsAfter("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW"}}
            """));
        assertEquals(union(REGISTRY, CONNECTIVITY), managedFieldsAfter("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "thingConnectivityIndexingMode": "STATUS"}}
            """));
        assertEquals(union(REGISTRY, NAMED_SHADOW), managedFieldsAfter("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "namedShadowIndexingMode": "ON",
              "filter": {"namedShadowNames": ["config"]}}}
            """));
        assertEquals(union(REGISTRY, DEVICE_DEFENDER), managedFieldsAfter("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "deviceDefenderIndexingMode": "VIOLATIONS"}}
            """));
        assertEquals(union(REGISTRY, SHADOW, NAMED_SHADOW, CONNECTIVITY, DEVICE_DEFENDER), managedFieldsAfter("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW",
              "thingConnectivityIndexingMode": "STATUS", "deviceDefenderIndexingMode": "VIOLATIONS",
              "namedShadowIndexingMode": "ON", "filter": {"namedShadowNames": ["config"]}}}
            """));
    }

    @Test
    void thingGroupIndexingManagesGroupFields() {
        update("""
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}}
            """);

        assertEquals("ON", service.getIndexingConfiguration(REGION).thingGroupIndexingMode());
        assertEquals(new HashSet<>(THING_GROUP), new HashSet<>(IotFleetIndexingService.THING_GROUP_MANAGED_FIELDS));
    }

    @Test
    void clientSentFilterAndCustomFieldsAreKept() {
        update("""
            {"thingIndexingConfiguration": {
              "thingIndexingMode": "REGISTRY_AND_SHADOW",
              "namedShadowIndexingMode": "ON",
              "customFields": [{"name": "attributes.site", "type": "String"}],
              "filter": {
                "namedShadowNames": ["config", "state"],
                "geoLocations": [{"name": "shadow.reported.location", "order": "LatLon"}],
                "connectivity": {"includeSocketInformation": ["GET_THING_CONNECTIVITY_DATA"]}
              }}}
            """);

        assertEquals(new ThingIndexing("REGISTRY_AND_SHADOW", "OFF", "OFF", "ON", List.of("config", "state"),
                List.of(new GeoLocation("shadow.reported.location", "LatLon")), List.of("GET_THING_CONNECTIVITY_DATA"),
                List.of(new Field("attributes.site", "String"))), thing());
    }

    @Test
    void aGeoLocationWithoutAnOrderIsStoredAsLatLon() {
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW",
              "filter": {"geoLocations": [{"name": "shadow.name.building.reported.location"}]}}}
            """);

        assertEquals(List.of(new GeoLocation("shadow.name.building.reported.location", "LatLon")),
                thing().geoLocations());
    }

    @Test
    void turningThingIndexingOffResetsTheThingConfiguration() {
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW",
              "thingConnectivityIndexingMode": "STATUS", "namedShadowIndexingMode": "ON",
              "customFields": [{"name": "attributes.site", "type": "String"}],
              "filter": {"namedShadowNames": ["config"]}}}
            """);

        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "OFF",
              "customFields": [{"name": "attributes.site", "type": "String"}],
              "filter": {"namedShadowNames": ["config"]}}}
            """);

        assertEquals(ThingIndexing.OFF, thing());
        assertEquals(List.of(), IotFleetIndexingService.thingManagedFields(thing()));
    }

    @Test
    void updatingOneConfigurationLeavesTheOtherUnchanged() {
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"},
             "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}}
            """);

        update("""
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "OFF"}}
            """);
        assertEquals("REGISTRY", thing().thingIndexingMode());

        update("""
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}}
            """);
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW"}}
            """);
        assertEquals("REGISTRY_AND_SHADOW", thing().thingIndexingMode());
        assertEquals("ON", service.getIndexingConfiguration(REGION).thingGroupIndexingMode());
    }

    @Test
    void configurationIsKeptPerRegion() {
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"}}
            """);

        assertEquals(IotIndexingConfiguration.OFF, service.getIndexingConfiguration("eu-west-1"));
        assertEquals("REGISTRY", thing().thingIndexingMode());
    }

    @Test
    void updateWithoutAnyConfigurationIsRejected() {
        String message = "At least one configuration to update "
                + "(thingIndexingConfiguration / thingGroupIndexingConfiguration) is required";
        assertRejected("{}", message);
        assertRejected("""
            {"thingIndexingConfiguration": null}
            """, message);
    }

    @Test
    void missingModesAreReportedAsNullMembers() {
        assertRejected("""
            {"thingIndexingConfiguration": {}}
            """, "1 validation error detected: Value null at 'thingIndexingConfiguration.thingIndexingMode' "
                + "failed to satisfy constraint: Member must not be null");
        assertRejected("""
            {"thingGroupIndexingConfiguration": {}}
            """, "1 validation error detected: Value null at 'thingGroupIndexingConfiguration.thingGroupIndexingMode' "
                + "failed to satisfy constraint: Member must not be null");
    }

    @Test
    void enumViolationsAreReportedWithAwsValueSets() {
        Map<String, String> violations = Map.of(
                """
                {"thingIndexingConfiguration": {"thingIndexingMode": "BOGUS"}}
                """, enumError("BOGUS", "thingIndexingConfiguration.thingIndexingMode",
                        "[REGISTRY_AND_SHADOW, OFF, REGISTRY]"),
                """
                {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "thingConnectivityIndexingMode": "BOGUS"}}
                """, enumError("BOGUS", "thingIndexingConfiguration.thingConnectivityIndexingMode", "[OFF, STATUS]"),
                """
                {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "deviceDefenderIndexingMode": "BOGUS"}}
                """, enumError("BOGUS", "thingIndexingConfiguration.deviceDefenderIndexingMode", "[VIOLATIONS, OFF]"),
                """
                {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "namedShadowIndexingMode": "BOGUS"}}
                """, enumError("BOGUS", "thingIndexingConfiguration.namedShadowIndexingMode", "[OFF, ON]"),
                """
                {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "BOGUS"}}
                """, enumError("BOGUS", "thingGroupIndexingConfiguration.thingGroupIndexingMode", "[OFF, ON]"),
                """
                {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY",
                  "customFields": [{"name": "attributes.site", "type": "Bogus"}]}}
                """, enumError("Bogus", "thingIndexingConfiguration.customFields.1.member.type",
                        "[Boolean, Number, String]"));

        violations.forEach((body, error) -> assertRejected(body, "1 validation error detected: " + error));
    }

    @Test
    void severalViolationsAreReportedTogether() {
        AwsException failure = assertThrows(AwsException.class, () -> update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "BOGUS", "thingConnectivityIndexingMode": "BOGUS"}}
            """));

        assertEquals("InvalidRequestException", failure.getErrorCode());
        assertTrue(failure.getMessage().startsWith("2 validation errors detected: "), failure.getMessage());
        assertTrue(failure.getMessage().contains(enumError("BOGUS", "thingIndexingConfiguration.thingIndexingMode",
                "[REGISTRY_AND_SHADOW, OFF, REGISTRY]")), failure.getMessage());
        assertTrue(failure.getMessage().contains("; "), failure.getMessage());
        assertTrue(failure.getMessage().contains(enumError("BOGUS",
                "thingIndexingConfiguration.thingConnectivityIndexingMode", "[OFF, STATUS]")), failure.getMessage());
    }

    @Test
    void filterEnumsAreValidatedEvenWithThingIndexingOff() {
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "OFF", "filter": {
              "geoLocations": [{"name": "shadow.reported.loc", "order": "BOGUS"}],
              "connectivity": {"includeSocketInformation": ["GetConnection"]}}}}
            """, "2 validation errors detected: Value '[GetConnection]' at "
                + "'thingIndexingConfiguration.filter.connectivity.includeSocketInformation' failed to satisfy "
                + "constraint: Member must satisfy constraint: [Member must satisfy enum value set: "
                + "[GET_THING_CONNECTIVITY_DATA]]; Value 'BOGUS' at "
                + "'thingIndexingConfiguration.filter.geoLocations.1.member.order' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [LatLon, LonLat]");
    }

    @Test
    void secondaryModesNeedThingIndexingOn() {
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "OFF", "thingConnectivityIndexingMode": "STATUS"}}
            """, "ThingIndexingMode must be turned ON for enabling ThingConnectivityIndexingMode");
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "OFF", "namedShadowIndexingMode": "ON",
              "filter": {"namedShadowNames": ["config"]}}}
            """, "ThingIndexingMode must be turned ON for enabling NamedShadowIndexingMode");
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "OFF", "deviceDefenderIndexingMode": "VIOLATIONS"}}
            """, "ThingIndexingMode must be turned ON for enabling DeviceDefenderIndexingMode");
    }

    @Test
    void namedShadowIndexingNeedsShadowNames() {
        String message = "NamedShadowNames Filter must not be empty for enabling NamedShadowIndexingMode";
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW", "namedShadowIndexingMode": "ON"}}
            """, message);
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW", "namedShadowIndexingMode": "ON",
              "filter": {"namedShadowNames": []}}}
            """, message);
    }

    @Test
    void managedFieldsMustBeKnownWithTheirExpectedType() {
        String prefix = "Only managed fields with expected types are allowed in "
                + "thingIndexingConfiguration.managedFields. Invalid field(s): ";
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY",
              "managedFields": [{"name": "thingName", "type": "Number"}]}}
            """, prefix + "[name:thingName, type:Number]");
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY",
              "managedFields": [{"name": "thingId", "type": "String"}, {"name": "a", "type": "String"},
                                {"name": "b", "type": "String"}]}}
            """, prefix + "[name:a, type:String, name:b, type:String]");
        // The configuration TEOS deploys: AWS rejects custom shadow paths sent as managed fields.
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW", "namedShadowIndexingMode": "ON",
              "filter": {"namedShadowNames": ["building"]},
              "managedFields": [{"name": "shadow.name.building.reported.type", "type": "String"},
                                {"name": "shadow.name.building.reported.tz", "type": "String"}]}}
            """, prefix + "[name:shadow.name.building.reported.type, type:String, "
                + "name:shadow.name.building.reported.tz, type:String]");
    }

    @Test
    void knownManagedFieldsAreAcceptedInAnyModeAndNotStored() {
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY",
              "managedFields": [{"name": "shadow.version", "type": "Number"},
                                {"name": "connectivity.connected", "type": "Boolean"}]}}
            """);

        assertEquals(new HashSet<>(REGISTRY), new HashSet<>(IotFleetIndexingService.thingManagedFields(thing())));
    }

    @Test
    void rejectedUpdateChangesNothing() {
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"},
             "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}}
            """);

        assertThrows(AwsException.class, () -> update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW", "namedShadowIndexingMode": "ON"},
             "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "OFF"}}
            """));

        assertEquals("REGISTRY", thing().thingIndexingMode());
        assertEquals("ON", service.getIndexingConfiguration(REGION).thingGroupIndexingMode());
    }

    // Single quotes stand in for double quotes to keep the bodies on one line.
    @ParameterizedTest
    @ValueSource(strings = {
        "[]",
        "'x'",
        "{'thingIndexingConfiguration': 'x'}",
        "{'thingGroupIndexingConfiguration': []}",
        "{'thingIndexingConfiguration': {'thingIndexingMode': 1}}",
        "{'thingGroupIndexingConfiguration': {'thingGroupIndexingMode': 1}}",
        "{'thingIndexingConfiguration': {'thingIndexingMode': 'BOGUS', 'customFields': {}}}",
        "{'thingIndexingConfiguration': {'thingIndexingMode': 'BOGUS'}, "
                + "'thingGroupIndexingConfiguration': {'thingGroupIndexingMode': 'ON', 'customFields': [1]}}"
    })
    void aBodyOrConfigurationOfTheWrongJsonKindIsASerializationError(String body) {
        assertSerializationError(body.replace('\'', '"'));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "'thingConnectivityIndexingMode': true",
        "'deviceDefenderIndexingMode': {}",
        "'namedShadowIndexingMode': []",
        "'customFields': {}",
        "'customFields': [1]",
        "'customFields': [null]",
        "'customFields': [{'name': 1, 'type': 'String'}]",
        "'customFields': [{'name': 'attributes.x', 'type': 1}]",
        "'managedFields': 'x'",
        "'managedFields': ['thingName']",
        "'filter': 'bad'",
        "'filter': {'namedShadowNames': 'config'}",
        "'filter': {'namedShadowNames': [1]}",
        "'filter': {'namedShadowNames': [null]}",
        "'filter': {'geoLocations': {}}",
        "'filter': {'geoLocations': [1]}",
        "'filter': {'geoLocations': [{'name': 1}]}",
        "'filter': {'geoLocations': [{'name': 'shadow.reported.location', 'order': 1}]}",
        "'filter': {'connectivity': 1}",
        "'filter': {'connectivity': {'includeSocketInformation': 'GET_THING_CONNECTIVITY_DATA'}}",
        "'filter': {'connectivity': {'includeSocketInformation': [1]}}",
        "'filter': {'connectivity': {'includeSocketInformation': [null]}}"
    })
    void aThingMemberOfTheWrongJsonKindIsASerializationError(String member) {
        assertSerializationError(("{'thingIndexingConfiguration': {'thingIndexingMode': 'REGISTRY', " + member + "}}")
                .replace('\'', '"'));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "'managedFields': 'x'",
        "'managedFields': [null]",
        "'managedFields': [{'name': 'version', 'type': 2}]",
        "'customFields': {}",
        "'customFields': [true]"
    })
    void aThingGroupMemberOfTheWrongJsonKindIsASerializationError(String member) {
        assertSerializationError(("{'thingGroupIndexingConfiguration': {'thingGroupIndexingMode': 'OFF', " + member + "}}")
                .replace('\'', '"'));
    }

    @Test
    void absentAndNullMembersAreNotSent() {
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "thingConnectivityIndexingMode": null,
              "customFields": null, "managedFields": null,
              "filter": {"namedShadowNames": null, "geoLocations": null, "connectivity": {"includeSocketInformation": null}}},
             "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON", "managedFields": null, "customFields": null}}
            """);
        assertEquals(new ThingIndexing("REGISTRY", "OFF", "OFF", "OFF", List.of(), List.of(), List.of(), List.of()),
                thing());

        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW", "filter": null},
             "thingGroupIndexingConfiguration": null}
            """);
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW", "filter": {"connectivity": null}}}
            """);
        assertEquals(new IotIndexingConfiguration(new ThingIndexing("REGISTRY_AND_SHADOW", "OFF", "OFF", "OFF",
                List.of(), List.of(), List.of(), List.of()), "ON"), service.getIndexingConfiguration(REGION));
    }

    @Test
    void thingCustomFieldsNeedANameAndThenAType() {
        String noName = "Custom field name cannot be nullFieldName: null, FieldType: ";
        String noType = "Invalid field type: null. Expecting one of [String, Number, Boolean, Geopoint]";
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "customFields": [{}]}}
            """, noName + "null");
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "customFields": [{"type": "String"}]}}
            """, noName + "String");
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "customFields": [{"name": "attributes.x"}]}}
            """, noType);
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY",
              "customFields": [{"name": "attributes.a"}, {"type": "Number"}]}}
            """, noType);
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY",
              "customFields": [{"name": "attributes.a", "type": "String"}, {"type": "Number"}]}}
            """, noName + "Number");
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "customFields": [{}],
              "managedFields": [{"name": "bogus", "type": "String"}]}}
            """, noName + "null");
    }

    @Test
    void customFieldTypeViolationsAreReportedBeforeAMissingName() {
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY",
              "customFields": [{}, {"name": "attributes.x", "type": "Bogus"}]}}
            """, "1 validation error detected: " + enumError("Bogus",
                "thingIndexingConfiguration.customFields.2.member.type", "[Boolean, Number, String]"));
    }

    @ParameterizedTest
    @CsvSource({
        "thingName, String",
        "registry.version, Number",
        "connectivity.connected, Boolean"
    })
    void aThingCustomFieldNamedAfterAManagedFieldIsRejected(String name, String type) {
        assertRejected(("{'thingIndexingConfiguration': {'thingIndexingMode': 'REGISTRY_AND_SHADOW', "
                + "'thingConnectivityIndexingMode': 'STATUS', "
                + "'customFields': [{'name': '" + name + "', 'type': '" + type + "'}]}}").replace('\'', '"'),
                "Defined field is a reserved field for MULTI_INDEXING_MODE. Field: " + name);
    }

    @Test
    void theFirstReservedCustomFieldNameIsReportedWhateverItsType() {
        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW",
              "thingConnectivityIndexingMode": "STATUS",
              "customFields": [{"name": "shadow.name.building.reported.x", "type": "String"},
                               {"name": "thingName", "type": "Number"},
                               {"name": "registry.version", "type": "String"}]}}
            """, "Defined field is a reserved field for MULTI_INDEXING_MODE. Field: thingName");
    }

    @Test
    void thingGroupManagedFieldsMustBeKnownWithTheirExpectedTypeInEitherMode() {
        String prefix = "Only managed fields with expected types are allowed in "
                + "thingGroupIndexingConfiguration.managedFields. Invalid field(s): ";
        assertRejected("""
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON",
              "managedFields": [{"name": "bogus", "type": "String"}]}}
            """, prefix + "[name:bogus, type:String]");
        assertRejected("""
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "OFF",
              "managedFields": [{"name": "bogus", "type": "String"}]}}
            """, prefix + "[name:bogus, type:String]");
        assertRejected("""
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON",
              "managedFields": [{"name": "version", "type": "Number"}, {"name": "description", "type": "Number"}]}}
            """, prefix + "[name:description, type:Number]");
    }

    @Test
    void thingGroupFieldTypesAreValidated() {
        assertRejected("""
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON",
              "managedFields": [{"name": "description", "type": "Bogus"}]}}
            """, "1 validation error detected: " + enumError("Bogus",
                "thingGroupIndexingConfiguration.managedFields.1.member.type", "[Boolean, Number, String]"));
        assertRejected("""
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "OFF",
              "customFields": [{"name": "attributes.x", "type": "Bogus"}]}}
            """, "1 validation error detected: " + enumError("Bogus",
                "thingGroupIndexingConfiguration.customFields.1.member.type", "[Boolean, Number, String]"));
    }

    @Test
    void thingGroupFieldsAreAcceptedButNotStored() {
        update("""
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON",
              "managedFields": [{"name": "parentGroupNames", "type": "String"}, {"name": "description", "type": "String"},
                                {"name": "version", "type": "Number"}, {"name": "thingGroupName", "type": "String"},
                                {"name": "thingGroupId", "type": "String"}],
              "customFields": [{"name": "attributes.x", "type": "String"}, {}]}}
            """);

        assertEquals(new IotIndexingConfiguration(ThingIndexing.OFF, "ON"), service.getIndexingConfiguration(REGION));
    }

    @Test
    void rejectedThingGroupFieldsChangeNothing() {
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"},
             "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}}
            """);
        IotIndexingConfiguration stored = service.getIndexingConfiguration(REGION);

        assertRejected("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "OFF"},
             "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "OFF",
              "managedFields": [{"name": "bogus", "type": "String"}]}}
            """, "Only managed fields with expected types are allowed in "
                + "thingGroupIndexingConfiguration.managedFields. Invalid field(s): [name:bogus, type:String]");
        assertSerializationError("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "OFF"},
             "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "OFF", "customFields": {}}}
            """);

        assertEquals(stored, service.getIndexingConfiguration(REGION));
    }

    @Test
    void describeIndexSchemaFollowsTheModes() {
        Map<String, String> schemas = Map.of(
                """
                {"thingIndexingMode": "REGISTRY"}
                """, "REGISTRY",
                """
                {"thingIndexingMode": "REGISTRY_AND_SHADOW"}
                """, "REGISTRY_AND_SHADOW",
                """
                {"thingIndexingMode": "REGISTRY", "thingConnectivityIndexingMode": "STATUS"}
                """, "REGISTRY_AND_CONNECTIVITY_STATUS",
                """
                {"thingIndexingMode": "REGISTRY_AND_SHADOW", "thingConnectivityIndexingMode": "STATUS"}
                """, "REGISTRY_AND_SHADOW_AND_CONNECTIVITY_STATUS",
                """
                {"thingIndexingMode": "REGISTRY", "deviceDefenderIndexingMode": "VIOLATIONS"}
                """, "MULTI_INDEXING_MODE",
                """
                {"thingIndexingMode": "REGISTRY_AND_SHADOW", "thingConnectivityIndexingMode": "STATUS",
                 "namedShadowIndexingMode": "ON", "filter": {"namedShadowNames": ["config"]}}
                """, "MULTI_INDEXING_MODE");

        schemas.forEach((thingConfiguration, schema) -> {
            update("{\"thingIndexingConfiguration\": " + thingConfiguration + "}");
            assertEquals(schema, service.describeIndex("AWS_Things", REGION), thingConfiguration);
        });

        update("""
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}}
            """);
        assertEquals("REGISTRY", service.describeIndex("AWS_ThingGroups", REGION));
    }

    @Test
    void describeIndexOfADisabledIndexIsNotFound() {
        assertDescribeFails("AWS_Things", "ResourceNotFoundException", 404, "Index AWS_Things does not exist");
        assertDescribeFails("AWS_ThingGroups", "ResourceNotFoundException", 404,
                "Index AWS_ThingGroups does not exist");

        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"},
             "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}}
            """);
        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "OFF"}}
            """);
        assertDescribeFails("AWS_Things", "ResourceNotFoundException", 404, "Index AWS_Things does not exist");
        assertEquals("REGISTRY", service.describeIndex("AWS_ThingGroups", REGION));
    }

    @Test
    void describeIndexOfAnUnknownNameIsInvalid() {
        assertDescribeFails("Nope", "InvalidRequestException", 400, "Unrecognized indexName Nope");

        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"}}
            """);
        assertDescribeFails("AWS_Thing", "InvalidRequestException", 400, "Unrecognized indexName AWS_Thing");
    }
}
