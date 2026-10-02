package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplaceentitlement.MarketplaceEntitlementClient;
import software.amazon.awssdk.services.marketplaceentitlement.model.GetEntitlementsResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketplaceEntitlementTest {

    @Test
    void usesAwsSdkWireContract() {
        try (MarketplaceEntitlementClient client = TestFixtures.marketplaceEntitlementClient()) {
            GetEntitlementsResponse response = client.getEntitlements(r -> r.productCode("product-local"));
            assertNotNull(response.entitlements());
            assertTrue(response.entitlements().isEmpty());
        }
    }
}
