package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplaceagreement.MarketplaceAgreementClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class MarketplaceAgreementTest {

    @Test
    void usesAwsSdkWireContract() {
        try (MarketplaceAgreementClient client = TestFixtures.marketplaceAgreementClient()) {
            var response = client.searchAgreements(r -> r.filters(
                    f -> f.name("AgreementType").values("PurchaseAgreement")));
            assertNotNull(response.agreementViewSummaries());
        }
    }
}
