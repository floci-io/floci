package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplacecatalog.MarketplaceCatalogClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class MarketplaceCatalogTest {

    @Test
    void usesAwsSdkWireContract() {
        try (MarketplaceCatalogClient client = TestFixtures.marketplaceCatalogClient()) {
            var response = client.listEntities(r -> r.catalog("AWSMarketplace").entityType("SaaSProduct"));
            assertNotNull(response.entitySummaryList());
        }
    }
}
