package io.github.hectorvent.floci.services.iot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The fleet indexing configuration of an account in one region, as UpdateIndexingConfiguration
 * stores it. Managed fields are not stored: AWS derives them from the modes.
 */
@RegisterForReflection
public record IotIndexingConfiguration(ThingIndexing thing, String thingGroupIndexingMode) {

    public static final IotIndexingConfiguration OFF = new IotIndexingConfiguration(ThingIndexing.OFF, "OFF");

    @RegisterForReflection
    public record ThingIndexing(String thingIndexingMode, String thingConnectivityIndexingMode,
                                String deviceDefenderIndexingMode, String namedShadowIndexingMode,
                                List<String> namedShadowNames, List<GeoLocation> geoLocations,
                                List<String> includeSocketInformation, List<Field> customFields) {

        public static final ThingIndexing OFF =
                new ThingIndexing("OFF", "OFF", "OFF", "OFF", List.of(), List.of(), List.of(), List.of());
    }

    @RegisterForReflection
    public record Field(String name, String type) {
    }

    @RegisterForReflection
    public record GeoLocation(String name, String order) {
    }
}
