package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplacereporting.MarketplaceReportingClient;
import software.amazon.awssdk.services.marketplacereporting.model.GetBuyerDashboardResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MarketplaceReportingTest {

    @Test
    void usesAwsSdkWireContract() {
        try (MarketplaceReportingClient client = TestFixtures.marketplaceReportingClient()) {
            String arn = "arn:aws:aws-marketplace::000000000000:AWSMarketplace/ReportingData/Agreement_V1/Dashboard/AgreementSummary_V1";
            GetBuyerDashboardResponse response = client.getBuyerDashboard(r -> r.dashboardIdentifier(arn).embeddingDomains("https://example.com"));
            assertEquals(arn, response.dashboardIdentifier());
            assertFalse(response.embedUrl().isBlank());
        }
    }
}
