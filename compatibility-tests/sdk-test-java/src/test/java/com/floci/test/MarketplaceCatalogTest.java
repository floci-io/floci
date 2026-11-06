package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplacecatalog.MarketplaceCatalogClient;
import software.amazon.awssdk.services.marketplacecatalog.model.ListEntitiesResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MarketplaceCatalogTest {

    private static final List<String> REGIONS = List.of("us-east-1");

    @Test
    void usesAwsSdkWireContract() {
        assumeTrue(REGIONS.contains(TestFixtures.region().id()),
                "AWS Marketplace Catalog has no endpoint in this region");
        try (MarketplaceCatalogClient client = TestFixtures.marketplaceCatalogClient()) {
            ListEntitiesResponse response = client.listEntities(r -> r.catalog("AWSMarketplace").entityType("SaaSProduct"));
            assertNotNull(response.entitySummaryList());
        }
    }
}
