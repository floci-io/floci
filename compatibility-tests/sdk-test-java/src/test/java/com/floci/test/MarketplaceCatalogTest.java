package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplacecatalog.MarketplaceCatalogClient;
import software.amazon.awssdk.services.marketplacecatalog.model.ListEntitiesResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class MarketplaceCatalogTest {

    @Test
    void usesAwsSdkWireContract() {
        try (MarketplaceCatalogClient client = TestFixtures.marketplaceCatalogClient()) {
            ListEntitiesResponse response = client.listEntities(r -> r.catalog("AWSMarketplace").entityType("SaaSProduct"));
            assertNotNull(response.entitySummaryList());
        }
    }
}
