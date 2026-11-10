package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceCatalogTest {

    @Test
    void plainJsonFormatIsDeclaredByTheDescriptor() {
        ServiceDescriptor legacy = descriptor("example", Set.of("Example.", "LegacyExample."),
                Set.of(ServiceProtocol.JSON, ServiceProtocol.PLAIN_JSON));
        ServiceDescriptor modern = descriptor("modern", Set.of("Modern."), Set.of(ServiceProtocol.JSON));
        ServiceCatalog catalog = new ServiceCatalog(List.of(legacy, modern));

        assertEquals(legacy, catalog.matchPlainJsonTarget("Example.Read").orElseThrow().descriptor());
        assertEquals(legacy, catalog.matchPlainJsonTarget("LegacyExample.Read").orElseThrow().descriptor());
        assertTrue(catalog.matchPlainJsonTarget("Modern.Read").isEmpty());
        assertTrue(catalog.matchPlainJsonTarget("Unknown.Read").isEmpty());
        assertTrue(catalog.matchPlainJsonTarget(null).isEmpty());
    }

    @Test
    void moreSpecificTargetOwnsItsAcceptedFormats() {
        ServiceDescriptor legacy = descriptor("example", Set.of("Example."),
                Set.of(ServiceProtocol.JSON, ServiceProtocol.PLAIN_JSON));
        ServiceDescriptor modern = descriptor("specific", Set.of("Example.Specific."), Set.of(ServiceProtocol.JSON));
        ServiceCatalog catalog = new ServiceCatalog(List.of(legacy, modern));

        assertEquals(modern, catalog.matchTarget("Example.Specific.Read").orElseThrow().descriptor());
        assertTrue(catalog.matchPlainJsonTarget("Example.Specific.Read").isEmpty());
    }

    @Test
    void jsonOnlyPrefixesCannotSelectCborAndAreRecognizedByTheirDescriptor() {
        ServiceDescriptor service = new ServiceDescriptor("example", "example", true, true,
                null, null, 0L, null, ServiceProtocol.JSON, Set.of(ServiceProtocol.JSON, ServiceProtocol.CBOR),
                Set.of("LegacyExample.", "Example."), Set.of("example"), Set.of(), Set.of(),
                Set.of("LegacyExample."));
        ServiceCatalog catalog = new ServiceCatalog(List.of(service));

        assertTrue(catalog.matchTarget("LegacyExample.Read").orElseThrow().jsonOnly());
        assertFalse(catalog.matchTarget("Example.Read").orElseThrow().jsonOnly());
        assertTrue(catalog.byCborSdkServiceId("LegacyExample").isEmpty());
        assertEquals(service, catalog.byCborSdkServiceId("Example").orElseThrow());
    }

    private static ServiceDescriptor descriptor(String name, Set<String> prefixes, Set<ServiceProtocol> protocols) {
        return new ServiceDescriptor(name, name, true, true, null, null, 0L, null,
                ServiceProtocol.JSON, protocols, prefixes, Set.of(name), Set.of(), Set.of(), Set.of());
    }
}
