package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplaceentitlement.MarketplaceEntitlementClient;
import software.amazon.awssdk.services.marketplaceentitlement.model.GetEntitlementsResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MarketplaceEntitlementTest {

    private static final List<String> REGIONS = List.of("us-east-1");

    @Test
    void usesAwsSdkWireContract() {
        assumeTrue(REGIONS.contains(TestFixtures.region().id()),
                "Floci serves AWS Marketplace Entitlement only in us-east-1");
        try (MarketplaceEntitlementClient client = TestFixtures.marketplaceEntitlementClient()) {
            GetEntitlementsResponse response = client.getEntitlements(r -> r.productCode("product-local"));
            assertNotNull(response.entitlements());
            assertTrue(response.entitlements().isEmpty());
        }
    }
}
