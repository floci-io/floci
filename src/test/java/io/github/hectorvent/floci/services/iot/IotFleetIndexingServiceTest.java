package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iot.IotMqttBrokerService.Connectivity;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.Field;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.GeoLocation;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.ThingIndexing;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private static final String ACCOUNT = IotServiceTestSupport.ACCOUNT;
    private static final String CONNECTIVITY_NOT_ENABLED = "Query includes one or more constraints for Connectivity "
            + "attribute, but Connectivity indexing is not enabled for AWS_Things index";

    private final ObjectMapper mapper = new ObjectMapper();
    private final IotServiceTestSupport iot = new IotServiceTestSupport(REGION, null);
    private final IotMqttBrokerService broker = mock(IotMqttBrokerService.class);
    private final IotFleetIndexingService service =
            new IotFleetIndexingService(new InMemoryStorage<>(), iot.service, broker);

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
        return service.searchIndex(request, ACCOUNT, REGION);
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
        assertEquals(List.of("beta-1"), names("thingName:?eta-1"));
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
    void connectivityReportsTheBrokerStateOfTheCallersAccountAndRegion() {
        fleet("{\"thingIndexingMode\": \"REGISTRY\", \"thingConnectivityIndexingMode\": \"STATUS\"}");
        when(broker.connectivity(ACCOUNT, REGION, "alpha-1")).thenReturn(Optional.of(
                new Connectivity(true, 1_700_000_000_000L, null, 30, true)));
        when(broker.connectivity(ACCOUNT, REGION, "alpha-2")).thenReturn(Optional.of(
                new Connectivity(false, 1_700_000_100_000L, "CLIENT_INITIATED_DISCONNECT", 60, false)));

        assertEquals(List.of("alpha-1"), names("connectivity.connected:true"));
        assertEquals(List.of("alpha-2", "beta-1", "gamma"), names("connectivity.connected:FALSE"));
        assertEquals(List.of("alpha-2"), names("connectivity.disconnectReason:client_initiated_disconnect"));
        assertEquals(List.of("beta-1"), names("connectivity.clientId:beta-1"));
        assertEquals(json("""
            {"connected": true, "timestamp": 1700000000000, "keepAliveDuration": 30, "cleanSession": true,
             "clientId": "alpha-1"}
            """), document("alpha-1").get("connectivity"));
        assertEquals(json("""
            {"connected": false, "timestamp": 1700000100000, "disconnectReason": "CLIENT_INITIATED_DISCONNECT",
             "keepAliveDuration": 60, "cleanSession": false, "clientId": "alpha-2"}
            """), document("alpha-2").get("connectivity"));
        assertEquals(json("""
            {"clientId": "beta-1", "connected": false, "timestamp": 0}
            """), document("beta-1").get("connectivity"));
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

        IotService.Page<ObjectNode> rest =
                search(query("*").put("maxResults", Integer.MAX_VALUE).put("nextToken", "1"));

        assertEquals(List.of("alpha-2", "beta-1", "gamma"),
                rest.items().stream().map(thing -> thing.path("thingName").asText()).toList());
        assertNull(rest.nextToken());
    }

    @Test
    void searchSeesOnlyTheCallersRegion() {
        fleet();
        iot.service.createThing("alpha-9", Map.of("provider", "acme"), null, "eu-west-1");

        assertEquals(List.of("alpha-1", "alpha-2"), names("attributes.provider:acme"));
        assertThrows(AwsException.class, () -> service.searchIndex(query("*"), ACCOUNT, "eu-west-1"));

        service.updateIndexingConfiguration(json("""
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"}}
            """), "eu-west-1");
        List<ObjectNode> other = service.searchIndex(query("attributes.provider:acme"), ACCOUNT, "eu-west-1").items();
        assertEquals(1, other.size());
        assertEquals("alpha-9", other.get(0).path("thingName").asText());
    }
}
