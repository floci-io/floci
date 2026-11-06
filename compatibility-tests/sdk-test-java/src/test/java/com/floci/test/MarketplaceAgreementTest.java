package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplaceagreement.MarketplaceAgreementClient;
import software.amazon.awssdk.services.marketplaceagreement.model.SearchAgreementsResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MarketplaceAgreementTest {

    private static final List<String> REGIONS = List.of(
            "us-east-1", "us-iso-east-1", "us-isob-east-1", "us-isof-south-1", "eusc-de-east-1");

    @Test
    void usesAwsSdkWireContract() {
        assumeTrue(REGIONS.contains(TestFixtures.region().id()),
                "AWS Marketplace Agreement has no endpoint in this region");
        try (MarketplaceAgreementClient client = TestFixtures.marketplaceAgreementClient()) {
            SearchAgreementsResponse response = client.searchAgreements(r -> r.filters(
                    f -> f.name("AgreementType").values("PurchaseAgreement")));
            assertNotNull(response.agreementViewSummaries());
        }
    }
}
