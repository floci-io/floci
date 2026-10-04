package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplacediscovery.MarketplaceDiscoveryClient;
import software.amazon.awssdk.services.marketplacediscovery.model.SearchListingsResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MarketplaceDiscoveryTest {

    private static final List<String> REGIONS = List.of("us-east-1", "us-west-2", "eu-west-1");

    @Test
    void usesAwsSdkWireContract() {
        assumeTrue(REGIONS.contains(TestFixtures.region().id()),
                "Marketplace Discovery has no endpoint in this region");
        try (MarketplaceDiscoveryClient client = TestFixtures.marketplaceDiscoveryClient()) {
            SearchListingsResponse response = client.searchListings(r -> r.searchText("sdk-compat"));
            assertTrue(response.totalResults() >= 0);
            assertNotNull(response.listingSummaries());
        }
    }
}
