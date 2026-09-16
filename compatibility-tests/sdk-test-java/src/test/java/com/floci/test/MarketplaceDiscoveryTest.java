package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplacediscovery.MarketplaceDiscoveryClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketplaceDiscoveryTest {

    @Test
    void usesAwsSdkWireContract() {
        try (MarketplaceDiscoveryClient client = TestFixtures.marketplaceDiscoveryClient()) {
            var response = client.searchListings(r -> r.searchText("sdk-compat"));
            assertTrue(response.totalResults() >= 0);
            assertNotNull(response.listingSummaries());
        }
    }
}
