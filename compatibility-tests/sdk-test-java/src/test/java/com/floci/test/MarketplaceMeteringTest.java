package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplacemetering.MarketplaceMeteringClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class MarketplaceMeteringTest {

    @Test
    void usesAwsSdkWireContract() {
        try (MarketplaceMeteringClient client = TestFixtures.marketplaceMeteringClient()) {
            var response = client.resolveCustomer(r -> r.registrationToken("local-registration-token"));
            assertNotNull(response.customerAWSAccountId());
            assertNotNull(response.licenseArn());
        }
    }
}
