package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static final String CONNECTIVITY_NOT_ENABLED = "Query includes one or more constraints for Connectivity "
            + "attribute, but Connectivity indexing is not enabled for AWS_Things index";

    private final ObjectMapper mapper = new ObjectMapper();
    private final IotServiceTestSupport iot = new IotServiceTestSupport(REGION, null);
    private final IotFleetIndexingService service =
            new IotFleetIndexingService(new InMemoryStorage<>(), iot.service);

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

    /**
     * Four things in the default region: alpha-1 and beta-1 of type sensor, alpha-1 and alpha-2 in
     * thing groups, attribute values in mixed case, and gamma with nothing but its name.
     */
    private void fleet(String thingIndexing) {
        update("{\"thingIndexingConfiguration\": " + thingIndexing + "}");
        IotService registry = iot.service;
        registry.createThingType("sensor", mapper.createObjectNode(), REGION);
        registry.createThingGroup("north-group", mapper.createObjectNode(), REGION);
        registry.createThingGroup("south-group", mapper.createObjectNode(), REGION);
        registry.createThing("alpha-1", Map.of("site", "North", "provider", "acme"), "sensor", REGION);
        registry.createThing("alpha-2", Map.of("site", "south", "provider", "ACME"), null, REGION);
        registry.createThing("beta-1", Map.of("provider", "other"), "sensor", REGION);
        registry.createThing("gamma", Map.of(), null, REGION);
        registry.addThingToThingGroup("north-group", "alpha-1", REGION);
        registry.addThingToThingGroup("south-group", "alpha-2", REGION);
        registry.addThingToThingGroup("north-group", "alpha-2", REGION);
    }

    private void fleet() {
        fleet("{\"thingIndexingMode\": \"REGISTRY\"}");
    }

    private ObjectNode query(String queryString) {
        return mapper.createObjectNode().put("queryString", queryString);
    }

    private IotService.Page<ObjectNode> search(ObjectNode request) {
        return service.searchIndex(request, REGION);
    }

    private List<String> names(String queryString) {
        return search(query(queryString)).items().stream().map(thing -> thing.path("thingName").asText()).toList();
    }

    private ObjectNode document(String thingName) {
        List<ObjectNode> things = search(query("thingName:" + thingName)).items();
        assertEquals(1, things.size());
        return things.get(0);
    }

    private void assertSearchFails(ObjectNode request, String code, int status, String message) {
        AwsException failure = assertThrows(AwsException.class, () -> search(request));
        assertEquals(code, failure.getErrorCode());
        assertEquals(status, failure.getHttpStatus());
        assertEquals(message, failure.getMessage());
    }

    private void assertQueryFails(String queryString, String message) {
        assertSearchFails(query(queryString), "InvalidQueryException", 400, message + ", query string: " + queryString);
    }

    private void assertUnsupported(String queryString, String construct) {
        assertQueryFails(queryString, "Floci does not support " + construct + " in fleet index queries");
    }

    @Test
    void searchMatchesValuesCaseInsensitivelyInThingNameOrder() {
        fleet();

        assertEquals(List.of("alpha-1"), names("thingName:ALPHA-1"));
        assertEquals(List.of("alpha-1"), names("attributes.site:north"));
        assertEquals(List.of("alpha-1", "alpha-2"), names("attributes.provider:Acme"));
        assertEquals(List.of("alpha-1", "beta-1"), names("thingTypeName:SENSOR"));
        assertEquals(List.of("alpha-1", "alpha-2"), names("thingGroupNames:north-group"));
        assertEquals(List.of("alpha-2"), names("thingGroupNames:south-group"));
        assertEquals(List.of("gamma"), names("thingId:" + iot.service.describeThing("gamma", REGION).getThingId()));
        assertEquals(List.of("alpha-1"), names("thingName:\"Alpha-1\""));
        assertEquals(List.of(), names("thingName:alpha"));
        assertEquals(List.of(), names("attributes.missing:x"));
    }

    @Test
    void fieldNamesAreCaseSensitive() {
        fleet();

        assertEquals(List.of(), names("attributes.Site:north"));
        assertQueryFails("ThingName:alpha-1", "Unable to parse query, invalid field name, field name: ThingName");
        assertQueryFails("thingname:alpha-1", "Unable to parse query, invalid field name, field name: thingname");
    }

    @Test
    void wildcardsMatchAnyRunOrOneCharacter() {
        fleet();

        assertEquals(List.of("alpha-1", "alpha-2"), names("thingName:alpha*"));
        assertEquals(List.of("alpha-1", "alpha-2"), names("thingName:ALPHA-?"));
        assertEquals(List.of("beta-1"), names("thingName:b?ta-1"));
        assertEquals(List.of("gamma"), names("thingName:*a"));
        assertEquals(List.of("alpha-1", "alpha-2"), names("attributes.provider:a*e"));
        assertEquals(List.of(), names("thingName:alpha?"));
        assertEquals(List.of(), names("thingName:\"alpha*\""));
        assertEquals(List.of(), names("thingName:alpha\\*"));
    }

    @Test
    void existenceAndMatchAllQueries() {
        fleet();

        assertEquals(List.of("alpha-1", "beta-1"), names("thingTypeName:*"));
        assertEquals(List.of("alpha-1", "alpha-2"), names("attributes.site:*"));
        assertEquals(List.of("alpha-1", "alpha-2"), names("thingGroupNames:*"));
        assertEquals(List.of("alpha-1", "alpha-2", "beta-1", "gamma"), names("*"));
    }

    @Test
    void booleanOperatorsFollowTheMeasuredAwsPrecedence() {
        fleet();

        // AND binds tighter than OR.
        assertEquals(List.of("alpha-2", "gamma"),
                names("thingName:gamma OR attributes.provider:acme AND attributes.site:south"));
        assertEquals(List.of("alpha-2", "gamma"),
                names("attributes.provider:acme AND attributes.site:south OR thingName:gamma"));
        // Whitespace is an AND that binds looser than OR: (gamma OR acme) AND south.
        assertEquals(List.of("alpha-2"), names("thingName:gamma OR attributes.provider:acme attributes.site:south"));
        assertEquals(List.of("alpha-1", "alpha-2"), names("thingName:alpha* attributes.provider:acme"));
        assertEquals(List.of("alpha-2", "gamma"),
                names("(thingName:gamma OR attributes.provider:acme) AND NOT thingTypeName:sensor"));
        // NOT applies to the term after it only.
        assertEquals(List.of("beta-1"), names("NOT attributes.provider:acme AND thingTypeName:sensor"));
        assertEquals(List.of("alpha-2", "gamma"), names("NOT thingTypeName:sensor"));
        assertEquals(List.of("alpha-2", "gamma"), names("-thingTypeName:sensor"));
        assertEquals(List.of("alpha-2"), names("thingName:alpha* -thingTypeName:sensor"));
        assertEquals(List.of("alpha-2"), names("thingName:alpha* - thingTypeName:sensor"));
        assertEquals(List.of("alpha-1", "alpha-2", "gamma"), names("-(thingTypeName:sensor AND attributes.provider:other)"));
        // The symbolic forms AWS accepts.
        assertEquals(List.of("alpha-2", "gamma"), names("!thingTypeName:sensor"));
        assertEquals(List.of("alpha-1"), names("thingTypeName:sensor && attributes.site:north"));
        assertEquals(List.of("beta-1", "gamma"), names("thingName:gamma || attributes.provider:other"));
    }

    @Test
    void documentsCarryOnlyTheMembersAThingHas() {
        fleet();

        assertEquals(json("{\"thingName\": \"alpha-1\", \"thingId\": \""
                + iot.service.describeThing("alpha-1", REGION).getThingId() + "\", \"thingTypeName\": \"sensor\", "
                + "\"thingGroupNames\": [\"north-group\"], \"attributes\": {\"site\": \"North\", \"provider\": \"acme\"}}"),
                document("alpha-1"));
        assertEquals(json("[\"north-group\", \"south-group\"]"), document("alpha-2").get("thingGroupNames"));
        assertEquals(json("{\"thingName\": \"gamma\", \"thingId\": \""
                + iot.service.describeThing("gamma", REGION).getThingId() + "\"}"), document("gamma"));
    }

    @Test
    void connectivityFieldsAreRefusedLikeShadowAndDeviceDefenderFields() {
        fleet("{\"thingIndexingMode\": \"REGISTRY\", \"thingConnectivityIndexingMode\": \"STATUS\"}");

        assertUnsupported("connectivity.connected:true", "the field connectivity.connected");
        assertUnsupported("connectivity.clientId:alpha-1", "the field connectivity.clientId");
        assertUnsupported("connectivity.disconnectReason:CONNECTION_LOST", "the field connectivity.disconnectReason");
        assertUnsupported("thingName:gamma OR connectivity.connected:false", "the field connectivity.connected");
    }

    @Test
    void documentsCarryNoConnectivityWhileConnectivityIsIndexed() {
        fleet("{\"thingIndexingMode\": \"REGISTRY\", \"thingConnectivityIndexingMode\": \"STATUS\"}");

        assertFalse(document("alpha-1").has("connectivity"));
        assertEquals(json("{\"thingName\": \"gamma\", \"thingId\": \""
                + iot.service.describeThing("gamma", REGION).getThingId() + "\"}"), document("gamma"));
    }

    @Test
    void connectivityQueriesNeedConnectivityIndexing() {
        fleet();

        assertSearchFails(query("connectivity.connected:true"), "InvalidRequestException", 400, CONNECTIVITY_NOT_ENABLED);
        assertSearchFails(query("thingName:gamma OR connectivity.clientId:gamma"), "InvalidRequestException", 400,
                CONNECTIVITY_NOT_ENABLED);
        assertFalse(document("alpha-1").has("connectivity"));
    }

    @Test
    void shadowAndDeviceDefenderQueriesNeedTheirIndexing() {
        fleet();

        assertSearchFails(query("shadow.reported.x:1"), "InvalidRequestException", 400, "Query includes one or more "
                + "constraints for Shadow attribute, but Shadow indexing is not enabled for AWS_Things index");
        assertSearchFails(query("deviceDefender.violationCount:1"), "InvalidRequestException", 400,
                "Query includes one or more constraints for Devicedefender attribute, but Devicedefender indexing "
                        + "is not enabled for AWS_Things index");
    }

    @Test
    void classicAndNamedShadowFieldsFollowTheirOwnIndexing() {
        fleet("""
            {"thingIndexingMode": "REGISTRY", "namedShadowIndexingMode": "ON",
             "filter": {"namedShadowNames": ["building"]}}
            """);

        assertSearchFails(query("shadow.reported.x:1"), "InvalidRequestException", 400, "Query includes one or more "
                + "constraints for Shadow attribute, but Shadow indexing is not enabled for AWS_Things index");
        assertUnsupported("shadow.name.building.reported.type:x", "the field shadow.name.building.reported.type");
        assertQueryFails("shadow.name.other.reported.a:1",
                "Unable to parse query, invalid field name, field name: shadow.name.other.reported.a");
        assertQueryFails("shadow.name.building:x",
                "Unable to parse query, invalid field name, field name: shadow.name.building");
        assertQueryFails("shadow.name:x", "Unable to parse query, invalid field name, field name: shadow.name");

        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY_AND_SHADOW"}}
            """);
        assertQueryFails("shadow.name.building.reported.type:x",
                "Unable to parse query, invalid field name, field name: shadow.name.building.reported.type");
        assertUnsupported("shadow.reported.x:1", "the field shadow.reported.x");
    }

    @Test
    void awsValidConstructsFlociDoesNotEvaluateAreRefused() {
        fleet("""
            {"thingIndexingMode": "REGISTRY_AND_SHADOW", "thingConnectivityIndexingMode": "STATUS",
             "deviceDefenderIndexingMode": "VIOLATIONS"}
            """);

        assertUnsupported("attributes.site:[a TO z]", "range queries");
        assertUnsupported("attributes.site:{a TO z}", "range queries");
        assertUnsupported("thingName>a", "comparisons");
        assertUnsupported("attributes.count>=5", "comparisons");
        assertUnsupported("attributes.count<5", "comparisons");
        assertUnsupported("attributes.count<=5", "comparisons");
        assertUnsupported("alpha", "free text terms");
        assertUnsupported("thingName:alpha-1 or thingName:gamma", "free text terms");
        assertUnsupported("\"alpha-1\"", "free text terms");
        assertUnsupported("attributes.site:(north OR south)", "field grouping");
        assertUnsupported("shadow.reported.x:1", "the field shadow.reported.x");
        assertUnsupported("deviceDefender.violationCount:1", "the field deviceDefender.violationCount");
        assertUnsupported("connectivity.timestamp:0", "the field connectivity.timestamp");
        assertUnsupported("connectivity.connected:true", "the field connectivity.connected");
    }

    @Test
    void malformedQueriesAreSyntaxErrors() {
        fleet();

        for (String queryString : List.of("thingName:", "thingName:(a", "thingName:a AND", "AND thingName:a",
                "thingName:a OR", "thingName:a NOT", "NOT", "!", "()", "thingName:a)", "thingName:a -",
                "NOT NOT thingName:a", "NOT -thingName:a", "-NOT thingName:a", "thingName:a AND OR thingName:b",
                "thingName:\"a", "thingName:a\"", " ", "attributes.count:>5")) {
            assertQueryFails(queryString, "Unable to parse query, invalid syntax");
        }
    }

    @Test
    void rangesAreParsedAsAwsParsesThem() {
        fleet();

        for (String queryString : List.of("thingName:[", "thingName:[a", "thingName:[a TO", "thingName:[a TO b",
                "thingName:[a to b]", "thingName:[a TO b TO c]", "thingName:{", "thingName:]", "thingName:[]",
                "thingName:a]", "thingName:[aTOb]", "thingName:[a TO b]]", "thingName:a]b", "thingName:a[b",
                "thingName:a}", "thingName:a{b", "attributes.a[0]:x", "thingName:[TO b]", "thingName:[a TO]",
                "[a TO]", "thingName:[a TO b)", "thingName:[a\tTO\tb]")) {
            assertQueryFails(queryString, "Unable to parse query, invalid syntax");
        }
        assertQueryFails("bogusfield:1 OR thingName:[a", "Unable to parse query, invalid syntax");
        for (String queryString : List.of("thingName:[a b]", "thingName:[a TO b}", "thingName:[* TO b]",
                "thingName:[ a TO b ]", "thingName:[\"a\" TO \"b\"]", "thingName:[\"a]\" TO b]",
                "thingName:[a[ TO b]", "NOT thingName:[a TO b]", "(thingName:[a TO b])",
                "thingName:[a TO b](thingName:c)")) {
            assertUnsupported(queryString, "range queries");
        }
        assertUnsupported("[a TO b]", "free text terms");
        assertEquals(List.of(), names("thingName:a\\]"));
        assertEquals(List.of(), names("thingName:\"a]\""));

        String range = "thingName:[a TO b] ";
        String eight = String.join(" ", Collections.nCopies(8, "thingName:x"));
        assertUnsupported(range.repeat(4) + eight, "range queries");
        assertQueryFails(range.repeat(5) + eight,
                "Unable to parse query, number of terms cannot exceed 12, terms found: 13");
    }

    @Test
    void unknownFieldsAreInvalidFieldNames() {
        fleet();

        for (String field : List.of("bogusfield", "registry.version", "*", "attributes", "attributes.")) {
            assertQueryFails(field + ":1", "Unable to parse query, invalid field name, field name: " + field);
        }
    }

    @Test
    void queryTypesAwsRejectsAreRejectedWithItsMessages() {
        fleet();

        assertQueryFails("+thingName:a", "Unable to parse query, unsupported operator - \"+\"");
        assertQueryFails("thingName:a~", "Unable to parse query, unsupported query type - fuzzy");
        assertQueryFails("thingName:a~2", "Unable to parse query, unsupported query type - fuzzy");
        assertQueryFails("thingName:/a/", "Unable to parse query, unsupported query type - regular expression");
        assertQueryFails("thingName:a^2", "Unable to parse query, unsupported query type - boost");
    }

    @Test
    void escapedFuzzyAndBoostCharactersAreLiteral() {
        fleet();

        assertEquals(List.of(), names("thingName:a\\~b"));
        assertEquals(List.of(), names("attributes.x:a\\^b"));
        assertEquals(List.of(), names("thingName:\"a~b^c\""));
        assertQueryFails("thingName:a~", "Unable to parse query, unsupported query type - fuzzy");
    }

    @Test
    void aQueryStringOfAtMost1000CodePointsIsAccepted() {
        fleet();
        String longest = "thingName:" + "\u00e9".repeat(990);
        String tooLong = longest + "\u00e9";

        assertEquals(List.of(), names(longest));
        assertSearchFails(query(tooLong), "InvalidRequestException", 400, "1 validation error detected: Value '"
                + tooLong + "' at 'queryString' failed to satisfy constraint: "
                + "Member must have length less than or equal to 1000");
        assertEquals(List.of("gamma"), names("(".repeat(490) + "thingName:gamma" + ")".repeat(490)));
    }

    @Test
    void aQueryOfAtMost12TermsIsAccepted() {
        fleet();
        String twelve = String.join(" OR ", Collections.nCopies(12, "thingName:gamma"));
        String tooMany = "Unable to parse query, number of terms cannot exceed 12, terms found: 13";

        assertEquals(List.of("gamma"), names(twelve));
        assertEquals(List.of("alpha-1", "alpha-2", "beta-1", "gamma"),
                names(String.join(" OR ", Collections.nCopies(7, "NOT thingName:z"))));
        assertQueryFails(twelve + " OR thingName:gamma", tooMany);
        assertQueryFails("* " + twelve, tooMany);
        assertQueryFails("(" + twelve + ") AND NOT thingName:z", tooMany);
        assertQueryFails("bogusfield:1 OR " + twelve, tooMany);
        assertQueryFails(twelve + " OR thingName:*a*b*c*", tooMany);
    }

    @Test
    void syntaxErrorsWinOverTheTermLimitAndOtherQueryErrors() {
        fleet();

        for (String queryString : List.of("thingName:z OR ".repeat(12) + "thingName:", "bogusfield:1 OR thingName:",
                "thingName:a~ OR thingName:", "thingName:*a*b*c* OR thingName:",
                "attributes.site:[a TO b] OR thingName:", "+thingName:a OR thingName:", "thingName:?a OR thingName:",
                "bogusfield:1 OR thingName:<a", "bogusfield:1 OR (thingName:a", "bogusfield:1 AND", "bogusfield:",
                "deviceDefender.violationCount:1 OR thingName:")) {
            assertQueryFails(queryString, "Unable to parse query, invalid syntax");
        }
    }

    @Test
    void theTermLimitWinsOverAFieldWhoseIndexingIsOff() {
        fleet();
        List<String> twelve = IntStream.rangeClosed(1, 12).mapToObj(i -> "thingName:z" + i).toList();

        assertQueryFails("deviceDefender.violationCount:1 OR " + String.join(" OR ", twelve) + " OR thingName:y",
                "Unable to parse query, number of terms cannot exceed 12, terms found: 14");
    }

    @Test
    void fieldGroupValuesCountAsTermsButTheFieldDoesNot() {
        fleet();
        List<String> values = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m");
        String tooMany = "Unable to parse query, number of terms cannot exceed 12, terms found: 13";

        assertQueryFails("thingName:(" + String.join(" OR ", values) + ")", tooMany);
        assertQueryFails("thingName:(" + String.join(" OR ", values.subList(0, 12)) + ") OR thingName:x", tooMany);
        assertUnsupported("thingName:(" + String.join(" OR ", values.subList(0, 11)) + ") OR thingName:x",
                "field grouping");
        for (String queryString : List.of("thingName:()", "thingName:(a OR)", "thingName:(a OR b")) {
            assertQueryFails(queryString, "Unable to parse query, invalid syntax");
        }
    }

    @Test
    void aFieldGroupChecksItsFieldFirst() {
        fleet();
        String invalidFieldName = "Unable to parse query, invalid field name, field name: ";

        assertQueryFails("bogusfield:(a OR b)", invalidFieldName + "bogusfield");
        assertQueryFails("registry.version:(1 OR 2)", invalidFieldName + "registry.version");
        assertQueryFails("attributes:(a OR b)", invalidFieldName + "attributes");
        assertQueryFails("bogusfield:(a OR b) OR deviceDefender.violationCount:1", invalidFieldName + "bogusfield");
        assertQueryFails("attributes.a[0]:(x OR y)", "Unable to parse query, invalid syntax");
        assertQueryFails("bogusfield:(a OR b) OR thingName:", "Unable to parse query, invalid syntax");
        assertQueryFails("bogusfield:(a OR b OR c OR d OR e OR f OR g OR h OR i OR j OR k OR l OR m)",
                "Unable to parse query, number of terms cannot exceed 12, terms found: 13");
        assertSearchFails(query("connectivity.connected:(true OR false)"), "InvalidRequestException", 400,
                CONNECTIVITY_NOT_ENABLED);
        assertSearchFails(query("deviceDefender.violationCount:(1 OR 2)"), "InvalidRequestException", 400,
                "Query includes one or more constraints for Devicedefender attribute, but Devicedefender indexing "
                        + "is not enabled for AWS_Things index");
        assertUnsupported("thingName:(a OR b)", "field grouping");
    }

    @Test
    void aValueTakesAtMostTwoUnescapedWildcards() {
        fleet();

        assertQueryFails("thingName:*a*b*", "Unable to parse query, invalid value - too many wildcards (*). "
                + "The maximum allowed per term is 2, field name: thingName, value: *a*b*");
        assertQueryFails("attributes.x:*a*b*c", "Unable to parse query, invalid value - too many wildcards (*). "
                + "The maximum allowed per term is 2, field name: attributes.x, value: *a*b*c");
        assertEquals(List.of("alpha-1", "alpha-2", "beta-1", "gamma"), names("thingName:*a*"));
        assertEquals(List.of(), names("thingName:*a\\*b*"));
        assertEquals(List.of(), names("thingName:\"*a*b*c*\""));
        assertEquals(List.of(), names("thingName:a\\*b\\*c\\*d"));
    }

    @Test
    void aValueCannotStartWithAQuestionMark() {
        fleet();

        assertQueryFails("thingName:?a", "Unable to parse query, invalid value - wildcard character (?) is not "
                + "allowed at the start of the value, field name: thingName, value: ?a");
        assertEquals(List.of(), names("thingName:a?b"));
        assertEquals(List.of(), names("thingName:a?b?c?d?e?f"));
    }

    @Test
    void requestsAreValidatedInTheMeasuredAwsOrder() {
        assertSearchFails(mapper.createObjectNode(), "InvalidRequestException", 400, "1 validation error detected: "
                + "Value null at 'queryString' failed to satisfy constraint: Member must not be null");
        assertSearchFails(query(""), "InvalidRequestException", 400, "1 validation error detected: Value '' at "
                + "'queryString' failed to satisfy constraint: Member must have length greater than or equal to 1");
        assertSearchFails(query("*").put("maxResults", 0), "InvalidRequestException", 400,
                "1 validation error detected: Value '0' at 'maxResults' failed to satisfy constraint: "
                        + "Member must have value greater than or equal to 1");
        assertSearchFails(query("*").put("indexName", ""), "InvalidRequestException", 400,
                "indexName cannot be empty.");
        assertSearchFails(query("*").put("indexName", "Nope").put("queryVersion", "bogus"), "InvalidRequestException",
                400, "Invalid queryVersion. Expected one of: [2017-09-30]");
        assertSearchFails(query("*").put("indexName", "Nope"), "InvalidRequestException", 400,
                "Unrecognized indexName Nope");
        assertSearchFails(query("bogusfield:1"), "ResourceNotFoundException", 404,
                "Index AWS_Things does not exist. Please enable index by calling UpdateIndexingConfiguration");
        assertSearchFails(query("*").put("indexName", "AWS_ThingGroups"), "ResourceNotFoundException", 404,
                "Index AWS_ThingGroups does not exist. Please enable index by calling UpdateIndexingConfiguration");

        update("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"},
             "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}}
            """);
        assertSearchFails(query("*").put("indexName", "AWS_ThingGroups"), "InvalidRequestException", 400,
                "Floci does not support searching AWS_ThingGroups");
        assertEquals(List.of(), search(query("*").put("indexName", "AWS_Things").put("queryVersion", "2017-09-30"))
                .items());
    }

    @Test
    void pagesCarryATokenUntilTheLastPage() {
        fleet();

        IotService.Page<ObjectNode> first = search(query("thingName:alpha*").put("maxResults", 1));
        assertEquals("alpha-1", first.items().get(0).path("thingName").asText());
        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());
        IotService.Page<ObjectNode> second =
                search(query("thingName:alpha*").put("maxResults", 1).put("nextToken", first.nextToken()));
        assertEquals("alpha-2", second.items().get(0).path("thingName").asText());
        assertNull(second.nextToken());
        assertNull(search(query("thingName:alpha*")).nextToken());

        assertSearchFails(query("*").put("nextToken", "bogus"), "InvalidRequestException", 400, "Invalid nextToken");
        assertSearchFails(query("bogusfield:1").put("nextToken", "bogus"), "InvalidQueryException", 400,
                "Unable to parse query, invalid field name, field name: bogusfield, query string: bogusfield:1");
    }

    @Test
    void aHugeMaxResultsReturnsTheRemainingThingsWithoutAToken() {
        fleet();
        String token = search(query("*").put("maxResults", 1)).nextToken();

        IotService.Page<ObjectNode> rest =
                search(query("*").put("maxResults", Integer.MAX_VALUE).put("nextToken", token));

        assertEquals(List.of("alpha-2", "beta-1", "gamma"), thingNames(rest));
        assertNull(rest.nextToken());
    }

    @Test
    void aPageTokenContinuesAfterTheLastThingReturnedWhenEarlierThingsAreDeleted() {
        fleet();
        IotService.Page<ObjectNode> first = search(query("thingName:alpha*").put("maxResults", 1));
        assertEquals(List.of("alpha-1"), thingNames(first));
        assertNotNull(first.nextToken());

        iot.service.deleteThing("alpha-1", REGION);
        IotService.Page<ObjectNode> second =
                search(query("thingName:alpha*").put("maxResults", 1).put("nextToken", first.nextToken()));

        assertEquals(List.of("alpha-2"), thingNames(second));
        assertNull(second.nextToken());
    }

    @Test
    void aPageTokenOnlyContinuesTheRequestThatIssuedIt() {
        fleet();
        String token = search(query("thingName:alpha*").put("maxResults", 1)).nextToken();

        assertEquals(List.of("alpha-2"),
                thingNames(search(query("thingName:alpha*").put("maxResults", 5).put("nextToken", token))));
        assertEquals(List.of("alpha-2"), thingNames(search(query("thingName:alpha*").put("nextToken", token))));
        String differentParameters = "nextToken was from a request with different parameters";
        assertSearchFails(query("thingName:alpha* ").put("nextToken", token), "InvalidRequestException", 400,
                differentParameters);
        assertSearchFails(query("*").put("nextToken", token), "InvalidRequestException", 400, differentParameters);
        assertSearchFails(query("thingName:alpha*").put("indexName", "AWS_Things").put("nextToken", token),
                "InvalidRequestException", 400, differentParameters);
        assertSearchFails(query("thingName:alpha*").put("queryVersion", "2017-09-30").put("nextToken", token),
                "InvalidRequestException", 400, differentParameters);

        String explicit = search(query("thingName:alpha*").put("indexName", "AWS_Things")
                .put("queryVersion", "2017-09-30").put("maxResults", 1)).nextToken();
        assertEquals(List.of("alpha-2"), thingNames(search(query("thingName:alpha*").put("indexName", "AWS_Things")
                .put("queryVersion", "2017-09-30").put("nextToken", explicit))));
        assertSearchFails(query("thingName:alpha*").put("nextToken", explicit), "InvalidRequestException", 400,
                differentParameters);

        assertSearchFails(query("bogusfield:1").put("nextToken", token), "InvalidQueryException", 400,
                "Unable to parse query, invalid field name, field name: bogusfield, query string: bogusfield:1");
    }

    @Test
    void aTokenThisOperationCouldNotHaveIssuedIsInvalid() {
        fleet();
        String bareThingName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("alpha-1".getBytes(StandardCharsets.UTF_8));

        for (String token : List.of("YQ", "", "AAAA", bareThingName)) {
            assertSearchFails(query("*").put("nextToken", token), "InvalidRequestException", 400,
                    "Invalid nextToken");
        }

        String issued = search(query("thingName:alpha*").put("maxResults", 1)).nextToken();
        String decoded = new String(Base64.getUrlDecoder().decode(issued), StandardCharsets.UTF_8);
        String edited = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((decoded.substring(0, decoded.length() - 1) + "0").getBytes(StandardCharsets.UTF_8));
        String shortened = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(decoded.substring(0, decoded.length() - 1).getBytes(StandardCharsets.UTF_8));
        String truncated = issued.substring(0, issued.length() - 2);
        for (String token : List.of(edited, shortened, truncated)) {
            assertSearchFails(query("thingName:alpha*").put("nextToken", token), "InvalidRequestException", 400,
                    "Invalid nextToken");
        }
        assertEquals(List.of("alpha-2"), thingNames(search(query("thingName:alpha*").put("nextToken", issued))));
    }

    @Test
    void maxResultsIsAnyJsonNumberOfAtLeastOne() {
        fleet();

        assertEquals(List.of("alpha-1"), thingNames(search(query("*").put("maxResults", 1.5))));
        IotService.Page<ObjectNode> all = search(query("*").put("maxResults", 2147483648L));
        assertEquals(List.of("alpha-1", "alpha-2", "beta-1", "gamma"), thingNames(all));
        assertNull(all.nextToken());
        assertEquals(4, search(query("*").put("maxResults", 500)).items().size());
        assertSearchFails(query("*").put("maxResults", "5"), "SerializationException", 400,
                "maxResults must be a JSON number");
        assertSearchFails(query("*").put("maxResults", true), "SerializationException", 400,
                "maxResults must be a JSON number");
    }

    @Test
    void maxResultsIsTruncatedAndSaturatedAtTheIntRange() {
        fleet();

        IotService.Page<ObjectNode> all = search(query("*").put("maxResults", new BigInteger("9223372036854775808")));
        assertEquals(List.of("alpha-1", "alpha-2", "beta-1", "gamma"), thingNames(all));
        assertNull(all.nextToken());
        assertEquals(4, search(query("*").put("maxResults", new BigDecimal("1e30"))).items().size());
        assertSearchFails(query("*").put("maxResults", new BigInteger("-9223372036854775809")),
                "InvalidRequestException", 400, "1 validation error detected: Value '-2147483648' at 'maxResults' "
                        + "failed to satisfy constraint: Member must have value greater than or equal to 1");
        assertSearchFails(query("*").put("maxResults", 0.5), "InvalidRequestException", 400,
                "1 validation error detected: Value '0' at 'maxResults' failed to satisfy constraint: "
                        + "Member must have value greater than or equal to 1");
        assertSearchFails(query("*").put("maxResults", -1), "InvalidRequestException", 400,
                "1 validation error detected: Value '-1' at 'maxResults' failed to satisfy constraint: "
                        + "Member must have value greater than or equal to 1");
    }

    @Test
    void indexNameMustBeAJsonString() {
        fleet();

        assertSearchFails(query("*").put("indexName", 1), "SerializationException", 400,
                "indexName must be a JSON string");
        assertSearchFails(query("*").put("indexName", true), "SerializationException", 400,
                "indexName must be a JSON string");
    }

    @Test
    void aBodyThatIsNotAJsonObjectIsASerializationError() {
        fleet();

        for (String body : List.of("[]", "\"x\"", "1", "null", "[{\"queryString\":\"*\"}]")) {
            assertSearchBodyFails(body, "request body must be a JSON object");
        }
    }

    @Test
    void memberTypeErrorsComeBeforeEveryOtherError() {
        fleet();

        assertSearchBodyFails("{\"queryString\":\"bogusfield:\",\"nextToken\":1}", "nextToken must be a JSON string");
        assertSearchBodyFails("{\"queryString\":\"*\",\"indexName\":\"\",\"queryVersion\":1}",
                "queryVersion must be a JSON string");
        assertSearchBodyFails("{\"queryString\":\"\",\"nextToken\":true}", "nextToken must be a JSON string");
        assertSearchBodyFails("{\"queryString\":\"*\",\"indexName\":\"nope\",\"nextToken\":1}",
                "nextToken must be a JSON string");
    }

    private void assertSearchBodyFails(String body, String message) {
        AwsException failure = assertThrows(AwsException.class,
                () -> service.searchIndex(json(body), REGION), body);
        assertEquals("SerializationException", failure.getErrorCode(), body);
        assertEquals(400, failure.getHttpStatus(), body);
        assertEquals(message, failure.getMessage(), body);
    }

    private static List<String> thingNames(IotService.Page<ObjectNode> page) {
        return page.items().stream().map(thing -> thing.path("thingName").asText()).toList();
    }

    @Test
    void searchSeesOnlyTheCallersRegion() {
        fleet();
        iot.service.createThing("alpha-9", Map.of("provider", "acme"), null, "eu-west-1");

        assertEquals(List.of("alpha-1", "alpha-2"), names("attributes.provider:acme"));
        assertThrows(AwsException.class, () -> service.searchIndex(query("*"), "eu-west-1"));

        service.updateIndexingConfiguration(json("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"}}
            """), "eu-west-1");
        List<ObjectNode> other = service.searchIndex(query("attributes.provider:acme"), "eu-west-1").items();
        assertEquals(1, other.size());
        assertEquals("alpha-9", other.get(0).path("thingName").asText());
    }
}
