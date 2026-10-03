package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplaceagreement.MarketplaceAgreementClient;
import software.amazon.awssdk.services.marketplaceagreement.model.SearchAgreementsResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class MarketplaceAgreementTest {

    @Test
    void usesAwsSdkWireContract() {
        try (MarketplaceAgreementClient client = TestFixtures.marketplaceAgreementClient()) {
            SearchAgreementsResponse response = client.searchAgreements(r -> r.filters(
                    f -> f.name("AgreementType").values("PurchaseAgreement")));
            assertNotNull(response.agreementViewSummaries());
        }
    }
}
